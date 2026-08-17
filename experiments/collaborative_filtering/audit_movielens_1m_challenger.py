"""One-shot frozen audit of the 64-factor challenger against the served item KNN."""

from __future__ import annotations

import json
import shutil
import urllib.request
import zipfile
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path

from compare_latent_challenger import LatentFactorModel
from evaluate_movielens import ItemKnnModel, Rating, build_targets, evaluate, ratings_before_targets, sha256


DATASET_URL = "https://files.grouplens.org/datasets/movielens/ml-1m.zip"
DATASET_NAME = "ml-1m"


def ensure_dataset(data_dir: Path) -> tuple[Path, str]:
    data_dir.mkdir(parents=True, exist_ok=True)
    archive = data_dir / f"{DATASET_NAME}.zip"
    dataset_dir = data_dir / DATASET_NAME
    if not archive.exists():
        temporary = archive.with_suffix(".zip.part")
        with urllib.request.urlopen(DATASET_URL, timeout=60) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
        temporary.replace(archive)
    if not dataset_dir.exists():
        with zipfile.ZipFile(archive) as zipped:
            prefix = f"{DATASET_NAME}/"
            if any(not member.startswith(prefix) for member in zipped.namelist()):
                raise ValueError("Dataset archive contains an unexpected path")
            zipped.extractall(data_dir)
    return dataset_dir, sha256(archive)


def load_ratings(path: Path) -> list[Rating]:
    ratings: list[Rating] = []
    with path.open(encoding="latin-1") as handle:
        for line_number, line in enumerate(handle, start=1):
            fields = line.rstrip("\n").split("::")
            if len(fields) != 4:
                raise ValueError(f"Unexpected ratings.dat row at line {line_number}")
            ratings.append(Rating(int(fields[0]), int(fields[1]), float(fields[2]), int(fields[3])))
    return ratings


def main() -> int:
    base = Path(__file__).resolve().parent
    dataset_dir, archive_sha = ensure_dataset(base / "data")
    ratings = load_ratings(dataset_dir / "ratings.dat")
    targets = build_targets(ratings, positive_threshold=4.0, minimum_positive_history=5)
    training = ratings_before_targets(ratings, targets, "test")
    positives = [rating for rating in training if rating.rating >= 4.0]

    item_knn = ItemKnnModel(positives, 30, 100, 500)
    latent = LatentFactorModel(positives, components=64)
    item_metrics = evaluate(item_knn, targets, "test", popularity_weight=0.0)
    latent_metrics = evaluate(latent, targets, "test", popularity_weight=0.0)

    def lift(challenger: float, baseline: float) -> float:
        return (challenger - baseline) / baseline

    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "auditStatus": "independent-frozen-configuration",
        "dataset": {
            "name": "MovieLens 1M",
            "source": DATASET_URL,
            "archiveSha256": archive_sha,
            "ratings": len(ratings),
            "users": len({rating.user_id for rating in ratings}),
            "movies": len({rating.movie_id for rating in ratings}),
        },
        "methodology": "one final positive holdout; item KNN fixed at 30 neighbors; challenger fixed at 64 factors",
        "eligibleUsers": len(targets),
        "itemKnn30": asdict(item_metrics),
        "latent64": asdict(latent_metrics),
        "latentRelativeToItemKnn": {
            "hitRateAt5": lift(latent_metrics.hit_rate_at_5, item_metrics.hit_rate_at_5),
            "hitRateAt10": lift(latent_metrics.hit_rate_at_10, item_metrics.hit_rate_at_10),
            "mrrAt10": lift(latent_metrics.mrr_at_10, item_metrics.mrr_at_10),
            "ndcgAt10": lift(latent_metrics.ndcg_at_10, item_metrics.ndcg_at_10),
            "catalogCoverageAt10": lift(
                latent_metrics.catalog_coverage_at_10, item_metrics.catalog_coverage_at_10
            ),
        },
    }
    output = base / "artifacts" / "movielens-1m-challenger-audit-report.json"
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
