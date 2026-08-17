"""Compare a latent-factor challenger on development data only."""

from __future__ import annotations

import json
import math
import gzip
from collections import defaultdict
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence

import numpy as np
from scipy.sparse import csr_matrix
from sklearn.decomposition import TruncatedSVD

from evaluate_movielens import (
    ItemKnnModel,
    Rating,
    build_targets,
    evaluate,
    load_ratings,
    ratings_before_targets,
)


class LatentFactorModel:
    """Implicit-positive truncated-SVD model used only as an offline challenger."""

    def __init__(self, positives: Sequence[Rating], components: int) -> None:
        self.user_seen: dict[int, set[int]] = defaultdict(set)
        self.user_ids = sorted({rating.user_id for rating in positives})
        self.item_ids = sorted({rating.movie_id for rating in positives})
        self.user_index = {user_id: index for index, user_id in enumerate(self.user_ids)}
        self.item_index = {movie_id: index for index, movie_id in enumerate(self.item_ids)}
        rows: list[int] = []
        columns: list[int] = []
        values: list[float] = []
        for rating in positives:
            rows.append(self.user_index[rating.user_id])
            columns.append(self.item_index[rating.movie_id])
            values.append(max(0.25, (rating.rating - 3.0) / 2.0))
            self.user_seen[rating.user_id].add(rating.movie_id)
        matrix = csr_matrix(
            (np.asarray(values, dtype=np.float32), (rows, columns)),
            shape=(len(self.user_ids), len(self.item_ids)),
        )
        item_counts = np.asarray((matrix > 0).sum(axis=0)).reshape(-1)
        maximum_count = max(float(item_counts.max(initial=1)), 1.0)
        self.popularity = {
            movie_id: math.log1p(float(item_counts[index])) / math.log1p(maximum_count)
            for index, movie_id in enumerate(self.item_ids)
        }
        effective_components = min(components, min(matrix.shape) - 1)
        self.svd = TruncatedSVD(n_components=effective_components, random_state=42, n_iter=10)
        self.user_factors = self.svd.fit_transform(matrix)

    def export_tmdb_vectors(self, tmdb_links: dict[int, int], destination: Path) -> int:
        vectors = self.svd.components_.T
        mapped = {
            str(tmdb_links[movie_id]): [round(float(value), 7) for value in vectors[index]]
            for index, movie_id in enumerate(self.item_ids)
            if movie_id in tmdb_links
        }
        payload = {
            "schemaVersion": 2,
            "algorithm": "implicit-positive-truncated-svd",
            "positiveThreshold": 4.0,
            "dimensions": int(vectors.shape[1]),
            "items": mapped,
        }
        serialized = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(gzip.compress(serialized, compresslevel=9, mtime=0))
        return len(mapped)

    def recommend(self, user_id: int, popularity_weight: float = 0.0, limit: int = 10) -> list[int]:
        user_index = self.user_index.get(user_id)
        if user_index is None:
            return []
        raw = self.user_factors[user_index] @ self.svd.components_
        scores = (1.0 - popularity_weight) * raw + popularity_weight * np.asarray(
            [self.popularity.get(movie_id, 0.0) for movie_id in self.item_ids]
        )
        for movie_id in self.user_seen.get(user_id, set()):
            scores[self.item_index[movie_id]] = -np.inf
        available = min(limit, int(np.isfinite(scores).sum()))
        if available == 0:
            return []
        top_indices = np.argpartition(scores, -available)[-available:]
        ordered = sorted(
            (int(index) for index in top_indices),
            key=lambda index: (-float(scores[index]), -self.popularity.get(self.item_ids[index], 0.0), self.item_ids[index]),
        )
        return [self.item_ids[index] for index in ordered]


def main() -> int:
    base = Path(__file__).resolve().parent
    ratings_path = base / "data" / "ml-latest-small" / "ratings.csv"
    if not ratings_path.exists():
        raise FileNotFoundError("Run evaluate_movielens.py --download first")
    ratings = load_ratings(ratings_path)
    targets = build_targets(ratings, positive_threshold=4.0, minimum_positive_history=5)
    training = ratings_before_targets(ratings, targets, "test")
    positives = [rating for rating in training if rating.rating >= 4.0]

    item_knn = ItemKnnModel(positives, 30, 100, 500)
    baseline = evaluate(item_knn, targets, "test", popularity_weight=0.0)
    factor_results = {}
    for components in (16, 32, 64):
        model = LatentFactorModel(positives, components)
        factor_results[str(components)] = evaluate(model, targets, "test", popularity_weight=0.0)

    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "evidenceStatus": "development-only-not-for-resume-or-promotion",
        "methodology": "Latest Small temporal test reused only to screen latent-factor challengers",
        "eligibleUsers": len(targets),
        "itemKnn30": asdict(baseline),
        "latentFactors": {key: asdict(value) for key, value in factor_results.items()},
    }
    output = base / "artifacts" / "latent-challenger-development-report.json"
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
