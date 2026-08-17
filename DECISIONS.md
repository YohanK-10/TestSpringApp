# AtlasWatch engineering decisions

This file records decisions that affect architecture, reliability, security, or developer workflow. Entries describe context and trade-offs, not merely the final code.

## D-016: Evaluate against a frozen dataset in a disposable database

**Status:** Accepted — 2026-08-16

**Context:** Phase 1 and Phase 2 were measured against the live `Travel` database while the Docker backend kept ingesting into it. The test property that disables ingestion applies only to the evaluator's own Spring context, so the input moved between and during runs, and four prompts silently changed from returning results to returning none. The recorded checksum hashed `cached_at`, so it flipped on any refresh whether or not ranking-relevant content changed, and nothing ever compared it to an expected value.

**Decision:** Evaluation reads a versioned dataset (`session-intent-catalog-v1`, 793 recommendation-ready movies) containing only ranking-relevant fields, genres, and keywords, and seeds it through Flyway into a disposable Testcontainers PostgreSQL instance. The content fingerprint excludes volatile timestamps and is asserted both before and after the request loop. TMDB beans are mocked, so the context cannot reach the network, and the test asserts zero interactions. Every artifact records `datasetVersion`, `catalogSize`, and `contentFingerprint`.

**Alternatives considered:**

- Freeze a manifest but keep the shared database. Another process can still write to it.
- Seed a container from the live database at run time. That reintroduces the nondeterminism the freeze is meant to remove.
- Only assert the fingerprint at the end of the run. That cannot distinguish a stable catalog from one that moved and returned, and the previous 891-row figure was exactly such an end-of-run measurement.

**Trade-off:** The dataset is a checked-in artifact that ages against the real catalog, and refreshing it invalidates comparability with earlier reports. That is the intended cost: a baseline is only meaningful against a fixed input, and a new dataset version should force a new baseline rather than silently shift one.

## D-017: Gate promotion on constraint CIs, not point estimates

**Status:** Accepted — 2026-08-16

**Context:** The first Phase 2 comparison gated only on fill and rule-derived mood coverage. Runtime violations rose by `0.0232` and were reported as diagnostic. The scope was chosen before the run rather than after seeing the result, but a user-visible constraint sitting outside the gate meant rotation could buy freshness with worse-fitting films and still pass.

**Decision:** Runtime and era violation rates join the paired cluster-bootstrap metrics and become promotion gates. The gate is the upper bound of the paired CI rather than the point estimate, so a delta whose interval spans zero counts as noise instead of a regression. Rotation additionally holds the pre-rotation slate's runtime-compliance count as a floor, reusing the substitution rule that already protected mood coverage.

**Alternatives considered:**

- Keep runtime diagnostic and document the trade-off. Honest, but it leaves a visible constraint unprotected against the next change.
- Gate on the point estimate. That converts ordinary sampling noise into spurious failures across 80 clusters.
- Hard-filter non-compliant runtimes during rotation. That would starve the unseen pool for exactly the strict requests that already fill worst.

**Trade-off:** A floor on compliance count can retain a previously shown film to protect the constraint, so rotation is slightly weaker than an unconstrained version. On the frozen catalog this cost nothing measurable: overlap, unique-result rate, and top-1 repetition all improved with intervals excluding zero while runtime violations fell.

## D-015: Treat session novelty as a guarded presentation objective

**Status:** Accepted — 2026-08-16

**Context:** A different rotation seed can still surface the same strongest films, so `Try another mix` needs explicit knowledge of what the current session has already displayed. Blind exclusion can shrink a shortlist or replace strong intent matches with weaker novel items. Repeated authenticated displays should also not create duplicate impression events that look like independent opportunities.

**Decision:** Carry at most 50 displayed TMDB IDs in tab-scoped client state and the recommendation request. For authenticated users, union them with distinct impressions from a six-hour server window. Prefer qualified unseen candidates only after the ordinary intent gates and ranking; when unseen supply is insufficient, fall back to the baseline rather than reducing fill. Enforce the original slate's aggregate rule-derived mood coverage as a floor and restore the minimum number of strong baseline items needed to meet it. Record at most one impression per user/movie within the same window. Promote the behavior only through a same-snapshot paired cluster-bootstrap comparison.

**Alternatives considered:**

