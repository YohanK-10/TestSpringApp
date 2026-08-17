# AtlasWatch changelog

This is a developer-oriented record of what changed, how bugs were diagnosed, and how each change was verified. New entries go at the top.

## 2026-08-16

### Made recommendation evaluation hermetic and re-measured Phase 2 on a frozen catalog

**Symptom**

The Phase 1 baseline and the Phase 2 rotation comparison recorded different catalog checksums. Four prompts that produced results in the baseline produced none in the Phase 2 control, and total returned items fell from 2,055 to 1,293. The two reports could not be read together.

**Investigation and evidence**

The evaluation tests set `atlaswatch.catalog.ingestion.enabled=false`, but that property only disables ingestion inside the evaluator's own Spring context. The Docker backend was running against the same `Travel` database and continued its scheduled ingestion during and between runs. The recorded checksum also hashed `cached_at`, which every summary refresh rewrites, so it changed on any ingestion tick regardless of whether ranking-relevant content moved. Nothing compared the recorded checksum against an expected value, so the mismatch passed silently.

Building a schema from scratch to isolate the database then exposed a second defect. `V1__initial_schema.sql` declares `CREATE SEQUENCE db_watchList`; PostgreSQL folds the unquoted identifier to `db_watchlist`, while Hibernate's physical naming strategy resolves the entity's `db_watchList` to `db_watch_list`. The long-lived database was created by `ddl-auto` and baselined into Flyway at V1, so V1's SQL had never actually executed anywhere and the mismatch was invisible until a clean Flyway run. A schema diff between a freshly migrated database and the baselined one confirmed the sequence name and the `watch_list.id` default were the only differences; all columns matched.

**Root cause**

Recording a checksum of a shared mutable database is not the same as freezing an evaluation input, and a migration that has only ever been baselined is untested SQL.

**Change**

- `V6__align_watchlist_sequence_name.sql` converges both database shapes on `db_watch_list`, advancing the sequence only when it could still collide with an existing row.
- Both evaluations now seed the versioned `session-intent-catalog-v1` dataset into a disposable Testcontainers PostgreSQL instance and assert the content fingerprint before and after the run.
- `TmdbApiService` and the TMDB `RestClient` are replaced with mocks, so the context cannot reach the network, and the test asserts zero interactions.
- Artifacts record `datasetVersion`, `catalogSize`, and `contentFingerprint` instead of a volatile checksum.
- Runtime and era violation rates joined the paired bootstrap metrics and became promotion gates, keyed on the CI rather than the point estimate.
- Rotation now preserves the pre-rotation slate's runtime-compliance count, using the same substitution rule that already protected mood coverage.
- Requests carrying rotation state bypass result caching entirely rather than producing single-use cache entries.
- Item-level slices report undefined session metrics as `—` instead of `0.0000`.
- `moods` is capped at five entries in the API and the picker UI.
- The report adds a `requestedMoodCount` slice.

**Verification**

Full backend suite: 166 passing, 0 failures, 4 opt-in evaluations skipped. Re-run on the frozen 793-movie catalog, Phase 2 passed every gate: consecutive overlap `0.1218 → 0.0945` (paired CI `[-0.0402, -0.0158]`), unique-result rate `0.3589 → 0.4205` (`[0.0336, 0.0946]`), top-1 repetition `0.5362 → 0.2360` (`[-0.2634, -0.1579]`), with fill and rule coverage unchanged. The previously waived runtime regression is gone: violations now move `0.3146 → 0.3127` (`[-0.0023, 0.0000]`). The baseline and rotation artifacts now share one dataset version and fingerprint.

The new mood-count slice quantifies the Phase 3 target: across 360 three-mood sessions the full-shortlist rate is `0.0000` (160 items returned against 1,800 requested), versus `0.6550` for two-mood sessions.

The pre-freeze artifacts are retained under `docs/evaluation/runs/archive-mutable-catalog/` and must not be compared with the frozen set.

### Removed a production-only frontend restart during container startup

**Symptom**

The freshly recreated frontend container restarted once before becoming healthy. Its first process attempted to install TypeScript at runtime and then failed to load `next.config.ts`; the second process succeeded only because that unexpected install had modified the running container.

**Investigation and evidence**

The production Docker stage deliberately runs `npm ci --omit=dev`, while TypeScript is a development dependency. The same stage copied `next.config.ts`, causing `next start` to require a compiler that was intentionally absent. Container logs showed the attempted install, `MODULE_NOT_FOUND: typescript`, and restart count `1`.

**Root cause**

The runtime configuration format contradicted the production dependency policy. A TypeScript configuration file cannot be loaded in a clean production-only Node image without TypeScript.

**Change**

Converted the type-only Next configuration to `next.config.mjs` and updated the Docker copy path. The behavior is unchanged, but `next start` can now load configuration using Node alone and never mutates its package set during startup.

**Verification**

- The rebuilt image passed lint, type validation, compilation, and generation of all 13 routes.
- Recreated only the frontend after the conversion. It reached Next Ready in 747 ms, returned HTTP 200 from `/pick-for-me`, reported healthy with restart count `0`, and logged no runtime package installation.

**Evidence boundary**

This fixes deterministic container startup. It does not audit or upgrade the frontend dependency graph; dependency vulnerability review is separate work.

### Added session-aware recommendation rotation with paired promotion evidence

**Symptom**

`Try another mix` changed the request token but did not remember the films already displayed in the current Pick for Me session. Strong catalog items could therefore recur across consecutive slates, and authenticated impression rows could be written repeatedly for the same movie during one browsing session.

