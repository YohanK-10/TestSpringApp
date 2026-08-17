# Recommendation Evaluation Baseline

## Purpose

Recommendation changes must outperform a recorded baseline instead of being accepted because a few screenshots look better. The automated evaluator measures relevance, ordering, coverage, diversity, and explicit-constraint compliance.

This synthetic baseline is a deterministic regression suite. It is not evidence that real users will like the recommendations; the separately versioned session-intent benchmark supplies that human judgment.

## Methodology

`RecommendationEvaluationTest` runs the full recommendation pipeline against a synthetic catalog of 15 movies across eight genres and four users with different taste profiles.

| User | Profile | Reviews | Watchlist | Held-out positives |
|---|---|---:|---:|---:|
| Alice | Thriller / Crime | 2 | 0 | 2 |
| Bob | Comedy / Drama | 2 | 1 | 3 |
| Carol | Sci-Fi / Action | 1 | 1 | 3 |
| Dave | Drama / Mystery | 2 | 1 watched | 2 |

The held-out positives simulate later positive interactions. Binary relevance powers Hit Rate, Precision@K, Recall@K, and MRR. The evaluator also accepts 0-3 relevance grades for nDCG@K, so future human labels can distinguish weak, good, and excellent matches.

## Baseline metrics

Captured: 2026-08-16

Rechecked after introducing confidence-adjusted TMDB quality scoring. The synthetic fixtures now include stable vote counts, and all metrics remained unchanged. This confirms regression safety; it does not by itself prove that the prior values are optimal for the real catalog.

Rechecked after requiring semantic evidence for ambiguous moods. The deterministic baseline uses `moods=any`, so its unchanged metrics protect unrelated ranking behavior but do not measure the semantic improvement. The versioned session-intent prompts and human labels are the appropriate evaluation layer for that claim.

| Metric | Value | Interpretation |
|---|---:|---|
| Hit rate | 1.0000 | Every user received at least one held-out positive |
| Mean Precision@5 | 0.3500 | 35% of returned movies were held-out positives |
| Mean Recall@5 | 0.6667 | Two thirds of known positives were retrieved |
| Mean reciprocal rank | 0.4583 | The first relevant result is not consistently near rank one |
| Mean nDCG@5 | 0.4545 | Relevant items are found but ordering has substantial room to improve |
| Catalog coverage | 0.8667 | 13 of 15 movies appeared across all lists |
| Mean genre diversity | 0.5250 | Lists retain moderate genre breadth |
| Mean intra-list similarity | 0.2750 | Lists share some genres without collapsing into one cluster |

The synthetic baseline request uses `moods=any` and `runtime=any`, so its 0% constraint-violation values are structural and are not a meaningful product-quality result. Active mood/runtime/era constraints belong in the session-intent benchmark.

## Guardrails

The regression test currently rejects changes below:

- Precision@5: 0.25
- Recall@5: 0.50
- MRR: 0.40
- nDCG@5: 0.40
- catalog coverage: 0.60
- genre diversity: 0.35
- intra-list similarity: at most 0.50

These are regression floors, not quality goals. Do not lower a floor merely to make a new model pass. Record and justify any intentional relevance/diversity trade-off.

## Reproduce

```bash
mvn "-Dtest=RecommendationEvaluationTest" test
```

The formatted report is printed during the test. Run it after changes to retrieval, scoring, filtering, reranking, taste profiles, content similarity, or model inference.

## Evaluation layers

1. **Synthetic regression:** fast, deterministic protection for pipeline behavior.
2. **Human-labeled session intent:** graded relevance and real constraint compliance across versioned prompts.
3. **Historical interaction evaluation:** temporal train/test splits for collaborative and learning-to-rank models.
4. **Blind user comparison:** compare baseline, semantic, and hybrid result lists without revealing the algorithm.

No model should be promoted solely from one layer.

## Label-free session-intent baseline

Phase 0 first established that all 770 then-recommendation-ready movies completed semantic detail synchronization. Normal ingestion added five eligible, semantically complete rows before Phase 1 froze its 775-film checksum. Phase 1 then ran 800 non-mutating sessions across frozen prompts, synthetic personas, and deterministic rotations. The report includes cluster-bootstrap confidence intervals and primary-mood, evidence-source, and cold/warm slices.

See `docs/evaluation/catalog-semantic-backfill-v1.md` for the catalog gate, `docs/evaluation/session-intent-batch-evaluation.md` for methodology and limitations, and `docs/evaluation/runs/session-intent-v1/baseline.md` for the frozen result.

These are label-free structural metrics. Rule-derived mood coverage must not be presented as human relevance.

Phase 2 evaluated session-aware rotation against a control run beside it in the same process, on the frozen `session-intent-catalog-v1` dataset (793 recommendation-ready movies, fingerprint `d597118d…`) seeded into a disposable Testcontainers database. Across 800 sessions per variant it preserved full-shortlist rate, slot fill, and rule-derived mood coverage exactly. Paired cluster bootstrap showed lower consecutive overlap (`-0.0273`, 95% CI `[-0.0402, -0.0158]`), higher unique-result rate (`+0.0617`, `[0.0336, 0.0946]`), and lower top-1 repetition (`-0.2097`, `[-0.2634, -0.1579]`). Runtime and era violations are gated rather than diagnostic: runtime moved `-0.0010` with CI `[-0.0023, 0.0000]`, so it did not increase, and era stayed at zero. The accepted report and item-level evidence are under `docs/evaluation/runs/session-intent-v1/phase2-rotation.*`.

An earlier version of this comparison ran against the live database while a backend kept ingesting into it; its numbers are superseded and archived under `docs/evaluation/runs/archive-mutable-catalog/`. Do not quote them.

## Historical collaborative benchmark

The first historical layer used 603 eligible MovieLens Latest Small users for model development. Because the final neighbor count was compared after viewing that split, a separate frozen audit was then run once on 938 eligible MovieLens 100K users. Against popularity, the audited model improved HR@10 from `0.0586` to `0.0789` (+34.5%), nDCG@10 from `0.0289` to `0.0394` (+36.3%), and coverage@10 from `0.0448` to `0.1531` (3.42x). Full methodology, development results, failed-formulation evidence, and limitations are recorded in `docs/evaluation/collaborative-filtering-results.md`.

This external benchmark authorizes a bounded collaborative signal; it does not replace the synthetic or session-intent layers and is not an AtlasWatch online result.

A later 64-factor truncated-SVD challenger was fixed on development data and audited once against item-KNN on MovieLens 1M. Across 6,034 eligible users it improved HR@10 by 58.4%, nDCG@10 by 55.5%, MRR@10 by 52.8%, and coverage@10 by 27.4%; this all-metric win authorized the schema-2 latent artifact now served by Java.