- Rely on refresh-token randomness. It changes sampling but does not make non-repetition an invariant.
- Hard-exclude every prior item in candidate retrieval. This can starve strict mood/era requests and turn novelty into a hidden constraint stronger than relevance.
- Keep all session state only in the browser. That works anonymously but loses protection across tabs/devices and cannot correct repeated authenticated impression writes.
- Use the intermediate weighted-sampling probability as a propensity. Later merging, gates, fallback, diversity, calibration, and mood-floor restoration change display probability, so that value would be invalid for IPS.

**Trade-off:** The six-hour window is a product heuristic, not a durable user preference, and a 50-ID cap bounds rather than eliminates long-session repeats. Mood coverage is preserved only under the existing rule model, not human judgment. Deterministic rotation still has no exact non-zero display propensity; a future randomized policy must log rank and final-policy probability separately.

## D-014: Establish a non-mutating, cluster-bootstrapped session baseline

**Status:** Accepted — 2026-08-16

**Context:** Manual `Try another mix` checks do not quantify recommendation behavior, are hard to reproduce, and can silently depend on one user's sparse history. Aggregate-only metrics can hide severe mood-specific failures, while resampling individual ranked items would understate uncertainty because results from one prompt/persona session are correlated.

**Decision:** Freeze 20 prompts and four cold/synthetic-warm personas, run ten deterministic rotations for each pair, and exercise the production retrieval/scoring/reranking path through a read-only evaluator seam. Bypass seeding, production users, caches, and impressions. Report structural metrics with primary-mood, evidence-source, and cold/warm slices; give headline metrics 95% intervals by resampling whole prompt/persona clusters. Retain a paired cluster-bootstrap API for challengers. Treat rule-derived mood coverage only as a circular guardrail. Do not record a propensity until a deliberately randomized serving policy can compute the exact probability after all selection stages.

**Alternatives considered:**

- Continue manual browser testing. It remains useful for UX checks but cannot produce repeatable coverage or uncertainty estimates.
- Clone production accounts and histories. This mutates state, risks privacy leakage, and makes a baseline drift as accounts change.
- Bootstrap returned items or ranks independently. Those observations share a request and rotation process, so independent resampling would create false precision.
- Infer propensity from an intermediate weighted sampler. Merge, filtering, and reranking change display probability; logging that intermediate value would poison future IPS estimates.

**Trade-off:** Synthetic warm personas measure controlled taste sensitivity rather than real-user behavior, and the run is slower than unit tests. The opt-in live test and fast arithmetic tests keep CI practical. Human labels and eventual online experiments remain necessary for relevance claims.

## D-013: Track semantic-fetch completion separately from keyword presence

**Status:** Accepted — 2026-08-16

**Context:** Ambiguous mood matching depends on TMDB detail metadata. A movie with no keyword rows might be incomplete, or TMDB might legitimately return no keywords. Using keyword count as backfill state would retry valid empty responses forever and make baseline coverage unknowable.

**Decision:** Persist a nullable `semantic_metadata_synced_at` completion marker after every successful detail response, including empty keyword lists. Backfill only recommendation-ready rows lacking the marker through a disabled-by-default, resumable, rate-limited startup task. Keep failures incomplete so a later run can retry them.

**Alternatives considered:**

- Treat at least one keyword as completion. This conflates empty valid responses with failures.
- Re-fetch every catalog row before each evaluation. It wastes API quota, increases baseline drift, and makes evaluation depend on network availability.
- Mark attempted rows complete even after an error. That would hide missing data and prevent recovery.

**Trade-off:** The timestamp records successful synchronization, not semantic richness. TMDB can later change its metadata; a separate freshness policy may re-fetch old completed rows without weakening this completion contract.

## D-012: Prefer multi-mood relevance over filling the requested count

**Status:** Accepted — 2026-08-16

**Context:** The prior fill-aware gate could relax a three-mood request to one covered mood to return exactly five films. This made list completeness look healthy while weakening the user's explicit session intent. Pick for Me also recomputed on every route remount, so browser Back did not mean “return to the shortlist.”

**Decision:** Preserve the graceful fallback for a single mood, require at least one of two moods, and require all three when exactly three are selected. Treat four-or-more selections as an exploratory blend rather than a conjunction and require at least 60% coverage (for example, three of five). Return a partial shortlist when the catalog cannot meet that floor. Persist the latest successful Pick for Me state in tab-scoped session storage and restore it on remount; only filter changes or the explicit rotation action request new results.

