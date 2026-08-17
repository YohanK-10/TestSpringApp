import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from evaluate_movielens import (  # noqa: E402
    ItemKnnModel,
    RankingMetrics,
    Rating,
    build_targets,
    choose_blend,
    ratings_before_targets,
)
from audit_movielens_100k import load_ratings, relative_change  # noqa: E402


class TemporalSplitTest(unittest.TestCase):
    def setUp(self):
        self.ratings = [
            Rating(1, 10, 4.0, 100),
            Rating(1, 11, 4.5, 200),
            Rating(1, 12, 5.0, 300),
            Rating(1, 13, 4.0, 400),
            Rating(1, 14, 4.5, 500),
            Rating(1, 99, 2.0, 600),
            Rating(2, 20, 5.0, 100),
            Rating(2, 21, 5.0, 200),
        ]

    def test_uses_second_last_and_last_positive_as_validation_and_test(self):
        targets = build_targets(self.ratings, positive_threshold=4.0, minimum_positive_history=5)

        self.assertEqual(13, targets[1].validation.movie_id)
        self.assertEqual(14, targets[1].test.movie_id)
        self.assertNotIn(2, targets)

    def test_training_never_contains_the_target_or_later_interactions(self):
        targets = build_targets(self.ratings, positive_threshold=4.0, minimum_positive_history=5)

        validation = ratings_before_targets(self.ratings, targets, "validation")
        test = ratings_before_targets(self.ratings, targets, "test")

        self.assertEqual([10, 11, 12], [rating.movie_id for rating in validation])
        self.assertEqual([10, 11, 12, 13], [rating.movie_id for rating in test])


class BlendSelectionTest(unittest.TestCase):
    def test_selects_best_validation_ndcg_without_looking_at_test(self):
        def metrics(ndcg, hit_rate=0.2, coverage=0.1):
            return RankingMetrics(10, 0.1, hit_rate, 0.1, ndcg, coverage)

        selected = choose_blend({0.0: metrics(0.10), 0.2: metrics(0.25), 0.5: metrics(0.20)})

        self.assertEqual(0.2, selected)

    def test_prefers_less_popularity_when_validation_metrics_tie(self):
        tied = RankingMetrics(10, 0.1, 0.2, 0.1, 0.2, 0.1)

        self.assertEqual(0.0, choose_blend({0.0: tied, 0.2: tied}))


class CollaborativeAggregationTest(unittest.TestCase):
    def test_multiple_supporting_history_items_raise_a_candidate(self):
        positives = [
            Rating(1, 1, 5.0, 1),
            Rating(1, 2, 5.0, 2),
            Rating(2, 1, 5.0, 1),
            Rating(2, 3, 5.0, 2),
            Rating(3, 2, 5.0, 1),
            Rating(3, 3, 5.0, 2),
            Rating(4, 1, 5.0, 1),
            Rating(4, 4, 5.0, 2),
        ]
        model = ItemKnnModel(positives, neighbors_per_item=3, profile_limit=10, candidate_popularity_limit=4)

        ranking = model.recommend(1, popularity_weight=0.0, limit=2)

        self.assertEqual(3, ranking[0])


class IndependentAuditTest(unittest.TestCase):
    def test_loads_movielens_100k_tab_separated_rows(self):
        from tempfile import TemporaryDirectory

        with TemporaryDirectory() as temporary:
            path = Path(temporary) / "u.data"
            path.write_text("7\t42\t4\t881250949\n", encoding="ascii")

            self.assertEqual([Rating(7, 42, 4.0, 881250949)], load_ratings(path))

    def test_relative_change_is_explicit_when_baseline_is_zero(self):
        self.assertAlmostEqual(0.5, relative_change(0.3, 0.2))
        self.assertIsNone(relative_change(0.3, 0.0))


if __name__ == "__main__":
    unittest.main()
