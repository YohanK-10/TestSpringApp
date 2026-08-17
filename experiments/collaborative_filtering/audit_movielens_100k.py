"""Run the frozen AtlasWatch collaborative model on the independent MovieLens 100K dataset."""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import urllib.request
import zipfile
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import scipy
import sklearn

from evaluate_movielens import (
    ItemKnnModel,
    Rating,
    build_targets,
    evaluate,
    ratings_before_targets,
    sha256,
)


DATASET_URL = "https://files.grouplens.org/datasets/movielens/ml-100k.zip"
DATASET_NAME = "ml-100k"

# Frozen before this dataset was evaluated. These are the settings selected while
# developing against MovieLens Latest Small; this audit intentionally has no tuning loop.
POSITIVE_THRESHOLD = 4.0
MINIMUM_POSITIVE_HISTORY = 5
NEIGHBORS_PER_ITEM = 30
PROFILE_LIMIT = 100
CANDIDATE_POPULARITY_LIMIT = 500


def parse_args() -> argparse.Namespace:
    base = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--download", action="store_true", help="Download the official dataset if absent")
    parser.add_argument("--data-dir", type=Path, default=base / "data")
    parser.add_argument("--artifact-dir", type=Path, default=base / "artifacts")
    return parser.parse_args()


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
    ratings: list[Rating] = []
    with path.open(encoding="ascii") as handle:
        for line_number, line in enumerate(handle, start=1):
            fields = line.rstrip("\n").split("\t")
            if len(fields) != 4:
                raise ValueError(f"Unexpected u.data row at line {line_number}")
            ratings.append(Rating(int(fields[0]), int(fields[1]), float(fields[2]), int(fields[3])))
    return ratings


def relative_change(challenger: float, baseline: float) -> float | None:
    return None if baseline == 0.0 else (challenger - baseline) / baseline


def main() -> int:
    args = parse_args()
    dataset_dir, dataset_sha = ensure_dataset(args.data_dir, args.download)
    ratings = load_ratings(dataset_dir / "u.data")
    targets = build_targets(ratings, POSITIVE_THRESHOLD, MINIMUM_POSITIVE_HISTORY)
    if not targets:
        raise ValueError("No users have enough positive history for the audit")

    training = ratings_before_targets(ratings, targets, "test")
    positives = [rating for rating in training if rating.rating >= POSITIVE_THRESHOLD]
    model = ItemKnnModel(
        positives,
        NEIGHBORS_PER_ITEM,
        PROFILE_LIMIT,
        CANDIDATE_POPULARITY_LIMIT,
    )
    popularity = evaluate(model, targets, "test", popularity_weight=1.0)
    collaborative = evaluate(model, targets, "test", popularity_weight=0.0)
    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "auditStatus": "independent-frozen-configuration",
        "dataset": {
            "name": "MovieLens 100K",
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
        "methodology": "one final positive holdout per eligible user; all settings frozen before audit",
        "frozenConfig": {
            "positiveThreshold": POSITIVE_THRESHOLD,
            "minimumPositiveHistory": MINIMUM_POSITIVE_HISTORY,
            "neighborsPerItem": NEIGHBORS_PER_ITEM,
            "profileLimit": PROFILE_LIMIT,
            "candidatePopularityLimit": CANDIDATE_POPULARITY_LIMIT,
            "popularityWeight": 0.0,
        },
        "eligibleUsers": len(targets),
        "test": {
            "popularity": asdict(popularity),
            "collaborative": asdict(collaborative),
        },
        "relativeLift": {
            "hitRateAt5": relative_change(collaborative.hit_rate_at_5, popularity.hit_rate_at_5),
            "hitRateAt10": relative_change(collaborative.hit_rate_at_10, popularity.hit_rate_at_10),
            "mrrAt10": relative_change(collaborative.mrr_at_10, popularity.mrr_at_10),
            "ndcgAt10": relative_change(collaborative.ndcg_at_10, popularity.ndcg_at_10),
            "catalogCoverageAt10": relative_change(
                collaborative.catalog_coverage_at_10, popularity.catalog_coverage_at_10
            ),
        },
    }
    args.artifact_dir.mkdir(parents=True, exist_ok=True)
    report_path = args.artifact_dir / "movielens-100k-audit-report.json"
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    print(f"\nAudit report: {report_path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
