# Session-intent benchmark labeling guide

## Goal

Measure whether a shortlist satisfies what the user asked for now, independently of historical taste. This benchmark targets the failure mode where a broadly popular movie receives a plausible explanation despite missing the requested tone.

## Versioned inputs

- `session-intent-prompts-v1.csv` contains frozen prompts.
- `session-intent-labels-v1.csv` receives judgments for a specific catalog snapshot and algorithm version.
- Never edit an established prompt or label silently. Create `v2` when the rubric or prompt set changes.

## Label scale

Judge the movie against the complete prompt, not whether it is generally a good movie.

| Grade | Meaning |
|---:|---|
| 0 | Contradicts or substantially misses the request |
| 1 | Weak/partial match that would be disappointing in the top five |
| 2 | Good match with credible evidence for the primary intent |
| 3 | Excellent match that strongly satisfies the whole request |

Record mood, runtime, and era compliance separately. A movie can have a good overall grade while violating a soft preference, but a strict constraint violation must remain visible.

## Labeling procedure

1. Freeze the catalog snapshot and algorithm version.
2. Export at least 20 candidates per prompt from every model being compared; deduplicate and randomize titles before labeling.
3. Inspect synopsis, keywords, genres, runtime, release date, and—when necessary—trusted editorial descriptions. Do not inspect the model score or explanation.
4. Assign relevance grade and constraint flags.
5. Prefer two independent labelers for the final benchmark. Resolve disagreements of two or more grade points and record agreement statistics.
6. Evaluate retrieval with Recall@100 and final ordering with nDCG@5, MRR, Precision@5, constraint violations, coverage, and diversity.

## Label file columns

- `benchmark_version`
- `catalog_snapshot`
- `prompt_id`
- `tmdb_id`
- `relevance_grade`
- `mood_satisfied`
- `runtime_satisfied`
- `era_satisfied`
- `labeler`
- `notes`

Do not use generated explanations as labeling evidence: explanations are evaluated separately for faithfulness.
