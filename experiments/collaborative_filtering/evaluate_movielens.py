"""Evaluate and export an item-item collaborative signal for AtlasWatch."""

from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import json
import math
import shutil
import sys
import urllib.request
import zipfile
from collections import defaultdict
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable, Mapping, Sequence

import numpy as np
import scipy
import sklearn
from scipy.sparse import csr_matrix
from sklearn.neighbors import NearestNeighbors


DATASET_URL = "https://files.grouplens.org/datasets/movielens/ml-latest-small.zip"
DATASET_NAME = "ml-latest-small"
DEFAULT_BLEND_WEIGHTS = (0.0, 0.05, 0.10, 0.20, 0.35, 0.50)


@dataclass(frozen=True)
class Rating:
    user_id: int
    movie_id: int
    rating: float
    timestamp: int


@dataclass(frozen=True)
class UserTargets:
    validation: Rating
    test: Rating


@dataclass(frozen=True)
class RankingMetrics:
    users: int
    hit_rate_at_5: float
    hit_rate_at_10: float
    mrr_at_10: float
    ndcg_at_10: float
    catalog_coverage_at_10: float


@dataclass(frozen=True)
class ExperimentConfig:
    positive_threshold: float
    minimum_positive_history: int
    neighbors_per_item: int
    profile_limit: int
    candidate_popularity_limit: int
    blend_weights: tuple[float, ...]


def parse_args() -> argparse.Namespace:
    base = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download", action="store_true", help="Download the official dataset if absent")
    parser.add_argument("--data-dir", type=Path, default=base / "data")
    parser.add_argument("--artifact-dir", type=Path, default=base / "artifacts")
    parser.add_argument("--positive-threshold", type=float, default=4.0)
    parser.add_argument("--minimum-positive-history", type=int, default=5)
    parser.add_argument("--neighbors", type=int, default=30)
    parser.add_argument("--profile-limit", type=int, default=100)
    parser.add_argument("--candidate-popularity-limit", type=int, default=500)
    parser.add_argument(
        "--production-model",
        type=Path,
        help="Also write the compressed model to this production resource path",
    )
    return parser.parse_args()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def ensure_dataset(data_dir: Path, allow_download: bool) -> tuple[Path, str]:
    data_dir.mkdir(parents=True, exist_ok=True)
    dataset_dir = data_dir / DATASET_NAME
    archive = data_dir / f"{DATASET_NAME}.zip"
    if not archive.exists():
        if not allow_download:
            raise FileNotFoundError(f"{archive} is absent; rerun with --download")
        temporary = archive.with_suffix(".zip.part")
        with urllib.request.urlopen(DATASET_URL, timeout=60) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
        temporary.replace(archive)
    if not dataset_dir.exists():
        with zipfile.ZipFile(archive) as zipped:
            expected_prefix = f"{DATASET_NAME}/"
            if any(not member.startswith(expected_prefix) for member in zipped.namelist()):
                raise ValueError("Dataset archive contains an unexpected path")
            zipped.extractall(data_dir)
    return dataset_dir, sha256(archive)


def load_ratings(path: Path) -> list[Rating]:
    with path.open(encoding="utf-8", newline="") as handle:
        return [
            Rating(int(row["userId"]), int(row["movieId"]), float(row["rating"]), int(row["timestamp"]))
            for row in csv.DictReader(handle)
        ]


