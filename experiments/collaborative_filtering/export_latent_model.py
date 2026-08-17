"""Train the promoted 64-factor model on all Latest Small positives and export it for Java."""

from __future__ import annotations

import argparse
from pathlib import Path

from compare_latent_challenger import LatentFactorModel
from evaluate_movielens import load_ratings, load_tmdb_links, sha256


def main() -> int:
    base = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--production-model",
        type=Path,
        default=Path("src/main/resources/models/movielens-latent-factors.json.gz"),
    )
    args = parser.parse_args()
    dataset = base / "data" / "ml-latest-small"
    ratings = load_ratings(dataset / "ratings.csv")
    positives = [rating for rating in ratings if rating.rating >= 4.0]
    model = LatentFactorModel(positives, components=64)
    mapped = model.export_tmdb_vectors(load_tmdb_links(dataset / "links.csv"), args.production_model)
    print(f"Exported {mapped} mapped item vectors to {args.production_model}")
    print(f"SHA-256: {sha256(args.production_model)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