**Trade-off:** A broad blend does not promise that every film embodies every selected mood; it promises meaningful individual coverage without one- or two-signal filler. Exact three-mood requests remain stricter. Shortlists may still be partial, and tab-scoped state does not survive closing the tab. A future slate-level coverage reranker could additionally ensure that the list collectively represents every selected mood, while a URL-backed saved search could add durable/shareable state without changing the current navigation guarantee.

## D-011: Cache Maven dependencies independently from backend source

**Status:** Accepted — 2026-08-16

**Context:** The backend Dockerfile copied all source before running Maven. Any Java or resource edit invalidated the only build layer, forcing a full dependency download; interrupted downloads could not help the next attempt.

**Decision:** Run `dependency:go-offline` after copying only Maven metadata, then copy source and package in a later layer. Mount the same BuildKit cache at `/root/.m2/repository` for both steps.

**Trade-off:** The first clean build still downloads dependencies and the cache consumes local disk. Subsequent source builds reuse both the stable dependency layer and the cache, while dependency changes invalidate only the appropriate layer.

## D-010: Train collaborative relationships offline and serve them inside Java

**Status:** Accepted — 2026-08-16

**Context:** AtlasWatch's content and taste features cannot learn population-level co-preference. Python's sparse numerical ecosystem makes offline experimentation practical, but adding a Python request-time service would create another deployment, failure, latency, security, and observability boundary. MovieLens provides timestamped ratings and TMDB links, but it represents external users rather than AtlasWatch traffic.

**Decision:** Use Python only for reproducible temporal evaluation and artifact generation. First establish a 30-neighbor item-KNN model above popularity on a frozen MovieLens 100K audit. Then screen truncated-SVD factor sizes on development data and promote the frozen 64-factor challenger only after it beat item-KNN on every metric in a one-shot MovieLens 1M audit. Export deterministic TMDB-keyed item vectors and load them once inside Spring Boot; Java performs the equivalent weighted profile projection. Use it as an authenticated candidate channel and a bounded `0.06` ranking feature; never allow it to bypass explicit mood, runtime, era, metadata-readiness, disliked-item, diversity, or calibration behavior. If loading fails, log the boundary and continue without the optional signal.

**Alternatives considered:**

- A Python recommendation microservice. It adds operational complexity without requiring online inference or Python-only production logic.
- Neural retrieval immediately. It adds complexity without evidence that it beats the now-audited latent baseline; any future challenger must be selected on development data and beat it on a new reserved audit.
- Replace the Java scorer with the collaborative ranking. MovieLens validation selected pure collaborative ranking there, but AtlasWatch must also satisfy explicit session constraints and quality controls that MovieLens does not measure.
- Train from the small amount of current AtlasWatch activity. That data is not yet broad enough for reliable co-preference estimates and would overfit a handful of users.

**Trade-off:** MovieLens preferences are older and may not represent AtlasWatch users or newer films. Coverage is limited to mapped catalog titles and authenticated users with a mapped positive seed. The bounded hybrid integration captures measurable behavioral value without overstating transferability; a future AtlasWatch interaction model should replace or blend this artifact only after temporal and session-intent evaluation.

## D-009: Treat a selected release era as eligibility, not preference

**Status:** Accepted — 2026-08-16

**Context:** Users may be open to any release year or may specifically want films from one or more periods. These are different intents. AtlasWatch previously exposed no era control, and a hidden default preference for recent films had already demonstrated how an unrequested time signal can degrade relevance.

**Decision:** Default to Any era, which contributes no score and excludes nothing. When one or more explicit eras are selected, require every eligible movie to fall within their union. Apply this boundary before fill-aware mood and runtime gates so those gates choose the strongest available candidates inside the requested periods. Include eras in the versioned cache key and cite the matching selection in explanations.

**Alternatives considered:**

- Add an era score. This can be overridden by other weights and would make a visible filter untrustworthy.
- Default to recent releases. This repeats the hidden-freshness bug and incorrectly assumes that newer means more relevant.
- Allow only one decade. Multi-select better represents requests such as “1990s or 2000s” without requiring a broad custom range.
- Add arbitrary start/end years immediately. That is more flexible but adds UI and validation complexity before evidence that decade-level controls are insufficient.

**Trade-off:** Strict filtering can return fewer than the requested count when a selected period is sparse, particularly after runtime and metadata readiness checks. That result is honest: AtlasWatch does not silently violate an explicit filter. Candidate retrieval still uses a bounded pool, so later evaluation should measure shortlist fill rate by era and expand retrieval when needed.

## D-008: Use genres for recall and semantic evidence for nuanced mood precision