**Investigation and evidence**

1. Traced Pick for Me state and found that the restored shortlist was persisted, but the request carried no bounded history of previously displayed TMDB IDs.
2. Traced backend rotation and found that the token changed deterministic sampling without making already-shown items an explicit presentation objective.
3. Traced impression persistence and found one write per returned item per request, including repeat displays; these are displays, not independent recommendation opportunities.
4. Built a paired evaluator on the same catalog snapshot: control sends no prior-slate history, while the challenger accumulates the same session's displayed IDs. The first challenger reduced repetition but failed the Phase 2 rule-derived mood-coverage gate.
5. Compared the rotated slate with its pre-rotation baseline and found that aggressive unseen substitution could replace a stronger mood match with a weaker eligible match.

**Root cause**

Rotation was modeled only as request-seeded sampling. Neither browser nor server context represented “already shown in this session,” and novelty had no explicit guard against weakening the ranked slate. Impression persistence also lacked a session-window uniqueness check.

**Change**

- Added a validated, optional `seenTmdbIds` request field capped at 50 IDs and included it in versioned cache identity.
- Pick for Me now carries the bounded shown-ID set across explicit `Try another mix` requests and stores it with the tab-scoped navigation snapshot. Filter changes start a new session history; browser Back preserves it.
- Authenticated requests union client history with distinct server-side impressions from the previous six hours; anonymous requests use the client history.
- The backend prefers qualified unseen candidates, falls back to baseline/seen candidates when the pool is exhausted, and restores only the strongest missing baseline items needed to keep aggregate rule-derived mood coverage from declining.
- Impression recording now deduplicates movie IDs and skips a movie already recorded for that user inside the six-hour session window.
- Versioned recommendation caches as `v15-session-aware-rotation`.
- Added an opt-in paired 1,600-request evaluator with paired prompt/persona cluster-bootstrap intervals. Failed challengers write artifacts before the promotion assertion so rejected experiments remain inspectable.

**Verification**

> Superseded. The figures below were measured against the live database while a
> backend continued ingesting into it, so the Phase 1 and Phase 2 artifacts of
> this run describe different catalogs. They are kept as a historical record.
> The current, hermetically measured numbers are in the entry above; the
> superseded artifacts are archived under
> `docs/evaluation/runs/archive-mutable-catalog/`.

- Focused Phase 2 backend suite: 49 tests passed before the live gate; the added mood-coverage regression brought the recommendation-service suite to 38 passing tests.
- Paired live evaluation: 800 control and 800 challenger sessions over 20 prompts, four personas, ten rotations, and one catalog snapshot; the opt-in test passed all promotion assertions.
- Full-shortlist rate, slot fill, rule-derived mood coverage, and primary-mood violation were unchanged between variants.
- Consecutive overlap fell from `0.1236` to `0.0893` (paired delta `-0.0343`, 95% CI `[-0.0551, -0.0172]`).
- Unique-result rate rose from `0.1751` to `0.2447` (paired delta `+0.0696`, 95% CI `[0.0314, 0.1127]`).
- Top-1 repeat rate fell from `0.6429` to `0.3857` (paired delta `-0.1267`, 95% CI `[-0.1712, -0.0823]`).
- Full backend regression suite: 162 tests, 0 failures, 0 errors, and 3 intentional skips (application-context smoke plus the two opt-in live evaluators).
- Frontend production build passed lint, TypeScript validation, compilation, and all 13 routes.
- Rebuilt and recreated the backend/frontend containers without replacing PostgreSQL or Redis volumes. All four services reported healthy; Flyway validated schema version 5 and the backend loaded the 6,293-item collaborative model.
- Deployed cold-start API smoke: two five-item `Tense + Dark` slates were full and internally unique; submitting the first slate as `seenTmdbIds` produced zero overlap in the second slate.

**Evidence boundary**

This phase proves less repetition without regression in the specified fill and rule-derived mood guardrails. The mood measure is circular and is not human relevance evidence. Runtime violations moved from `0.2838` to `0.3070`; runtime was not a Phase 2 promotion metric and remains a visible diagnostic for the next intent-quality phase. Exact display propensities remain absent because the final policy is deterministic rather than a known randomized serving policy.

### Completed the semantic-catalog gate and reproducible session-intent baseline

**Symptom**

Recommendation quality was being judged by manually selecting moods and repeatedly clicking `Try another mix`. Sparse or unknown keyword ingestion could be mistaken for a ranking defect, and there was no repeatable way to quantify fill, constraint behavior, rotation, or cold/warm differences across many requests.

**Investigation and evidence**

1. Counted the catalog without joining keyword rows into the movie denominator. The database contains 934 movies and 770 recommendation-ready movies; an earlier 12,507 figure was a join-row count, not a movie count.
2. Found 742 recommendation-ready rows with a known successful semantic-detail sync before backfill. Keyword presence could not distinguish “not fetched” from a valid zero-keyword TMDB response.
3. Ran an opt-in, resumable detail backfill. It attempted the remaining 28 recommendation-ready rows, succeeded for all 28, and left 770/770 with a completion marker. Keyword-backed rows remained 742, proving that the other 28 were completed empty-keyword responses.
4. Exercised the real ranking pipeline over 20 prompts, four personas, and ten rotations. The first report audit exposed a bootstrap defect: rotation overlap was 0.1339 while its interval excluded that estimate. Duplicate bootstrap cluster IDs had merged independent sampled copies.
5. A final failure-path audit found that the unbounded backfill always queried page zero. A failed row retained its null completion marker and could therefore be selected repeatedly in the same run. The 28-row live run did not exhibit the symptom because every request succeeded.

