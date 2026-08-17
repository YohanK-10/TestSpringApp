package com.atlasmind.atlaswatch.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Offline evaluation metrics for the recommendation engine.
 * <p>
 * Computes standard information-retrieval metrics to quantify recommendation
 * quality across users:
 * <ul>
 *   <li><b>Hit rate</b> - fraction of users where at least one relevant movie
 *       appears in the recommendation list</li>
 *   <li><b>Precision@K</b> - fraction of recommended movies that are relevant,
 *       averaged across users</li>
 *   <li><b>Recall@K</b> - fraction of known relevant movies retrieved</li>
 *   <li><b>MRR</b> - rewards placing the first relevant result near the top</li>
 *   <li><b>nDCG@K</b> - rank-sensitive quality with optional graded labels</li>
 *   <li><b>Catalog coverage</b> - fraction of the catalog that appears in at
 *       least one user's recommendations</li>
 *   <li><b>Genre diversity</b> - ratio of unique genres to total genre slots in
 *       a recommendation list (higher = more varied)</li>
 *   <li><b>Intra-list similarity</b> - average pairwise Jaccard similarity
 *       within a list (lower = more diverse)</li>
 * </ul>
 * <p>
 * Ground-truth labels: a movie is relevant when the user later reviews it
 * positively (rating >= 7) or adds it to their watchlist.
 *
 * @see RecommendationService
 */
class RecommendationEvaluator {

    record RecommendationConstraints(
            boolean moodSatisfied,
            boolean runtimeSatisfied,
            boolean eraSatisfied
    ) {
        static RecommendationConstraints allSatisfied() {
            return new RecommendationConstraints(true, true, true);
        }
    }

    record RecommendedMovie(
            long movieId,
            List<String> genres,
            RecommendationConstraints constraints
    ) {
        RecommendedMovie(long movieId, List<String> genres) {
            this(movieId, genres, RecommendationConstraints.allSatisfied());
        }

        RecommendedMovie {
            genres = genres == null ? List.of() : List.copyOf(genres);
            constraints = constraints == null
                    ? RecommendationConstraints.allSatisfied()
                    : constraints;
        }
    }

    record UserEvaluation(
            long userId,
            List<RecommendedMovie> recommendations,
            Set<Long> relevantMovieIds,
            Map<Long, Integer> relevanceGrades
    ) {
        UserEvaluation(long userId, List<RecommendedMovie> recommendations, Set<Long> relevantMovieIds) {
            this(userId, recommendations, relevantMovieIds, binaryGrades(relevantMovieIds));
        }

        UserEvaluation {
            recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
            relevanceGrades = relevanceGrades == null ? Map.of() : sanitizeGrades(relevanceGrades);
            Set<Long> normalizedRelevantIds = new LinkedHashSet<>(
                    relevantMovieIds == null ? Set.of() : relevantMovieIds);
            relevanceGrades.forEach((movieId, grade) -> {
                if (grade > 0) {
                    normalizedRelevantIds.add(movieId);
                } else {
                    normalizedRelevantIds.remove(movieId);
                }
            });
            relevantMovieIds = Set.copyOf(normalizedRelevantIds);
        }

        private static Map<Long, Integer> binaryGrades(Set<Long> relevantMovieIds) {
            if (relevantMovieIds == null || relevantMovieIds.isEmpty()) {
                return Map.of();
            }
            Map<Long, Integer> grades = new LinkedHashMap<>();
            relevantMovieIds.forEach(movieId -> grades.put(movieId, 1));
            return Map.copyOf(grades);
        }

        private static Map<Long, Integer> sanitizeGrades(Map<Long, Integer> grades) {
            Map<Long, Integer> sanitized = new LinkedHashMap<>();
            grades.forEach((movieId, grade) -> {
                if (movieId != null && grade != null) {
                    sanitized.put(movieId, Math.max(0, grade));
                }
            });
            return Map.copyOf(sanitized);
        }

        int relevanceGrade(long movieId) {
            return relevanceGrades.getOrDefault(movieId, relevantMovieIds.contains(movieId) ? 1 : 0);
        }
    }

