# AtlasWatch collaborative-filtering experiment

This directory is an offline model laboratory. It does not add Python to the
production request path: Spring Boot remains the only recommendation API.

The development experiment compares three recommenders on MovieLens Latest Small:

1. global popularity;
2. implicit-positive item-item KNN;
3. a validation-tuned blend of collaborative and popularity scores.

For every eligible user, the second-to-last positive rating is held out for
validation and the last positive rating is held out for test. Only interactions
that happened before a target are available when predicting it. The blend is
chosen on validation, then the model is rebuilt with the validation-period data
and evaluated on the later test target. Ratings of 4.0/5 or higher are positive by default.

The small dataset is intentionally used for fast, reproducible development. Its
test result is treated as development evidence because the final neighbor count
was compared after that split had been viewed. It
contains TMDB identifiers in `links.csv`, allowing the trained neighbors to be
exported in the identifier space used by AtlasWatch. MovieLens is external
behavioral evidence, not evidence about AtlasWatch's own users; promotion still
requires the local synthetic, session-intent, and eventually product-interaction
evaluation layers.

## Run

From the repository root:

```powershell
py -3 -m pip install -r experiments/collaborative_filtering/requirements.txt
py -3 experiments/collaborative_filtering/evaluate_movielens.py --download
py -3 experiments/collaborative_filtering/audit_movielens_100k.py --download
py -3 experiments/collaborative_filtering/compare_latent_challenger.py
py -3 experiments/collaborative_filtering/audit_movielens_1m_challenger.py
py -3 experiments/collaborative_filtering/export_latent_model.py
py -3 -m unittest discover -s experiments/collaborative_filtering/tests -v
```

`--download` retrieves the official GroupLens archive on the first run and
reuses it afterward. The report records the archive SHA-256 digest, configuration,
split sizes, validation results, test results, and TMDB mapping coverage.

Generated files:

- `artifacts/movielens-item-neighbors.json.gz`: compressed Java-consumable model artifact;
- `artifacts/movielens-report.json`: small, reviewable evidence report.
- `artifacts/movielens-100k-audit-report.json`: independent frozen-configuration audit.
- `artifacts/latent-challenger-development-report.json`: development-only factor-size screen.
- `artifacts/movielens-1m-challenger-audit-report.json`: one-shot latent promotion audit.
- `src/main/resources/models/movielens-latent-factors.json.gz`: Java-served 64-factor artifact.

Raw data and the large neighbor artifact are ignored by Git. The report is kept
so before/after claims can be audited.

Official dataset documentation:
https://files.grouplens.org/datasets/movielens/ml-latest-small-README.html

Independent audit dataset:
https://files.grouplens.org/datasets/movielens/ml-100k.zip