**Root cause**

The catalog had no durable “semantic detail fetch completed” state, and manual recommendation checks had no frozen matrix, catalog identity, cluster-aware uncertainty, or diagnostic slices. The initial bootstrap implementation resampled observations but reused original cluster IDs, which is invalid for statistics that regroup by cluster. The first-page backfill loop also relied only on successful rows disappearing from the query and did not defer a failed ID.

**Change**

- Added Flyway V5 and `semantic_metadata_synced_at`; successful detail responses now mark completion even when their keyword list is empty.
- Added a disabled-by-default, rate-limited, resumable metadata backfill with bounded attempts, per-movie failure isolation, and one post-run cache invalidation.
- Added a read-only evaluator seam that bypasses catalog seeding, user/review/watchlist state, caches, and recommendation-impression writes while retaining the production retrieval/ranking path.
- Added the frozen 800-session prompt/persona/rotation matrix, label-free metrics, decision-driving slices, deterministic replay check, cluster bootstrap intervals, paired-delta support, and JSON/CSV/Markdown artifacts.
- Corrected bootstrap resampling by assigning each sampled cluster copy a unique identity.
- Deferred failed movie IDs for the remainder of one maintenance run, preventing an unbounded same-run retry while leaving them eligible for the next deliberate run.
- Kept rule-derived mood coverage explicitly labeled as circular implementation evidence, not semantic relevance.

**Verification**

- Phase 0 focused suite: 8 tests passed with 0 failures/errors.
- Phase 0 live backfill: 28 attempted, 28 succeeded, 0 failed; 770/770 recommendation-ready rows marked complete.
- Before the final baseline, normal ingestion grew the eligible catalog to 775 rows; 775/775 were semantically complete and the report retained their SHA-256 snapshot identity.
- Evaluator-focused suite after implementation: 39 tests passed with 0 failures/errors.
- Bootstrap regression suite: 2 tests passed with 0 failures/errors.
- Live Phase 1 run against PostgreSQL 16.7 schema version 5: 800 sessions plus deterministic replay; 1 test passed with 0 failures/errors. The final versioned report was regenerated after the bootstrap correction.
- Final focused Phase 0/1 suite: 40 tests passed with 0 failures/errors, including the non-mutation assertion.
- Full backend suite: 156 tests, 0 failures, 0 errors, and 2 intentional skips (the external application-context smoke test and opt-in live evaluator).
- Backfill failure-termination regression: 2 tests passed; a failed TMDB movie was invoked exactly once in an unbounded run.
- Rebuilt and recreated only the backend container. Flyway reported schema version 5 current, the backend became healthy, PostgreSQL/Redis/frontend remained healthy, and `ATLASWATCH_METADATA_BACKFILL_RUN_ON_STARTUP=false` was verified in the final container.

**Evidence boundary**

The batch metrics quantify list construction and rule consistency; they do not establish human semantic relevance. Warm personas are synthetic taste profiles, not real cohorts. No propensity is recorded because the current policy does not expose an exact item-display probability.

### Balanced broad mood blends after a five-mood request collapsed to one result

**Symptom**

Selecting `Tense + Dark + Emotional + Mind-bending + Eerie`, any runtime, and any era requested five picks but returned only *Memento*.

**Investigation and evidence**

1. Confirmed from the visible response that the frontend accurately reported the one-result backend shortlist; this was not a rendering or pagination problem.
2. Inspected the deployed backend request logs. Candidate retrieval merged 140 eligible candidates for the authenticated request, demonstrating that the catalog and retrieval pool were not limited to one movie.
3. Traced `applyMoodIntentGate`: the prior 75% floor rounded five selected moods up to four required matches per movie. Ambiguous moods deliberately require semantic keyword/overview evidence, so only one candidate survived that near-conjunctive threshold.

**Root cause**

The previous weak-filler fix correctly protected exact three-mood intent, but applied a proportional 75% floor to larger selections. That made the UI's broad “blend” interaction behave like “every movie must match almost every mood.”

**Change**

- Exact three-mood requests still require all three and therefore cannot regress to the earlier one- or two-mood filler behavior.
- Four-or-more selections now require at least 60% coverage. A five-mood blend therefore accepts strong three-of-five matches while still rejecting one- and two-mood fillers.
- Versioned backend recommendation caches as `v14-balanced-mood-blends` so strict-v13 partial lists cannot be reused.

**Verification**

- Added a regression that keeps five-of-five and three-of-five candidates while rejecting a higher-scoring two-of-five candidate.
- Focused recommendation suite: 43 tests run with 0 failures and 0 errors.
- Full backend suite: 150 tests run, 0 failures, 0 errors, and 1 intentionally skipped application-context test.
- Rebuilt and recreated only the backend container; PostgreSQL, Redis, backend, and frontend all reported healthy afterward, and database/Redis data was left untouched.

**Evidence boundary**

This policy corrects the demonstrated shortlist-collapse mechanism. It does not prove that every three-of-five film is a good human recommendation; that remains a labeled session-quality evaluation question. A future slate-level coverage reranker could ensure the list collectively covers all selected moods instead of evaluating only per-film coverage.

### Fixed weak multi-mood padding and preserved Pick for Me navigation state

**Symptom**

With `Tense + Dark + Emotional`, `1990s`, and any runtime selected, a five-result request could include films such as *Forrest Gump* that covered only the emotional portion of the request. Opening a result and using browser Back also remounted Pick for Me, issued a new recommendation request, and replaced the shortlist the user was inspecting.

