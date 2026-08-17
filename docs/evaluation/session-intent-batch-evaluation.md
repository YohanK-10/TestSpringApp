# Session-intent batch evaluation

## Purpose

This is the label-free Phase 1 gate for Pick for Me. It replaces one-by-one clicking with a repeatable matrix that exercises the production candidate retrieval, scoring, intent gates, diversity, and calibration path over a frozen local catalog snapshot.

It measures structural behavior and rule consistency. It does **not** claim human semantic relevance: rule-derived mood coverage is calculated from the same evidence rules used by the ranker and is therefore circular.

## Version 1 matrix

- 20 frozen prompts from `session-intent-prompts-v1.csv`
- 4 personas from `session-intent-personas-v1.csv`
- 10 deterministic rotation tokens per prompt/persona pair
- 5 requested recommendations per session
- 800 total sessions grouped into 80 prompt/persona bootstrap clusters
- 2,000 cluster-level bootstrap resamples with a fixed seed

The cold persona has no taste signals. The three warm personas use synthetic genre and keyword preferences only. They intentionally do not read users, reviews, watchlists, caches, or impression history, so the benchmark is reproducible and does not change production state.

## Metrics

The versioned report records shortlist fill, slot fill, rule-derived mood coverage, primary-mood violations, exact runtime and era violations, genre diversity, intra-list similarity, catalog coverage, rotation overlap, unique-result rate, top-1 repeats, latency, merged candidate count, and candidate-channel contributions.

The four headline metrics include 95% confidence intervals produced by resampling whole prompt/persona clusters:

- full-shortlist rate
- rule-derived mood coverage
- primary-mood violation rate
- consecutive-rotation overlap

Cluster copies receive unique bootstrap identities. This matters for rotation metrics: repeated samples of one cluster must remain independent sequences instead of being merged into one longer sequence.

## Required diagnostic slices

Version 1 deliberately keeps the decision-driving slices small:

1. primary mood
2. cold versus synthetic-warm persona
3. keyword-backed versus overview-only evidence, with genre-only and no-evidence rows retained as diagnostic boundaries
4. requested mood count

The mood-count slice was added after the frozen rerun showed it to be the sharpest predictor of shortlist collapse: across 360 three-mood sessions the full-shortlist rate is `0.0000`, against `0.6550` for two-mood sessions. This is the direct measurement of the exact-three conjunctive gate that Phase 3 replaces.

Session-scoped metrics are undefined for item-level slices and are rendered as `—`, never `0`.

Era, runtime, popularity-tail, and vote-confidence slices can be added later without changing the frozen inputs or invalidating this baseline.

## Artifacts

Each successful run replaces the three files under `runs/session-intent-v1/` as one versioned baseline set:

- `baseline.json`: machine-readable metrics, intervals, slices, algorithm version, and catalog checksum
- `observations.csv`: one row per returned item for independent analysis
- `baseline.md`: compact human-readable report

The baseline is meaningful only when the algorithm version, prompt/persona inputs, and catalog checksum are retained with it. A future challenger should run on the same snapshot and use the evaluator's paired cluster-bootstrap delta rather than comparing two unpaired point estimates.

## Run it

The test is opt-in because it starts a container and runs 800 sessions. It needs Docker, but no local database, Redis, or TMDB credentials — the catalog is seeded from the versioned dataset into a disposable PostgreSQL instance:

```powershell
$env:ATLASWATCH_RUN_LIVE_EVALUATION = 'true'
mvn "-Dtest=RecommendationSessionBatchEvaluationTest" test
```

The evaluation catalog is `src/test/resources/evaluation/catalog/session-intent-catalog-v1.json` (793 recommendation-ready movies, fingerprint `d597118d…`). Its fingerprint is asserted before and after the run, so a report can never describe a catalog other than the one it measured.

The ordinary `mvn test` suite leaves this test skipped. Fast evaluator arithmetic and bootstrap behavior remain covered by `SessionIntentBatchEvaluatorTest`.

## Phase 2 paired rotation challenger

Phase 2 reuses the frozen matrix but evaluates two variants on one live catalog snapshot: a control with no displayed-item history and a challenger that accumulates prior displayed TMDB IDs within each prompt/persona session. It therefore makes paired comparisons rather than comparing a new point estimate with the older Phase 1 snapshot.

```powershell
$env:ATLASWATCH_RUN_PHASE2_EVALUATION = 'true'
mvn "-Dtest=RecommendationSessionRotationEvaluationTest" test
```

The run writes `phase2-rotation.json`, `phase2-rotation-observations.csv`, and `phase2-rotation.md` under `runs/session-intent-v1/`. Promotion requires no regression in shortlist fill or rule-derived mood coverage, lower consecutive overlap and top-1 repetition, and a higher unique-result rate. Artifacts are written before assertions so a rejected challenger remains auditable.

The accepted `v15-session-aware-rotation` challenger preserved fill and rule coverage exactly, reduced overlap by `0.0273` (95% paired CI `[-0.0402, -0.0158]`), increased unique-result rate by `0.0617` (`[0.0336, 0.0946]`), and reduced top-1 repetition by `0.2097` (`[-0.2634, -0.1579]`). Runtime and era violations are now gated rather than diagnostic; runtime moved `-0.0010` (`[-0.0023, 0.0000]`) and era was unchanged at zero.

### Superseded pre-freeze run

An earlier version of this comparison ran against the live `Travel` database while the Docker backend continued ingesting into it, so its Phase 1 and Phase 2 artifacts describe different catalogs and must not be read together. That run also reported a `+0.0232` runtime-violation regression that was excluded from the gate. Both problems are fixed: the evaluation is now hermetic, and rotation preserves the baseline slate's runtime-compliance count. The superseded artifacts are kept for provenance under `runs/archive-mutable-catalog/` and are not comparable with the frozen set.

## Promotion and interpretation boundary

Phase 1 is a regression and diagnosis layer. A challenger must not regress the relevant fill/constraint guardrails and should improve decision-driving slices, but these numbers cannot establish that people prefer the recommendations. Graded human or explicitly AI-assisted labels are a later phase and must remain separately versioned.

No inverse-propensity metric is reported. AtlasWatch's current merge/filter/rerank pipeline does not expose a mathematically valid display propensity. Propensity stays absent until a deliberately randomized policy can log the exact probability and position of each impression.