    record EvaluationReport(
            double hitRate,
            double meanPrecisionAtK,
            double meanRecallAtK,
            double meanReciprocalRank,
            double meanNdcgAtK,
            double catalogCoverage,
            double meanGenreDiversity,
            double meanIntraListSimilarity,
            double moodConstraintViolationRate,
            double runtimeConstraintViolationRate,
            double eraConstraintViolationRate,
            int usersEvaluated,
            int totalHits,
            int totalRecommendations,
            int uniqueMoviesRecommended,
            int catalogSize
    ) {
        String toFormattedReport() {
            return String.format("""
                    === Recommendation Evaluation Report ===
                    Users evaluated:           %d
                    Total recommendations:     %d

                    Relevance:
                      Hit rate:                %.1f%% (%d/%d users had >=1 hit)
                      Mean Precision@K:        %.4f
                      Mean Recall@K:           %.4f
                      Mean reciprocal rank:   %.4f
                      Mean nDCG@K:             %.4f

                    Coverage:
                      Catalog coverage:        %.1f%% (%d/%d movies)

                    Diversity:
                      Mean genre diversity:    %.4f (1.0 = all genres unique)
                      Mean intra-list sim:     %.4f (0.0 = max diversity)

                    Constraint violations:
                      Mood:                    %.1f%%
                      Runtime:                 %.1f%%
                      Era:                     %.1f%%
                    """,
                    usersEvaluated, totalRecommendations,
                    hitRate * 100, totalHits, usersEvaluated,
                    meanPrecisionAtK,
                    meanRecallAtK,
                    meanReciprocalRank,
                    meanNdcgAtK,
                    catalogCoverage * 100, uniqueMoviesRecommended, catalogSize,
                    meanGenreDiversity,
                    meanIntraListSimilarity,
                    moodConstraintViolationRate * 100,
                    runtimeConstraintViolationRate * 100,
                    eraConstraintViolationRate * 100);
        }
    }

    /**
     * Compute evaluation metrics across multiple users.
     *
     * @param evaluations per-user evaluation data
     * @param catalogSize total number of recommendation-ready movies
     */
    static EvaluationReport evaluate(List<UserEvaluation> evaluations, int catalogSize) {
        if (evaluations.isEmpty()) {
            return new EvaluationReport(
                    0, 0, 0, 0, 0,
                    0, 0, 0,
                    0, 0, 0,
                    0, 0, 0, 0, catalogSize);
        }

        int hits = 0;
        double precisionSum = 0;
        double recallSum = 0;
        double reciprocalRankSum = 0;
        double ndcgSum = 0;
        double diversitySum = 0;
        double ilsSum = 0;
        int totalRecommendations = 0;
        int moodViolations = 0;
        int runtimeViolations = 0;
        int eraViolations = 0;
        Set<Long> allRecommendedMovieIds = new LinkedHashSet<>();

        for (UserEvaluation eval : evaluations) {
            List<RecommendedMovie> recs = eval.recommendations();
            totalRecommendations += recs.size();
            recs.stream().map(RecommendedMovie::movieId).forEach(allRecommendedMovieIds::add);

            boolean hasHit = recs.stream()
                    .anyMatch(rec -> eval.relevantMovieIds().contains(rec.movieId()));
            if (hasHit) {
                hits++;
            }

            long relevantInRecs = recs.stream()
                    .filter(rec -> eval.relevantMovieIds().contains(rec.movieId()))
                    .count();
            precisionSum += recs.isEmpty() ? 0 : (double) relevantInRecs / recs.size();
            recallSum += eval.relevantMovieIds().isEmpty()
                    ? 0
                    : (double) relevantInRecs / eval.relevantMovieIds().size();
            reciprocalRankSum += reciprocalRank(eval);
            ndcgSum += normalizedDiscountedCumulativeGain(eval);

            diversitySum += genreDiversity(recs);
            ilsSum += intraListSimilarity(recs);
            moodViolations += (int) recs.stream()
                    .filter(rec -> !rec.constraints().moodSatisfied()).count();
            runtimeViolations += (int) recs.stream()
                    .filter(rec -> !rec.constraints().runtimeSatisfied()).count();
            eraViolations += (int) recs.stream()
                    .filter(rec -> !rec.constraints().eraSatisfied()).count();
        }

        int n = evaluations.size();
        return new EvaluationReport(
                (double) hits / n,
                precisionSum / n,
                recallSum / n,
                reciprocalRankSum / n,
                ndcgSum / n,
                catalogSize > 0 ? (double) allRecommendedMovieIds.size() / catalogSize : 0,
                diversitySum / n,
                ilsSum / n,
                totalRecommendations == 0 ? 0 : (double) moodViolations / totalRecommendations,
                totalRecommendations == 0 ? 0 : (double) runtimeViolations / totalRecommendations,
                totalRecommendations == 0 ? 0 : (double) eraViolations / totalRecommendations,
                n,
                hits,
                totalRecommendations,
                allRecommendedMovieIds.size(),
                catalogSize
        );
    }