**Investigation and evidence**

1. Traced the backend order: era gate, fill-aware mood gate, runtime gate, score sort, then reranking.
2. Found that `applyMoodIntentGate` relaxed a three-mood request from 3/3 to 2/3 and finally 1/3 whenever necessary to fill the requested count. Quality and popularity could then make a one-mood match look convincing.
3. Traced the frontend mount effect and found that it always called the API. The refresh token correctly rotates results only when `Try another mix` is clicked, but a route remount had no stored shortlist to restore.
4. The first strict implementation applied the same threshold to single-mood cold start and failed three regression tests. Narrowed the policy to preserve single-mood fallback while enforcing multi-mood quality.

**Root cause**

The backend treated requested list length as more important than multi-mood coverage, and the frontend stored recommendation state only in React component memory. Neither issue was caused by an empty watchlist: authenticated personalization can still use positive review/rating history, while a user with neither source follows the existing cold-start behavior.

**Change**

- Three selected moods must now match all three. Two moods require at least one, four or more require at least 75%, and one mood retains the existing graceful catalog fallback.
- When the minimum multi-mood threshold cannot fill the requested count, AtlasWatch returns fewer strong picks instead of padding with weak matches.
- Pick for Me stores the successful filters, rotation token, and result list in tab-scoped `sessionStorage`, keyed by authentication mode.
- Returning from a movie detail restores that exact state without an API request. Changing filters still recomputes automatically, and `Try another mix` remains the explicit rotation action.
- Versioned backend recommendation caches as `v13-strict-mood-intent`.

**Verification**

- Added a regression proving a 3/3 match is retained while 2/3 and 1/3 fillers are rejected for a three-mood request.
- Focused backend suite: 42 tests passed after the initial regression-guided policy correction.
- Full backend suite: 149 tests run, 0 failures, 0 errors, and 1 intentionally skipped application-context test.
- Frontend production build passed lint, TypeScript validation, compilation, and all 13 routes.
- Updated backend and frontend images were rebuilt and only those two containers were recreated; database and Redis containers/volumes were left untouched. PostgreSQL, Redis, backend, and frontend all reported healthy afterward.
- The in-app browser workflow blocked localhost reload under its URL safety policy, so no post-deploy click-through is claimed. Navigation behavior is supported by the compiled state-restore path; a manual Back-button replay remains appropriate.

**Evidence boundary**

The stricter rule addresses semantic coverage and navigation stability. Human judgment is still needed to decide whether a movie that genuinely covers all selected moods is desirable; the session-intent labeling benchmark remains the correct evaluation layer for that question.

### Added an offline-evaluated collaborative signal to the Java recommender

**Symptom**

AtlasWatch personalization was limited to the current user's genres, keywords, watchlist, and TF-IDF content similarity. It could not use behavioral relationships such as “people who liked these movies also liked this one,” and there was no historical benchmark showing that a collaborative model beat a non-personalized popularity list.

**Investigation and evidence**

1. Selected MovieLens Latest Small because its 100,836 timestamped ratings support chronological evaluation and `links.csv` maps MovieLens movies into AtlasWatch's TMDB identifier space.
2. Built a per-user temporal split for 603 eligible users: the second-to-last rating of 4/5 or higher is validation, the last is test, and no target or later interaction enters its training data.
3. Compared popularity, implicit-positive item-item cosine KNN, and validation-tuned blends at 5 and 10 results.
4. The first KNN implementation averaged similarity support per candidate and performed poorly: test HR@10 was `0.0116`, below popularity's `0.0381`. Inspection showed that averaging erased the benefit of several liked seeds independently supporting the same movie.
5. Changed collaborative aggregation to sum corroborating similarity evidence and added a regression test for multi-seed support. A compact 30-neighbor model outperformed the larger 100-neighbor variant while reducing the compressed serving artifact to about 785 KB.
6. Rebuilt the experiment in an isolated compatible Python environment and reproduced the final metrics and deterministic artifact hash.
7. During production review, found that `Map.copyOf` does not guarantee the score iteration order used by candidate retrieval. Preserved an explicitly unmodifiable `LinkedHashMap` and added an order assertion so JVM iteration cannot scramble model rank.
8. Audited the evaluation narrative and found that the 30- versus 100-neighbor comparison happened after viewing Latest Small test metrics. Reclassified those numbers as development evidence, froze the completed configuration, and ran it once on the separate official MovieLens 100K dataset without tuning.
9. Screened truncated-SVD challengers only on development data. The 64-factor version improved development nDCG but initially showed a coverage trade-off, so it was not promoted from that evidence alone.
10. Froze 64 factors and ran a one-shot comparison on the previously unused MovieLens 1M dataset. It beat item-KNN on HR@5/10, MRR@10, nDCG@10, and coverage, clearing the promotion gate without post-audit tuning.

**Root cause**

The production engine had no population-level interaction model, and the first experimental aggregator treated one weak neighbor and several corroborating neighbors too similarly. Language was not the limitation: Python was useful for sparse offline training/evaluation, while the learned artifact could be served directly by the existing Java process.

**Change**