def load_tmdb_links(path: Path) -> dict[int, int]:
    links: dict[int, int] = {}
    with path.open(encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            if row["tmdbId"]:
                links[int(row["movieId"])] = int(float(row["tmdbId"]))
    return links


def build_targets(
    ratings: Sequence[Rating], positive_threshold: float, minimum_positive_history: int
) -> dict[int, UserTargets]:
    positives: dict[int, list[Rating]] = defaultdict(list)
    for rating in ratings:
        if rating.rating >= positive_threshold:
            positives[rating.user_id].append(rating)
    targets: dict[int, UserTargets] = {}
    for user_id, user_positives in positives.items():
        ordered = sorted(user_positives, key=lambda item: (item.timestamp, item.movie_id))
        if len(ordered) >= minimum_positive_history:
            targets[user_id] = UserTargets(validation=ordered[-2], test=ordered[-1])
    return targets


def ratings_before_targets(
    ratings: Sequence[Rating], targets: Mapping[int, UserTargets], stage: str
) -> list[Rating]:
    result: list[Rating] = []
    for rating in ratings:
        target = targets.get(rating.user_id)
        if target is None:
            continue
        cutoff = target.validation.timestamp if stage == "validation" else target.test.timestamp
        target_movie = target.validation.movie_id if stage == "validation" else target.test.movie_id
        if rating.timestamp < cutoff or (rating.timestamp == cutoff and rating.movie_id != target_movie):
            result.append(rating)
    return result


class ItemKnnModel:
    def __init__(
        self,
        positives: Sequence[Rating],
        neighbors_per_item: int,
        profile_limit: int,
        candidate_popularity_limit: int,
    ) -> None:
        self.profile_limit = profile_limit
        self.user_seen: dict[int, set[int]] = defaultdict(set)
        self.user_positives: dict[int, list[Rating]] = defaultdict(list)
        item_users: dict[int, set[int]] = defaultdict(set)
        for rating in positives:
            self.user_seen[rating.user_id].add(rating.movie_id)
            self.user_positives[rating.user_id].append(rating)
            item_users[rating.movie_id].add(rating.user_id)

        self.item_ids = sorted(item_users)
        self.item_index = {movie_id: index for index, movie_id in enumerate(self.item_ids)}
        user_ids = sorted({rating.user_id for rating in positives})
        user_index = {user_id: index for index, user_id in enumerate(user_ids)}
        rows: list[int] = []
        columns: list[int] = []
        for movie_id, users in item_users.items():
            rows.extend([self.item_index[movie_id]] * len(users))
            columns.extend(user_index[user_id] for user_id in users)
        matrix = csr_matrix(
            (np.ones(len(rows), dtype=np.float32), (rows, columns)),
            shape=(len(self.item_ids), len(user_ids)),
        )

        counts = np.asarray(matrix.sum(axis=1)).reshape(-1)
        maximum_count = max(float(counts.max(initial=1.0)), 1.0)
        self.popularity = {
            movie_id: math.log1p(float(counts[index])) / math.log1p(maximum_count)
            for index, movie_id in enumerate(self.item_ids)
        }
        self.popular_items = sorted(
            self.item_ids, key=lambda movie_id: (-self.popularity[movie_id], movie_id)
        )[:candidate_popularity_limit]

        requested_neighbors = min(neighbors_per_item + 1, len(self.item_ids))
        nearest = NearestNeighbors(metric="cosine", algorithm="brute", n_jobs=-1)
        nearest.fit(matrix)
        distances, indices = nearest.kneighbors(matrix, n_neighbors=requested_neighbors)
        self.neighbors: dict[int, list[tuple[int, float]]] = {}
        for row_index, movie_id in enumerate(self.item_ids):
            pairs: list[tuple[int, float]] = []
            for neighbor_index, distance in zip(indices[row_index], distances[row_index]):
                neighbor_id = self.item_ids[int(neighbor_index)]
                similarity = max(0.0, 1.0 - float(distance))
                if neighbor_id != movie_id and similarity > 0.0:
                    pairs.append((neighbor_id, similarity))
            self.neighbors[movie_id] = pairs

    def scores(self, user_id: int, popularity_weight: float) -> dict[int, float]:
        history = sorted(
            self.user_positives.get(user_id, ()), key=lambda item: (item.timestamp, item.movie_id), reverse=True
        )[: self.profile_limit]
        seen = self.user_seen.get(user_id, set())
        collaborative: dict[int, float] = defaultdict(float)
        for rating in history:
            preference = max(0.25, (rating.rating - 3.0) / 2.0)
            for candidate, similarity in self.neighbors.get(rating.movie_id, ()):
                if candidate not in seen:
                    collaborative[candidate] += similarity * preference
        maximum_cf = max(collaborative.values(), default=1.0)
        candidates = set(collaborative).union(self.popular_items)
        return {
            candidate: (1.0 - popularity_weight) * (collaborative.get(candidate, 0.0) / maximum_cf)
            + popularity_weight * self.popularity.get(candidate, 0.0)
            for candidate in candidates
            if candidate not in seen
        }

    def recommend(self, user_id: int, popularity_weight: float, limit: int = 10) -> list[int]:
        scores = self.scores(user_id, popularity_weight)
        return [
            movie_id
            for movie_id, _ in sorted(
                scores.items(), key=lambda pair: (-pair[1], -self.popularity.get(pair[0], 0.0), pair[0])
            )[:limit]
        ]


def evaluate(
    model: ItemKnnModel,
    targets: Mapping[int, UserTargets],
    target_stage: str,
    popularity_weight: float,
) -> RankingMetrics:
    hits_5 = hits_10 = 0
    reciprocal_rank = discounted_gain = 0.0
    catalog_items: set[int] = set()
    evaluated = 0
    for user_id, user_targets in targets.items():
        target = user_targets.validation.movie_id if target_stage == "validation" else user_targets.test.movie_id
        ranked = model.recommend(user_id, popularity_weight, limit=10)
        if not ranked:
            continue
        evaluated += 1
        catalog_items.update(ranked)
        if target in ranked[:5]:
            hits_5 += 1
        if target in ranked:
            rank = ranked.index(target) + 1
            hits_10 += 1
            reciprocal_rank += 1.0 / rank
            discounted_gain += 1.0 / math.log2(rank + 1)
    denominator = max(evaluated, 1)
    return RankingMetrics(
        users=evaluated,
        hit_rate_at_5=hits_5 / denominator,
        hit_rate_at_10=hits_10 / denominator,
        mrr_at_10=reciprocal_rank / denominator,
        ndcg_at_10=discounted_gain / denominator,
        catalog_coverage_at_10=len(catalog_items) / max(len(model.item_ids), 1),
    )


def choose_blend(validation_results: Mapping[float, RankingMetrics]) -> float:
    return max(
        validation_results,
        key=lambda weight: (
            validation_results[weight].ndcg_at_10,
            validation_results[weight].hit_rate_at_10,
            validation_results[weight].catalog_coverage_at_10,
            -weight,
        ),
    )


def export_neighbors(model: ItemKnnModel, tmdb_links: Mapping[int, int], destination: Path, config: ExperimentConfig) -> int:
    mapped: dict[str, list[dict[str, float | int]]] = {}
    for movie_id, neighbors in model.neighbors.items():
        tmdb_id = tmdb_links.get(movie_id)
        if tmdb_id is None:
            continue
        converted = [
            {"tmdbId": tmdb_links[neighbor_id], "similarity": round(similarity, 6)}
            for neighbor_id, similarity in neighbors
            if neighbor_id in tmdb_links
        ]
        if converted:
            mapped[str(tmdb_id)] = converted
    payload = {
        "schemaVersion": 1,
        "algorithm": "implicit-positive-item-knn-cosine",
        "positiveThreshold": config.positive_threshold,
        "neighborsPerItem": config.neighbors_per_item,
        "items": mapped,
    }
    destination.parent.mkdir(parents=True, exist_ok=True)
    serialized = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    if destination.suffix == ".gz":
        destination.write_bytes(gzip.compress(serialized, compresslevel=9, mtime=0))
    else:
        destination.write_bytes(serialized)
    return len(mapped)


def main() -> int:
    args = parse_args()
    if not 0.5 <= args.positive_threshold <= 5.0:
        raise ValueError("positive threshold must be between 0.5 and 5.0")
    config = ExperimentConfig(
        positive_threshold=args.positive_threshold,
        minimum_positive_history=args.minimum_positive_history,
        neighbors_per_item=args.neighbors,
        profile_limit=args.profile_limit,
        candidate_popularity_limit=args.candidate_popularity_limit,
        blend_weights=DEFAULT_BLEND_WEIGHTS,
    )
    dataset_dir, dataset_sha = ensure_dataset(args.data_dir, args.download)
    ratings = load_ratings(dataset_dir / "ratings.csv")
    tmdb_links = load_tmdb_links(dataset_dir / "links.csv")
    targets = build_targets(ratings, config.positive_threshold, config.minimum_positive_history)
    if not targets:
        raise ValueError("No users have enough positive history for validation and test")

    validation_train = ratings_before_targets(ratings, targets, "validation")
    validation_positives = [rating for rating in validation_train if rating.rating >= config.positive_threshold]
    validation_model = ItemKnnModel(
        validation_positives, config.neighbors_per_item, config.profile_limit, config.candidate_popularity_limit
    )
    validation_results = {
        weight: evaluate(validation_model, targets, "validation", weight) for weight in config.blend_weights
    }
    selected_weight = choose_blend(validation_results)

    test_train = ratings_before_targets(ratings, targets, "test")
    test_positives = [rating for rating in test_train if rating.rating >= config.positive_threshold]
    test_model = ItemKnnModel(
        test_positives, config.neighbors_per_item, config.profile_limit, config.candidate_popularity_limit
    )
    test_results = {
        "popularity": evaluate(test_model, targets, "test", 1.0),
        "collaborative": evaluate(test_model, targets, "test", 0.0),
        "hybrid": evaluate(test_model, targets, "test", selected_weight),
    }

    all_positives = [rating for rating in ratings if rating.rating >= config.positive_threshold]
    export_model = ItemKnnModel(
        all_positives, config.neighbors_per_item, config.profile_limit, config.candidate_popularity_limit
    )
    args.artifact_dir.mkdir(parents=True, exist_ok=True)
    model_path = args.artifact_dir / "movielens-item-neighbors.json.gz"
    mapped_items = export_neighbors(export_model, tmdb_links, model_path, config)
    if args.production_model:
        export_neighbors(export_model, tmdb_links, args.production_model, config)
    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "dataset": {
            "name": DATASET_NAME,
            "source": DATASET_URL,
            "archiveSha256": dataset_sha,
            "ratings": len(ratings),
            "users": len({rating.user_id for rating in ratings}),
            "movies": len({rating.movie_id for rating in ratings}),
        },
        "dependencies": {
            "python": sys.version.split()[0],
            "numpy": np.__version__,
            "scipy": scipy.__version__,
            "scikitLearn": sklearn.__version__,
        },
        "methodology": "per-user chronological validation and test positive holdouts",
        "config": asdict(config),
        "eligibleUsers": len(targets),
        "selectedPopularityWeight": selected_weight,
        "validation": {str(weight): asdict(metrics) for weight, metrics in validation_results.items()},
        "test": {name: asdict(metrics) for name, metrics in test_results.items()},
        "artifact": {
            "schemaVersion": 1,
            "mappedTmdbItems": mapped_items,
            "totalModelItems": len(export_model.item_ids),
            "tmdbMappingCoverage": mapped_items / max(len(export_model.item_ids), 1),
            "path": model_path.name,
            "sha256": sha256(model_path),
        },
    }
    report_path = args.artifact_dir / "movielens-report.json"
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    print(f"\nModel: {model_path}\nReport: {report_path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