    /** Reciprocal rank of the first relevant result. */
    static double reciprocalRank(UserEvaluation evaluation) {
        for (int index = 0; index < evaluation.recommendations().size(); index++) {
            if (evaluation.relevantMovieIds().contains(evaluation.recommendations().get(index).movieId())) {
                return 1.0 / (index + 1);
            }
        }
        return 0;
    }

    /**
     * Normalized discounted cumulative gain over the returned list. Labels may
     * use 0 (irrelevant) through any positive integer; 0-3 is recommended.
     */
    static double normalizedDiscountedCumulativeGain(UserEvaluation evaluation) {
        if (evaluation.recommendations().isEmpty()) {
            return 0;
        }

        int k = evaluation.recommendations().size();
        double dcg = 0;
        for (int index = 0; index < k; index++) {
            int grade = evaluation.relevanceGrade(evaluation.recommendations().get(index).movieId());
            dcg += discountedGain(grade, index);
        }

        List<Integer> idealGrades = new ArrayList<>(evaluation.relevanceGrades().values());
        evaluation.relevantMovieIds().stream()
                .filter(movieId -> !evaluation.relevanceGrades().containsKey(movieId))
                .forEach(movieId -> idealGrades.add(1));
        idealGrades.sort(java.util.Comparator.reverseOrder());

        double idealDcg = 0;
        for (int index = 0; index < Math.min(k, idealGrades.size()); index++) {
            idealDcg += discountedGain(idealGrades.get(index), index);
        }
        return idealDcg == 0 ? 0 : dcg / idealDcg;
    }

    private static double discountedGain(int relevanceGrade, int zeroBasedRank) {
        if (relevanceGrade <= 0) {
            return 0;
        }
        double gain = Math.pow(2, relevanceGrade) - 1;
        return gain / (Math.log(zeroBasedRank + 2) / Math.log(2));
    }

    /**
     * Genre diversity: unique genres / total genre slots.
     * A list where every movie has completely different genres scores 1.0.
     */
    static double genreDiversity(List<RecommendedMovie> recommendations) {
        if (recommendations.isEmpty()) {
            return 0;
        }

        Set<String> uniqueGenres = recommendations.stream()
                .flatMap(rec -> rec.genres().stream())
                .collect(Collectors.toSet());
        long totalSlots = recommendations.stream()
                .mapToLong(rec -> rec.genres().size())
                .sum();
        return totalSlots == 0 ? 0 : (double) uniqueGenres.size() / totalSlots;
    }

    /**
     * Intra-list similarity: average pairwise Jaccard similarity
     * of genre sets within the list. 0 means maximum diversity.
     */
    static double intraListSimilarity(List<RecommendedMovie> recommendations) {
        if (recommendations.size() < 2) {
            return 0;
        }

        double totalSimilarity = 0;
        int pairs = 0;
        for (int i = 0; i < recommendations.size(); i++) {
            for (int j = i + 1; j < recommendations.size(); j++) {
                totalSimilarity += jaccardSimilarity(
                        recommendations.get(i).genres(),
                        recommendations.get(j).genres()
                );
                pairs++;
            }
        }
        return pairs == 0 ? 0 : totalSimilarity / pairs;
    }

    /**
     * Jaccard similarity between two genre lists.
     */
    static double jaccardSimilarity(List<String> a, List<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0;
        }
        Set<String> setA = new HashSet<>(a);
        Set<String> setB = new HashSet<>(b);
        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }
}