- Added a reproducible Python experiment with unit-tested temporal splitting, popularity and collaborative baselines, validation-only hyperparameter selection, rank-sensitive metrics, TMDB mapping, and deterministic compressed export.
- Trained a 30-neighbor implicit-positive item-item model from ratings `>= 4/5`; 6,293 of 6,298 learned items map to TMDB IDs (`99.92%`).
- Added a fail-open Spring component that loads the versioned gzip artifact once at startup and aggregates up to ten recent positive review/watchlist seeds.
- Added collaborative retrieval as a seventh candidate channel and a deliberately bounded `0.06` ranking weight. Explicit mood, runtime, and era gates remain authoritative.
- Limited the signal to authenticated users with positive history. Anonymous and no-history requests preserve the existing cold-start path.
- Versioned recommendation caches as `v11-hybrid-collaborative` and added a truthful collaborative explanation only for strong model scores.
- Replaced the neighbor artifact with schema-2, 64-dimensional item factors and versioned caches again as `v12-latent-collaborative`. Java weights review seeds consistently with offline ratings, projects their profile against all mapped movies, and remains fail-open if the artifact is unavailable.

**Verification**

- Python unit suite: 7 tests passed after adding independent-audit parsing and zero-baseline coverage.
- Latest Small development results across 603 users remain recorded but are not described as untouched because they informed the final neighbor count.
- Independent frozen audit across 938 eligible MovieLens 100K users: HR@5 `0.0469`, HR@10 `0.0789`, MRR@10 `0.0275`, nDCG@10 `0.0394`, and coverage@10 `0.1531`.
- Relative to popularity on that independent audit, this is +41.9% HR@5, +34.5% HR@10, +37.4% MRR@10, +36.3% nDCG@10, and 3.42x catalog coverage.
- Frozen MovieLens 1M promotion audit across 6,034 eligible users: latent HR@10 `0.0863` versus item-KNN `0.0545` (+58.4%), nDCG@10 `0.0429` versus `0.0276` (+55.5%), MRR@10 +52.8%, HR@5 +63.2%, and coverage +27.4%.
- Focused latent-serving and recommendation suite: 45 tests passed with no failures or errors.
- Focused Java suite: 60 tests passed with no failures or errors, including artifact loading, corrupt-artifact fallback, candidate retrieval, scoring/service regressions, and the unchanged synthetic evaluation baseline.
- Full backend suite after latent promotion: 148 tests run, 0 failures, 0 errors, and 1 intentionally skipped application-context test.
- Frontend production build passed lint, TypeScript validation, compilation, and generation of all 13 routes.
- Docker rebuilt and explicitly recreated only the backend without replacing database volumes; PostgreSQL, Redis, backend, and frontend are healthy. The startup log confirms `Loaded 64-factor collaborative recommendation model with 6293 TMDB items` before Spring reported ready.
- After separating Docker dependency and source layers, a no-change cached backend image verification completed in 4.3 seconds.
- The in-app browser tab had already fallen onto Chromium's connection-error document and local navigation was blocked by the browser safety policy, so no post-deploy browser interaction is claimed. Earlier exact era/mood browser replays remain recorded separately above.
- Experiment and production artifacts are byte-identical with SHA-256 `90f0b13cc48440a1d7e49d86f800bbeea1822ccdbf6cfdd1878da85dd74bace3`.
- The promoted schema-2 latent artifact is deterministic, approximately 1.23 MB compressed, and has SHA-256 `35bb5c2f13dd9f407da98512b42be5648f4fd95e83ad21775be9cd57801ea351`.

**Evidence boundary**

MovieLens proves that this collaborative formulation beats popularity on that external historical dataset. It does not prove an online lift for AtlasWatch users or mood-intent quality. The signal is therefore bounded and still subject to the synthetic, human session-intent, and future product-interaction evaluation layers.

### Added explicit, strict release-era controls to Pick for Me

**Symptom**

AtlasWatch had no way to distinguish “recommend movies from these decades” from “release year does not matter.” Earlier ranking also contained a hidden freshness signal, so an otherwise unconstrained request could skew toward recent and unreleased titles. Adding an era as another soft score would have allowed quality or popularity to override the user's explicit period choice.

**Investigation and evidence**

1. Traced release date from the persisted `Movie` through request scoring, cache keys, response mapping, and the Pick for Me result cards.
2. Confirmed that the existing freshness score is neutral by default and that release year was available but not represented in the request contract.
3. Compared a strict eligibility filter with a soft era weight. A soft weight could still leak an out-of-era movie into the shortlist, making the control misleading.
4. Found a pipeline-order edge case during review: filtering era after the fill-aware mood gate could discard the globally selected tier and return too few results even when suitable in-era candidates existed.
5. Replayed anonymous recommendations against the deployed PostgreSQL catalog with both broad and six-mood requests.

**Root cause**

Release year was only passive metadata. The API had no explicit era intent, so the ranking pipeline could neither enforce nor explain a user-selected time period.

**Change**

- Added an optional, validated multi-value `releaseEras` request field covering Before 1980 and each decade from the 1980s through the 2020s.
- Added an Any era default that preserves the existing no-era-preference behavior.
- Applied an explicit era selection as a strict eligibility boundary before fill-aware mood and runtime gates, so fallback decisions happen inside the selected catalog slice.
- Included normalized era values and algorithm version `v10-era-control` in recommendation cache keys.
- Added concise era explanations to every selected-period result.
- Added a multi-select release-era section, selection summary, strict-filter copy, and accessible pressed state to Pick for Me.

**Verification**

- Focused backend suite: 38 tests passed, 0 failures, and 0 errors.
- Full backend suite: 144 tests run, 0 failures, 0 errors, and 1 intentionally skipped application-context test.
- Frontend production build passed lint, TypeScript validation, compilation, and static generation.
- Added service regressions for strict filtering and Any era behavior, plus HTTP binding and invalid-value validation tests.
- Live 1990s + 2000s replay returned *Pulp Fiction* (1994), *Spirited Away* (2001), *The Matrix* (1999), *Gladiator* (2000), and *Life Is Beautiful* (1997), with an era-specific reason on every result.
- Live six-mood Medium replay constrained to those eras returned *Spider-Man* (2002), *Fight Club* (1999), *The Matrix* (1999), *Life Is Beautiful* (1997), and *The Incredible Hulk* (2008), with no out-of-era result.

