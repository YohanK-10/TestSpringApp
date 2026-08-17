# Collaborative-filtering experiment results

Captured: 2026-08-16

## Question

Can a population-level collaborative signal retrieve a user's later positive movie more effectively than global popularity, while broadening the catalog?

## Data and split

- MovieLens Latest Small snapshot: 100,836 ratings, 610 users, 9,724 rated movies.
- Positive label: rating `>= 4.0/5`.
- Eligible users: 603 with at least five positives.
- Per-user chronological validation target: second-to-last positive.
- Per-user chronological test target: last positive.
- Training contains only interactions before the relevant target.
- Validation chooses among configured popularity weights. The later test target supplied development feedback, including the final neighbor-count comparison.

This is a leave-later-out protocol rather than a global calendar cutoff. It prevents a user's target and later behavior from entering that user's training history. Other users' relative timelines are independent, so this is not a simulation of deploying at one shared historical date.

## Models

- Popularity: log-normalized positive interaction count.
- Collaborative: binary implicit-positive item-item cosine KNN with summed evidence from up to 100 recent positive profile items.
- Hybrid search: collaborative scores combined with configured popularity weights. Validation selected `0.0` popularity, so the reported collaborative and selected hybrid results are identical.
- Serving artifact: top 30 neighbors per item, trained on all positives after evaluation.

## Development-dataset results

These Latest Small numbers guided implementation choices, including the final neighbor count. They are useful development evidence, but they are **not** described as untouched evidence because the 30- versus 100-neighbor comparison happened after viewing this test split.

| Metric | Popularity | Selected collaborative | Relative change |
|---|---:|---:|---:|
| HR@5 | 0.0315 | 0.0365 | +15.8% |
| HR@10 | 0.0381 | 0.0680 | +78.3% |
| MRR@10 | 0.0142 | 0.0200 | +40.5% |
| nDCG@10 | 0.0200 | 0.0310 | +55.3% |
| Catalog coverage@10 | 0.0097 | 0.1129 | 11.6x |

The absolute values are low because every user is ranked against a broad catalog and only one held-out movie is considered relevant. Relative comparisons are meaningful because every model uses the same users, targets, candidates, and cutoff logic.

## Independent frozen audit

After identifying the development-set reuse, the completed algorithm and configuration were frozen and evaluated once on the separate official MovieLens 100K dataset. No parameter or formulation was selected using these results.

- Dataset: 100,000 ratings, 943 users, 1,682 rated movies.
- Eligible users: 938 with at least five positives.
- Target: each user's final rating of at least 4/5; only earlier interactions train the model.
- Frozen configuration: positive threshold 4/5, 30 neighbors, 100-item profile limit, 500 popularity candidates, and no popularity blending.

| Metric | Popularity | Collaborative | Relative change |
|---|---:|---:|---:|
| HR@5 | 0.0330 | 0.0469 | +41.9% |
| HR@10 | 0.0586 | 0.0789 | +34.5% |
| MRR@10 | 0.0200 | 0.0275 | +37.4% |
| nDCG@10 | 0.0289 | 0.0394 | +36.3% |
| Catalog coverage@10 | 0.0448 | 0.1531 | 3.42x |

This independent result is the resume-grade claim. It demonstrates repeatable lift over the same popularity baseline on another historical dataset, not online AtlasWatch impact.

## Latent-factor challenger and promotion

A truncated-SVD implicit-feedback challenger was screened only on Latest Small development data at 16, 32, and 64 factors. The frozen 64-factor configuration was then compared once against the served 30-neighbor item model on the separate MovieLens 1M dataset (1,000,209 ratings, 6,040 users). No 1M result was viewed before fixing the formulation and factor count.

| Metric | Item KNN (30) | Latent (64) | Latent relative change |
|---|---:|---:|---:|
| HR@5 | 0.0315 | 0.0514 | +63.2% |
| HR@10 | 0.0545 | 0.0863 | +58.4% |
| MRR@10 | 0.0195 | 0.0298 | +52.8% |
| nDCG@10 | 0.0276 | 0.0429 | +55.5% |
| Catalog coverage@10 | 0.1595 | 0.2032 | +27.4% |

Because the challenger improved every promotion metric, it replaced the neighbor graph in serving. Python trains and exports 64-dimensional TMDB-keyed item vectors; Java builds a weighted profile vector from positive reviews/watchlist seeds and performs the equivalent dot-product ranking without a Python runtime service.

## Failed formulation retained as evidence

The first implementation divided a candidate's summed similarity by its total similarity support. It produced HR@10 `0.0116`, materially below popularity. That normalization made one weak connection look like several independent supporting connections. Summing weighted similarities fixed the defect and is protected by `CollaborativeAggregationTest`.

## Artifact

- Schema: 2
- Learned MovieLens items: 6,298 positive-history items
- TMDB-mapped items: 6,293 (99.92%)
- Dimensions: 64
- Compressed production size: approximately 1.23 MB
- SHA-256: `35bb5c2f13dd9f407da98512b42be5648f4fd95e83ad21775be9cd57801ea351`

The development and audit reports are retained under `experiments/collaborative_filtering/artifacts/`, including the MovieLens 100K item-model audit and MovieLens 1M latent-challenger audit.

## Interpretation boundary

This supports adding a low-weight collaborative feature. It does not demonstrate that external MovieLens preferences improve AtlasWatch's session moods, newer movies, or real-user outcomes. Strict product constraints remain ahead of model score, and later AtlasWatch interactions should be evaluated with the same temporal protocol.