**Status:** Accepted — 2026-08-16

**Context:** Genres are stable, available, and useful for retrieving a broad candidate pool, but Drama does not necessarily mean Emotional or Thoughtful, Mystery does not necessarily mean Mind-bending or Eerie, and Thriller does not necessarily mean Dark. AtlasWatch already stores structured TMDB keywords and overviews for most of its local catalog.

**Decision:** Continue using mood-to-genre mappings as candidate-retrieval hints. For scoring coverage, allow genre-only proof only where a genre is a sufficiently specific proxy (Comedy for Funny, Thriller/Crime/Action for Tense, Adventure for Adventurous, and Romance for Romantic). Require a normalized phrase-boundary match in TMDB keywords or overview text for ambiguous moods. Keep the strongest-available coverage fallback so incomplete metadata reduces confidence instead of making the product unavailable. Explanations list only matched moods and cite the concrete cues used.

**Alternatives considered:**

- Expand genre mappings. This improves recall but repeats the original false-positive problem.
- Require semantic evidence for every mood. This would incorrectly ignore strong direct categories such as Comedy and Romance and would punish older rows with missing keywords.
- Call an LLM for every candidate. That adds latency, cost, nondeterminism, and an external online dependency before AtlasWatch has a labeled benchmark to establish improvement.
- Introduce embeddings immediately. Embeddings are a useful next experiment, but a transparent lexical layer fixes known false claims, produces auditable reasons, and creates a deterministic baseline that embeddings must beat.

**Trade-off:** A curated cue lexicon requires maintenance and cannot understand negation, metaphor, or every synonym. Phrase boundaries reduce substring false positives, and keyword evidence is generally safer than unstructured overview terms. The versioned human-label benchmark remains the authority for tuning or replacing these rules.

## D-007: Shrink low-confidence ratings toward a catalog prior

**Status:** Accepted — 2026-08-16

**Context:** TMDB's `vote_average` does not express confidence by itself. Treating a 9.5 average from five votes as stronger than an 8.0 average from ten thousand votes makes new and obscure movies disproportionately likely to enter the candidate pool and rise in final ranking.

**Decision:** Persist TMDB `vote_count` and use one configurable Bayesian estimate in retrieval and scoring: `(v * R + m * C) / (v + m)`, where `R` is the movie rating, `v` its vote count, `C` the prior mean, and `m` the prior weight. Defaults are `C=6.5` and `m=250`. Null vote counts are treated as zero observed votes, so legacy rows fall back to the prior until normal ingestion refreshes them. Preserve raw `movieRating` for display; this estimate is a ranking feature, not a rewrite of source data.

**Alternatives considered:**

- Require a hard minimum vote count. This removes noisy titles but creates a cliff, harms catalog coverage, and can permanently hide legitimate niche films.
- Sort by raw rating and add popularity separately. TMDB popularity blends several behaviors and does not directly quantify rating confidence; independent weights can also contradict each other.
- Use IMDb-style weighted ratings with fixed constants in SQL only. The formula is appropriate, but duplicating unconfigurable constants would make retrieval and final scoring drift.
- Train a quality model immediately. AtlasWatch does not yet have enough labeled outcome data, and sample-size uncertainty has a direct, interpretable solution.

**Trade-off:** The prior introduces a conservative bias toward movies with more evidence, and its defaults need future calibration against human-labeled or interaction data. A soft prior avoids the coverage loss of a hard threshold. Applying the same feature everywhere prevents retrieval from discarding candidates before the final scorer can evaluate them.

## D-006: Require layered, rank-sensitive evaluation before model promotion

**Status:** Accepted — 2026-08-16

**Context:** The original synthetic harness reported hit rate, Precision@K, coverage, and genre diversity. A 100% hit rate can conceal poor ordering: one relevant movie at rank five still counts as a complete hit. Synthetic genre clusters also cannot establish whether real movies satisfy nuanced session moods.

**Decision:** Keep the deterministic synthetic suite as a regression layer, add Recall@K, MRR, graded nDCG@K, and explicit mood/runtime/era violation rates, and create a separately versioned human-labeling benchmark. Later collaborative models must use temporal splits, and a model is promoted only after evidence from the relevant evaluation layers rather than a screenshot or one aggregate metric.

**Alternatives considered:**

- Optimize only hit rate or Precision@5. These are not sufficiently sensitive to ordering and can reward a list with one useful result buried beneath weak ones.
- Use production A/B tests immediately. AtlasWatch does not yet have enough traffic for reliable online significance.
- Let an LLM generate relevance labels. That risks measuring agreement with another model rather than human usefulness; generated labels may assist triage but are not benchmark ground truth.