### Required semantic evidence for ambiguous session moods

**Symptom**

Pick for Me treated broad genres as proof of nuanced moods. A Drama + Mystery movie could be described as Dark, Emotional, Thoughtful, Mind-bending, and Eerie even when its keywords and story did not support most of those claims. The explanation also repeated every selected mood whenever a movie matched only part of the request.

**Investigation and evidence**

1. Traced mood scoring from `SoloMood` through `RecommendationScorer`, the coverage gate, and `RecommendationReasonBuilder`.
2. Confirmed that stored TMDB keywords were already loaded for scoring but mood matching ignored them and the overview.
3. Measured local coverage: 803 of 929 movies have structured keywords, covering 5,061 keyword records.
4. Inspected the metadata for the reported weak recommendations. For example, *Witness for the Prosecution* had concrete murder/courtroom evidence but no support for mind-bending or eerie, while the old Drama + Mystery mapping credited three complex moods.
5. Replayed the exact six-mood Medium request after deployment and inspected the ordered titles and generated reasons.

**Root cause**

The model used genres both to retrieve candidates and to certify nuanced intent. Genres are useful broad candidate hints, but they are too coarse to prove emotional tone or narrative structure.

**Change**

- Added normalized, phrase-boundary semantic cue sets for each mood using existing TMDB keywords and overview text.
- Kept genre-only coverage for moods with a specific genre proxy (Funny, Tense, Adventurous, and Romantic).
- Required direct semantic evidence for ambiguous moods including Dark, Emotional, Thoughtful, Cozy, Hopeful, Bittersweet, Mind-bending, Eerie, Comforting, and Inspiring.
- Preserved the existing strongest-coverage fallback: sparse catalogs can still return a full list, but broad genres no longer receive false coverage credit.
- Changed reasons to name only matched moods and, when available, cite up to three concrete story signals.
- Versioned recommendation caches as `v9-semantic-moods`.

**Verification**

- Focused recommendation suite: 55 tests passed, 0 failures, 0 errors.
- Full backend suite: 142 tests run, 0 failures, 0 errors, and 1 intentionally skipped application-context test.
- Added regressions for complete/partial semantic coverage, specific genre-backed moods, rejection of Drama + Mystery as complex-mood proof, and phrase-boundary matching (`glossy` must not match the cue `loss`).
- The unchanged synthetic any-mood baseline retained all recorded relevance, coverage, and diversity metrics.
- Browser replay of Tense, Dark, Emotional, Thoughtful, Mind-bending, Eerie, Medium returned *The End of Oak Street*, *Obsession*, *Your Name.*, *Spider-Man*, and *Fight Club*. Explanations cited only supported moods and evidence such as survival, family relationships, teleportation, grim, supernatural, time travel, loss, identity, and dystopia.

### Made movie quality confidence-aware with TMDB vote counts

**Symptom**

AtlasWatch ranked movies by TMDB's raw average rating. A new or obscure title with a 9.5 average from only a handful of votes could therefore outrank an established 8.0 title supported by thousands of votes.

**Investigation and evidence**

1. Traced quality ranking through TMDB DTOs, `MovieService`, `Movie`, `MovieRepository`, `CandidateRetriever`, and `RecommendationScorer`.
2. Confirmed that TMDB already returns `vote_count`, but AtlasWatch discarded it at the DTO boundary and stored only `vote_average`.
3. Confirmed that both the top-rated retrieval channel and final weighted score used the unsupported raw average.
4. Added deterministic tests showing that a 9.5/5-vote movie previously looked stronger than an 8.0/10,000-vote movie.
5. Ran a live Pick for Me request after the first implementation. Startup validation passed, but execution exposed Hibernate inferring `priorMean` as an integer inside the JPQL arithmetic expression. The 6.5 prior then failed parameter coercion. Explicit `Double` casts at the query boundary fixed the mismatch.

**Root cause**

The catalog schema and ingestion path omitted rating sample size, so the recommender had no way to distinguish strong evidence from rating noise.

**Change**

- Added nullable `vote_count` storage through a forward-only V4 Flyway migration, with a non-negative constraint and retrieval index.
- Ingested `vote_count` from TMDB summary and detail responses and exposed it through movie and recommendation DTOs.
- Replaced raw quality with a configurable Bayesian estimate: `(votes * rating + priorWeight * priorMean) / (votes + priorWeight)`.
- Set conservative defaults of prior mean `6.5` and prior weight `250`; missing vote counts therefore behave as unknown evidence, not as zero-quality movies.
- Applied the same estimate to top-rated candidate retrieval, weighted scoring, sampling, and watchlist tie-breaking.
- Versioned recommendation cache keys as `v8-vote-confidence` so pre-change rankings cannot be replayed.
- Explicitly cast vote counts and prior parameters to `Double` in JPQL so Hibernate and PostgreSQL use the same numeric domain as the Java scorer.

**Verification**

- Focused backend suite: 28 tests passed, 0 failures, 0 errors (`RecommendationScorerTest`, `MovieServiceTest`, `CandidateRetrieverTest`, and `RecommendationEvaluationTest`).
- Full backend suite: 140 tests run, 0 failures, 0 errors, and 1 intentionally skipped application-context test.
- Frontend production build passed ESLint, TypeScript validation, and Next.js static generation.
- Docker runtime verification applied Flyway V4 to the existing 927-movie database without deleting data; Hibernate validated the entity and Spring parsed the updated JPQL before the backend became healthy.
- A browser smoke test executed the real PostgreSQL retrieval path and returned a complete five-pick shortlist after the numeric-cast correction.
- The unchanged synthetic evaluation retained Hit Rate `1.0000`, Precision@5 `0.3500`, Recall@5 `0.6667`, MRR `0.4583`, nDCG@5 `0.4545`, coverage `0.8667`, diversity `0.5250`, and intra-list similarity `0.2750`.
- Immediately after migration, `0/927` legacy rows had vote counts. The first scheduled TMDB summary refresh raised coverage to `807/929`; unreached rows use the prior-only fallback until a later summary or detail refresh.

### Established rank-sensitive recommendation evaluation

**Symptom**

The existing evaluation reported 100% hit rate even though visual testing showed weak movies above better matches. Hit rate only asks whether one relevant item appears anywhere in the list, so it could not measure the ordering problem.

**Investigation and evidence**

1. Traced `RecommendationEvaluationTest` through the real recommendation pipeline and inspected `RecommendationEvaluator`.
2. Confirmed that the evaluator measured Hit Rate and Precision@K but not how much relevant material was recovered or where it appeared.
3. Added rank-sensitive metrics and reran the unchanged synthetic scenario.
4. The result retained 100% Hit Rate while revealing MRR `0.4583` and nDCG@5 `0.4545`, confirming that useful results are often ranked too low.

**Root cause**

The original metric set treated every position equally and used only binary labels. It also had no representation for explicit constraint violations, so it could not evaluate nuanced Pick for Me quality.

**Change**

- Added Recall@K, MRR, graded nDCG@K, and separate mood/runtime/era violation rates.
- Preserved binary-label constructors for the existing deterministic harness while allowing 0-3 graded relevance.
- Added exact unit tests for graded ordering and constraint-rate calculations.
- Recorded regression floors and the expanded baseline in `docs/evaluation-baseline.md`.
- Added a versioned 20-prompt session-intent set, empty label schema, and human-labeling rubric under `docs/evaluation/`.

**Verification**

- `mvn "-Dtest=RecommendationEvaluationTest" test` — 10 tests passed, 0 failures, 0 errors.
- Baseline: Hit Rate `1.0000`, Precision@5 `0.3500`, Recall@5 `0.6667`, MRR `0.4583`, nDCG@5 `0.4545`, catalog coverage `0.8667`, genre diversity `0.5250`, and intra-list similarity `0.2750`.
- A sandboxed Maven run initially failed because `javac` could not traverse compiled class directories; direct compiler diagnostics reproduced the filesystem restriction. The approved out-of-sandbox Maven run compiled and passed, distinguishing environment failure from source failure.

### Made explicit mood and runtime choices authoritative in Pick for Me

**Symptom**

Selecting Dark, Emotional, Thoughtful, Mind-bending, Eerie, Short, and five picks returned unrelated or weakly related titles such as *Descendants: Wicked Wonderland*, *Shelter*, and *The Super Mario Bros. Movie*. Later smoke tests also exposed nominally “short” results longer than 105 minutes and a shortlist dominated by 2026 releases.

**Investigation and evidence**

1. Reproduced the exact filter combination in the running Next.js application.
2. Queried non-sensitive local catalog metadata for the reported titles and stronger alternatives.
3. Traced the request through `CandidateRetriever`, `RecommendationScorer`, and `RecommendationService`.
4. Found that multi-mood scoring divided matched genres by one large union of genre names. It did not measure how many requested moods a movie covered.
5. Found that mood relevance was only a weighted bonus, so profile, quality, popularity, and freshness could outrank explicit session intent.
6. Found that broad mappings made Drama satisfy Dark and Thoughtful and made Thriller satisfy Mind-bending and Eerie. `Drama + Mystery` therefore appeared to cover all five selected moods.
7. Found an unsolicited freshness weight of `0.08`, which systematically favored recent releases even though the user had not requested new movies.
8. Found that the runtime hard filter intentionally tolerated near matches up to 125 minutes for Short, even when at least five exact Short candidates (105 minutes or less) existed.

**Root cause**

The ranking model treated explicit session choices as soft, overlapping genre hints. Broad genre aliases, a hidden freshness preference, and tolerance-only runtime matches could collectively dominate what the user actually selected.

**Fix**

- Compute mood match as the fraction of requested moods covered by a movie, rather than overlap with one union of genres.
- Keep the strongest per-mood coverage tier that can still fill the requested result count, excluding zero- and weak-match candidates when stronger choices are available.
- Give per-mood coverage priority during initial ranking, diversity reranking, and personalized calibration.
- Narrow ambiguous mappings: Drama no longer automatically means Dark or Thoughtful; Thriller no longer automatically means Mind-bending or Eerie.
- Make freshness opt-in in both candidate sampling and final scoring, and suppress freshness explanations when it contributes no score.
- Prefer exact runtime matches whenever enough exist; retain the tolerance range only as a result-count fallback.
- Version the recommendation cache key so old Redis results cannot survive an algorithm revision.

**Verification**