**Trade-off:** Human labels and catalog snapshots take time to maintain. Versioning prompts and labels prevents silent benchmark drift, while the fast synthetic layer still catches routine regressions.

## D-005: Treat explicit session intent as a constraint before personalization

**Status:** Accepted — 2026-08-16

**Context:** Pick for Me asks users for moods and runtime in the current session. Those answers are stronger and more recent evidence than historical taste, popularity, or release freshness. The earlier weighted-only model could return good generic films that contradicted the active request.

**Decision:** Measure coverage per requested mood, retain the strongest coverage tier that can still fill the list, add mood coverage to the ranking priority used by diversity and calibration, and conditionally require exact runtime matches inside that relevance tier when the pool can fill the requested count. Keep relaxed filters only as fallbacks for small catalogs. Candidate sampling and final ranking are quality/popularity-first; freshness has no default weight unless a future product control explicitly requests recent releases.

**Alternatives considered:**

- Increase the mood weight only. This still allows unrelated films through and is fragile when other weights change.
- Hard-filter every request unconditionally. This can return fewer results for small or unusual catalogs.
- Replace the Java service with Python or an ML microservice. Language choice does not fix incorrect features, and a new service adds operational cost before there is labeled training/evaluation data.
- Use an LLM to judge every candidate online. This adds latency, cost, nondeterminism, and an external dependency to a sub-second path.

**Trade-off:** Genre-based mood coverage remains an approximation and can be conservative. Conditional gates preserve availability, while algorithm-versioned cache keys and regression tests make behavior changes explicit. A later evaluated embedding or learning-to-rank model can replace the heuristic without requiring the production API to stop being Java.

## D-004: Maintain living change, decision, and flow documentation

**Status:** Accepted — 2026-08-15

**Context:** AtlasWatch is a large learning project that may be revisited after long gaps. Code alone does not retain the investigation trail or explain why one solution was preferred.

**Decision:** Keep `CHANGELOG.md`, `DECISIONS.md`, and `FLOW.md` current. Repository-level `AGENTS.md` makes this part of future implementation work.

**Trade-off:** Documentation adds a small maintenance cost and can become stale. Requiring updates in the same change keeps that risk visible and makes the project easier to explain in interviews.

## D-003: Retry a rejected mutation once with a fresh CSRF token

**Status:** Accepted — 2026-08-15

**Context:** Spring Security may rotate CSRF state after authentication. A browser-side API module can retain the earlier token, making the first post-login mutation fail with `403`.

**Decision:** When a mutation receives `403`, clear the cached CSRF state, fetch a fresh token, and retry the request once.

**Alternatives considered:**

- Refresh the entire page after login. This couples correctness to navigation and still fails in multi-tab or long-lived-client cases.
- Disable CSRF for authentication endpoints or the whole API. Cookie-based authentication makes that an unacceptable security regression.
- Retry indefinitely. This could hide real authorization failures and create request loops.

**Trade-off:** A legitimate forbidden mutation makes one extra request. The retry is bounded and does not turn a denied request into an allowed one.

## D-002: Repair adopted databases with forward-only migrations

**Status:** Accepted — 2026-08-15

**Context:** A local database created before Flyway was introduced was baselined at version 1. Re-running V1 or deleting the volume would either violate Flyway history or destroy useful user data.

**Decision:** Add a new idempotent V3 migration that creates the missing recommendation-impression sequence, table, foreign keys, and indexes. Keep V1 correct for clean installations.

**Alternatives considered:**

- Run `docker compose down -v` and recreate the database. Fast, but destructive.
- Manually execute SQL only on one developer machine. Not repeatable and leaves future adopted databases broken.
- Edit Flyway history to make V1 run. Risky because V1 also creates tables already present.

**Trade-off:** The compatibility migration overlaps slightly with V1, but `IF NOT EXISTS` makes it safe for both adopted and fresh schemas.

## D-001: Validate production schema instead of allowing Hibernate to mutate it

**Status:** Accepted — 2026-08-15

**Decision:** Flyway owns schema changes and Hibernate uses `ddl-auto=validate`.

**Reasoning:** Versioned SQL is reviewable, repeatable, CI-friendly, and can explicitly manage PostgreSQL features such as generated search vectors and GIN indexes. Validation makes schema drift fail visibly during startup.