- Added regression tests for per-mood coverage, zero-match gating, intent-priority ordering, ambiguous mood mappings, freshness defaults, and exact-runtime gating.
- Focused suite: 40 tests passed with zero failures (`RecommendationScorerTest` and `RecommendationServiceTest`).
- Full backend suite: 135 tests run, 0 failures, 0 errors, 1 intentionally skipped application-context test.
- Replayed the exact browser filters after deploying each revision; this exposed and eliminated the unrelated-title, broad-mapping, hidden-freshness, and runtime-tolerance failure modes.

**Remaining model limitation**

The catalog currently stores TMDB rating averages but not vote counts, and mood semantics still rely primarily on genres. A later quality phase should ingest vote counts for Bayesian confidence and evaluate keyword/overview embeddings against a labeled recommendation set. Python may be useful for that offline experimentation, but it is not required by the production Java service.

### Recorded slow clean Docker backend builds

A clean backend image build took several hours because Maven dependencies were downloaded inside a source-sensitive Docker layer. For local verification, the tested host-built JAR was copied into the existing container and the backend restarted.

**Resolved 2026-08-16:** A collaborative-model deployment reproduced the defect: the frontend image finished, but the clean backend dependency download stopped without producing an image and the prior healthy container remained active. The Dockerfile now resolves dependencies in a pom-only layer and mounts a persistent BuildKit cache at Maven's repository for both dependency resolution and compilation. Source edits no longer invalidate dependency resolution, and interrupted downloads can be reused by the next build.

## 2026-08-15

### Fixed authenticated rating, review, and watchlist mutations after login

**Symptom**

Selecting a movie rating displayed: `Your session is not authenticated for rating changes.` A similar failure could affect reviews and watchlist updates.

**Investigation and evidence**

1. Located the message in `moviehub-frontend/app/(app)/movie/[tmdbId]/page.tsx` and established that either HTTP `401` or `403` produced the same copy.
2. Checked PostgreSQL without reading token values. The account was enabled and had fresh, non-revoked refresh-token records, ruling out an unverified account and ordinary refresh-token expiry.
3. Traced mutations through `moviehub-frontend/lib/api.ts`. The client cached a CSRF token, but only the refresh-token request retried a `403` with a new CSRF token.
4. Traced login through Spring Security. Successful authentication can rotate the CSRF token, leaving the frontend's in-memory pre-login token stale for the next mutation.

**Root cause**

The API client reused a stale CSRF token after login. Spring rejected the mutation with `403`, and the movie page grouped `403` together with unauthenticated `401` responses.

**Fix**

- Updated the shared API request function to retry a rejected mutation exactly once after obtaining a fresh CSRF token.
- Kept the retry bounded: a genuine authorization failure remains a `403` after the second attempt.

**Verification**

- `npm run build` — passed, including ESLint, TypeScript validation, and the Next.js production build.
- Rebuilt and restarted the frontend container.
- `docker compose ps` — backend, PostgreSQL, and Redis healthy; updated frontend running and entering its normal startup health check.

### Fixed misleading “account not verified” diagnosis during login

**Symptom**

After a successful password reset, login displayed: `Your account is not verified yet.`

**Investigation and evidence**

1. Traced `AuthService.resetPassword` and confirmed password reset intentionally changes only password-reset fields; it does not enable or disable an account.
2. Queried only non-secret account state. The affected account had `enable = true` and no active verification code.
3. Inspected the login page and found that every HTTP `403` was translated into the unverified-account message.

**Root cause**

The frontend treated status code `403` as proof of an unverified account, although CSRF rejection also uses `403`.

**Resolution**

The shared CSRF retry described above prevents the common stale-token failure. Error responses must continue moving toward server-error-code-based mapping instead of relying on status alone.

### Repaired startup against a pre-Flyway local database

**Symptom**

Docker built both images, but `atlaswatch_backend` became unhealthy. Hibernate reported `Schema-validation: missing table [recommendation_impression]`.

**Investigation and evidence**

1. Docker logs showed PostgreSQL and Redis were healthy and the backend connected successfully.
2. Flyway reported schema version 2 as current while Hibernate reported a missing table defined in `V1__initial_schema.sql`.
3. Read `flyway_schema_history`: version 1 was a Flyway baseline rather than an executed SQL migration, and version 2 had run normally.
4. Inspected the live schema and found the legacy lowercase `users` table. This also exposed incompatible quoted identifiers in the first draft of the fresh-schema migration.

**Root cause**

`baseline-on-migrate` correctly adopted a pre-existing schema as version 1, so it did not execute the new V1 SQL against that database. The later recommendation-impression entity therefore had no corresponding legacy table.

**Fix**

- Added `V3__add_recommendation_impressions.sql` as a forward-only, idempotent compatibility migration.
- Corrected V1 user identifiers to match Hibernate/PostgreSQL lowercase snake-case naming for fresh installations.
- Rebuilt and force-recreated only the backend container, preserving PostgreSQL data and Docker volumes.

**Verification**

- Flyway successfully migrated the existing schema from version 2 to version 3.
- Hibernate initialized its entity manager without schema-validation errors.
- The backend health check passed and the frontend subsequently started.

### Stabilized local Docker startup

**Symptom**

The initial Docker build was slow and once ended with a BuildKit `rpc error ... EOF`; later builds succeeded but an older backend container remained attached to a previous image.

**Fix**

- Added Docker ignore files to reduce build context.
- Added dependency health checks and startup ordering for PostgreSQL, Redis, backend, and frontend.
- Used targeted image rebuild and container recreation while preserving database volumes.

**Operational note**

Use `docker compose up -d --build` to build and start the application. Use `docker compose down` to stop it while retaining data. Do not use `docker compose down -v` unless deleting local PostgreSQL and Redis data is intentional.
