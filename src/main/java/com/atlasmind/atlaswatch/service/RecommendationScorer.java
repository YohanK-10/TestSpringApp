package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.config.RecommendationScoringProperties;
import com.atlasmind.atlaswatch.models.Movie;
import com.atlasmind.atlaswatch.models.WatchList;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RecommendationScorer {

    private final RecommendationScoringProperties scoringProperties;

    static final double MIN_RECOMMENDATION_RATING = 5.5;
    static final int MIN_RECOMMENDATION_RUNTIME = 70;
    static final double MIN_CONTENT_SIMILARITY_SCORE = 0.08;
    private static final double QUALITY_SAMPLING_WEIGHT = 0.70;
    private static final double POPULARITY_SAMPLING_WEIGHT = 0.30;
    private static final double FRESHNESS_SAMPLING_WEIGHT = 0.0;
    private static final double MIN_SAMPLING_WEIGHT = 0.05;

    // -------------------------------------------------------------------------
    // Hard filters
    // -------------------------------------------------------------------------

    boolean isRecommendationReady(Movie movie) {
        return movie != null
                && movie.getId() != null
                && movie.getMovieRating() != null
                && movie.getMovieRating() >= MIN_RECOMMENDATION_RATING
                && movie.getRuntime() != null
                && movie.getRuntime() >= MIN_RECOMMENDATION_RUNTIME
                && movie.getReleaseDate() != null
                && movie.getPosterPath() != null
                && !movie.getPosterPath().isBlank()
                && movie.getOverview() != null
                && !movie.getOverview().isBlank();
    }

    boolean isRetrievableCandidate(Movie movie, Set<Long> excludedMovieIds) {
        return isRecommendationReady(movie)
                && (excludedMovieIds == null || !excludedMovieIds.contains(movie.getId()));
    }

    boolean hasUsableOverview(Movie movie) {
        return movie != null
                && movie.getId() != null
                && movie.getOverview() != null
                && !movie.getOverview().isBlank()
                && isRecommendationReady(movie);
    }

    boolean passesScoringHardFilters(Movie movie, RuntimePreference runtimePreference) {
        if (movie == null || movie.getId() == null) {
            return false;
        }
        if (movie.getMovieRating() != null && movie.getMovieRating() < MIN_RECOMMENDATION_RATING) {
            return false;
        }
        if (runtimePreference != RuntimePreference.ANY
                && !runtimePreference.passesHardFilter(movie.getRuntime())) {
            return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Feature computation
    // -------------------------------------------------------------------------

    RankingFeatures buildWatchlistFeatures(
            Movie movie,
            WatchList entry,
            Set<SoloMood> moods,
            RuntimePreference runtimePreference,
            UserTasteProfile tasteProfile,
            List<String> genres,
            List<String> keywords
    ) {
        return new RankingFeatures(
                preferenceAffinityScore(tasteProfile, genres, keywords),
                dislikedPreferencePenaltyScore(tasteProfile, genres, keywords),
                moodMatchScore(moods, genres, keywords, movie.getOverview()),
                runtimeMatchScore(runtimePreference, movie.getRuntime()),
                qualityScore(movie),
                popularityScore(movie.getPopularity()),
                0.0,
                freshnessScore(movie.getReleaseDate()),
                watchlistAgeScore(entry),
                0.0,
                0.0
        );
    }

    RankingFeatures buildCatalogFeatures(
            Movie movie,
            WatchList watchlistEntry,
            Set<SoloMood> moods,
            RuntimePreference runtimePreference,
            UserTasteProfile tasteProfile,
            List<String> genres,
            List<String> keywords,
            int sourceCount,
            double collaborativeScore
    ) {
        return new RankingFeatures(
                preferenceAffinityScore(tasteProfile, genres, keywords),
                dislikedPreferencePenaltyScore(tasteProfile, genres, keywords),
                moodMatchScore(moods, genres, keywords, movie.getOverview()),
                runtimeMatchScore(runtimePreference, movie.getRuntime()),
                qualityScore(movie),
                popularityScore(movie.getPopularity()),
                watchlistEntry != null ? 1.0 : 0.0,
                freshnessScore(movie.getReleaseDate()),
                0.0,
                sourceCountScore(sourceCount),
                clamp01(collaborativeScore)
        );
    }

    double genreAffinityScore(UserTasteProfile tasteProfile, List<String> genres) {
        if (tasteProfile == null || !tasteProfile.hasSignals() || genres.isEmpty()) {
            return 0.0;
        }
        List<Double> positiveSignals = genres.stream()
                .map(this::normalize)
                .distinct()
                .map(genre -> Math.max(0.0, tasteProfile.netWeight(genre)))
                .filter(weight -> weight > 0.0)
                .toList();
        if (positiveSignals.isEmpty()) {
            return 0.0;
        }
        return clamp01(positiveSignals.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
    }

    double keywordAffinityScore(UserTasteProfile tasteProfile, List<String> keywords) {
        if (tasteProfile == null || !tasteProfile.hasSignals() || keywords.isEmpty()) {
            return 0.0;
        }
        List<Double> positiveSignals = keywords.stream()
                .map(this::normalize)
                .distinct()
                .map(keyword -> Math.max(0.0, tasteProfile.netKeywordWeight(keyword)))
                .filter(weight -> weight > 0.0)
                .toList();
        if (positiveSignals.isEmpty()) {
            return 0.0;
        }
        return clamp01(positiveSignals.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
    }

    double dislikedGenrePenaltyScore(UserTasteProfile tasteProfile, List<String> genres) {
        if (tasteProfile == null || !tasteProfile.hasSignals() || genres.isEmpty()) {
            return 0.0;
        }
        List<Double> penaltySignals = genres.stream()
                .map(this::normalize)
                .distinct()
                .map(genre -> Math.max(
                        tasteProfile.negativeWeight(genre),
                        Math.abs(Math.min(0.0, tasteProfile.netWeight(genre)))
                ))
                .filter(weight -> weight >= scoringProperties.getStrongDislikeThreshold())
                .toList();
        if (penaltySignals.isEmpty()) {
            return 0.0;
        }
        return clamp01(penaltySignals.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
    }

    double dislikedKeywordPenaltyScore(UserTasteProfile tasteProfile, List<String> keywords) {
        if (tasteProfile == null || !tasteProfile.hasSignals() || keywords.isEmpty()) {
            return 0.0;
        }
        List<Double> penaltySignals = keywords.stream()
                .map(this::normalize)
                .distinct()
                .map(keyword -> Math.max(
                        tasteProfile.negativeKeywordWeight(keyword),
                        Math.abs(Math.min(0.0, tasteProfile.netKeywordWeight(keyword)))
                ))
                .filter(weight -> weight >= scoringProperties.getStrongDislikeThreshold())
                .toList();
        if (penaltySignals.isEmpty()) {
            return 0.0;
        }
        return clamp01(penaltySignals.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
    }

    double preferenceAffinityScore(UserTasteProfile tasteProfile, List<String> genres, List<String> keywords) {
        return combineSignalScores(
                genreAffinityScore(tasteProfile, genres),
                keywordAffinityScore(tasteProfile, keywords)
        );
    }

    double dislikedPreferencePenaltyScore(UserTasteProfile tasteProfile, List<String> genres, List<String> keywords) {
        return combineSignalScores(
                dislikedGenrePenaltyScore(tasteProfile, genres),
                dislikedKeywordPenaltyScore(tasteProfile, keywords)
        );
    }

    double moodMatchScore(Set<SoloMood> moods, List<String> genres) {
        return moodMatchScore(moods, genres, List.of(), null);
    }

    double moodMatchScore(
            Set<SoloMood> moods,
            List<String> genres,
            List<String> keywords,
            String overview
    ) {
        if (!hasMoodIntent(moods)) {
            return 0.0;
        }

        List<SoloMood> requestedMoods = moods.stream()
                .filter(mood -> mood != SoloMood.ANY)
                .toList();
        if (requestedMoods.isEmpty()) {
            return 0.0;
        }

        List<String> safeGenres = genres == null ? List.of() : genres;
        List<String> safeKeywords = keywords == null ? List.of() : keywords;
        long coveredMoods = requestedMoods.stream()
                .filter(mood -> mood.isCovered(safeGenres, safeKeywords, overview))
                .count();
        return clamp01((double) coveredMoods / requestedMoods.size());
    }

    List<SoloMood> matchingMoods(
            Set<SoloMood> moods,
            List<String> genres,
            List<String> keywords,
            String overview
    ) {
        if (!hasMoodIntent(moods)) {
            return List.of();
        }
        List<String> safeGenres = genres == null ? List.of() : genres;
        List<String> safeKeywords = keywords == null ? List.of() : keywords;
        return moods.stream()
                .filter(mood -> mood != SoloMood.ANY)
                .filter(mood -> mood.isCovered(safeGenres, safeKeywords, overview))
                .toList();
    }

    double runtimeMatchScore(RuntimePreference runtimePreference, Integer runtime) {
        return runtimePreference.score(runtime);
    }

    double qualityScore(Movie movie) {
        if (movie == null || movie.getMovieRating() == null) {
            return 0.0;
        }
        return clamp01((confidenceAdjustedRating(movie) - MIN_RECOMMENDATION_RATING)
                / (10.0 - MIN_RECOMMENDATION_RATING));
    }

    double confidenceAdjustedRating(Movie movie) {
        if (movie == null || movie.getMovieRating() == null) {
            return 0.0;
        }

        double rating = Math.max(0.0, Math.min(10.0, movie.getMovieRating()));
        double priorMean = Math.max(0.0, Math.min(10.0, scoringProperties.getQualityPriorMean()));
        double priorWeight = Math.max(0.0, scoringProperties.getQualityPriorWeight());
        double votes = movie.getVoteCount() == null ? 0.0 : Math.max(0, movie.getVoteCount());

        if (priorWeight == 0.0) {
            return rating;
        }
        return ((votes * rating) + (priorWeight * priorMean)) / (votes + priorWeight);
    }

    double popularityScore(Double popularity) {
        if (popularity == null || popularity <= 0) {
            return 0.0;
        }
        return clamp01(popularity / scoringProperties.getPopularitySaturation());
    }

    double freshnessScore(LocalDate releaseDate) {
        if (releaseDate == null) {
            return 0.0;
        }
        long daysOld = Math.max(0, ChronoUnit.DAYS.between(releaseDate, LocalDate.now()));
        double yearsOld = daysOld / 365.25;
        return clamp01(1.0 - (yearsOld / scoringProperties.getFreshnessWindowYears()));
    }

    double watchlistAgeScore(WatchList entry) {
        if (entry == null || entry.getAddedAt() == null) {
            return 0.0;
        }
        long daysOnWatchlist = Math.max(0, Duration.between(entry.getAddedAt(), LocalDateTime.now()).toDays());
        return clamp01((double) daysOnWatchlist / scoringProperties.getWatchlistAgeSaturationDays());
    }

    double sourceCountScore(int sourceCount) {
        if (sourceCount <= 1) {
            return 0.0;
        }
        return clamp01((sourceCount - 1.0) / 4.0);
    }

    double watchlistSeedScore(
            Movie movie,
            UserTasteProfile tasteProfile,
            Map<Long, MovieSignalFeatures> signalFeaturesByMovieId
    ) {
        if (movie == null) {
            return 0.0;
        }
        MovieSignalFeatures signalFeatures = signalFeaturesByMovieId.getOrDefault(movie.getId(), MovieSignalFeatures.EMPTY);
        return preferenceAffinityScore(tasteProfile, signalFeatures.genres(), signalFeatures.keywords())
                + qualityScore(movie);
    }

    double samplingWeight(PreparedCandidate candidate) {
        Movie movie = candidate.movie();
        double weightedScore =
                (QUALITY_SAMPLING_WEIGHT * qualityScore(movie))
                + (POPULARITY_SAMPLING_WEIGHT * popularityScore(movie.getPopularity()))
                + (FRESHNESS_SAMPLING_WEIGHT * freshnessScore(movie.getReleaseDate()))
                + candidate.contentSimilarityScore();
        return Math.max(MIN_SAMPLING_WEIGHT, weightedScore);
    }

    double positiveGenreReasonThreshold() {
        return scoringProperties.getPositiveGenreReasonThreshold();
    }

    double qualityPriorMean() {
        return scoringProperties.getQualityPriorMean();
    }

    double qualityPriorWeight() {
        return scoringProperties.getQualityPriorWeight();
    }

    double combineSignalScores(double primaryScore, double secondaryScore) {
        boolean hasPrimary = primaryScore > 0.0;
        boolean hasSecondary = secondaryScore > 0.0;
        if (hasPrimary && hasSecondary) {
            return clamp01((primaryScore * 0.6) + (secondaryScore * 0.4));
        }
        if (hasPrimary) {
            return clamp01(primaryScore);
        }
        if (hasSecondary) {
            return clamp01(secondaryScore);
        }
        return 0.0;
    }

    // -------------------------------------------------------------------------
    // Weighted score
    // -------------------------------------------------------------------------

    double computeWeightedScore(
            RankingFeatures features,
            boolean includeTaste,
            boolean includeMood,
            boolean includeRuntime,
            boolean includeWatchlistBoost,
            boolean includeWatchlistAge,
            boolean includeSourceCount,
            boolean includeCollaborative
    ) {
        double weightedPositive = 0.0;
        double positiveWeightTotal = 0.0;

        if (includeTaste) {
            weightedPositive += scoringProperties.getGenreAffinityWeight() * features.tasteAffinity();
            positiveWeightTotal += scoringProperties.getGenreAffinityWeight();
        }
        if (includeMood) {
            weightedPositive += scoringProperties.getMoodMatchWeight() * features.moodMatch();
            positiveWeightTotal += scoringProperties.getMoodMatchWeight();
        }
        if (includeRuntime) {
            weightedPositive += scoringProperties.getRuntimeMatchWeight() * features.runtimeMatch();
            positiveWeightTotal += scoringProperties.getRuntimeMatchWeight();
        }
        if (includeWatchlistBoost) {
            weightedPositive += scoringProperties.getWatchlistBoostWeight() * features.watchlistBoost();
            positiveWeightTotal += scoringProperties.getWatchlistBoostWeight();
        }
        if (includeWatchlistAge) {
            weightedPositive += scoringProperties.getWatchlistAgeWeight() * features.watchlistAge();
            positiveWeightTotal += scoringProperties.getWatchlistAgeWeight();
        }
        if (includeSourceCount) {
            weightedPositive += scoringProperties.getSourceCountWeight() * features.sourceCount();
            positiveWeightTotal += scoringProperties.getSourceCountWeight();
        }
        if (includeCollaborative) {
            weightedPositive += scoringProperties.getCollaborativeWeight() * features.collaborative();
            positiveWeightTotal += scoringProperties.getCollaborativeWeight();
        }

        weightedPositive += scoringProperties.getQualityWeight() * features.quality();
        weightedPositive += scoringProperties.getPopularityWeight() * features.popularity();
        weightedPositive += scoringProperties.getFreshnessWeight() * features.freshness();

        positiveWeightTotal += scoringProperties.getQualityWeight();
        positiveWeightTotal += scoringProperties.getPopularityWeight();
        positiveWeightTotal += scoringProperties.getFreshnessWeight();

        if (positiveWeightTotal <= 0) {
            return 0.0;
        }

        double normalizedPositive = weightedPositive / positiveWeightTotal;
        double penalizedScore = normalizedPositive
                - (scoringProperties.getDislikedGenrePenaltyWeight() * features.dislikedTastePenalty());
        return clamp01(penalizedScore);
    }

    // -------------------------------------------------------------------------
    // Diversity re-ranking
    // -------------------------------------------------------------------------

    <T> List<T> rerankForDiversity(
            List<T> rankedRecommendations,
            int limit,
            ToDoubleFunction<T> scoreExtractor,
            Function<T, List<String>> genresExtractor
    ) {
        if (rankedRecommendations == null || rankedRecommendations.isEmpty()) {
            return List.of();
        }
        if (rankedRecommendations.size() <= 1) {
            return rankedRecommendations.stream().limit(limit).toList();
        }

        List<T> remaining = new ArrayList<>(rankedRecommendations);
        List<T> selected = new ArrayList<>();

        while (!remaining.isEmpty() && selected.size() < limit) {
            T bestCandidate = null;
            double bestAdjustedScore = Double.NEGATIVE_INFINITY;

            for (T candidate : remaining) {
                double diversityPenalty = selected.stream()
                        .mapToDouble(existing -> genreSimilarity(
                                genresExtractor.apply(existing),
                                genresExtractor.apply(candidate)))
                        .max()
                        .orElse(0.0);

                double adjustedScore = scoreExtractor.applyAsDouble(candidate)
                        - (scoringProperties.getDiversityPenaltyWeight() * diversityPenalty);

                if (adjustedScore > bestAdjustedScore) {
                    bestAdjustedScore = adjustedScore;
                    bestCandidate = candidate;
                }
            }

            if (bestCandidate == null) {
                break;
            }

            selected.add(bestCandidate);
            remaining.remove(bestCandidate);
        }

        return selected;
    }

    <T> List<T> rerankForCalibration(
            List<T> rankedRecommendations,
            int limit,
            ToDoubleFunction<T> scoreExtractor,
            Function<T, List<String>> genresExtractor,
            Map<String, Double> targetGenreWeights
    ) {
        if (rankedRecommendations == null || rankedRecommendations.isEmpty()) {
            return List.of();
        }
        if (rankedRecommendations.size() <= 1 || limit <= 1) {
            return rankedRecommendations.stream().limit(limit).toList();
        }

        Map<String, Double> normalizedTarget = normalizeCalibrationTarget(rankedRecommendations, genresExtractor, targetGenreWeights);
        if (normalizedTarget.size() < 2) {
            return rankedRecommendations.stream().limit(limit).toList();
        }

        List<T> remaining = new ArrayList<>(rankedRecommendations);
        List<T> selected = new ArrayList<>();
        Map<String, Double> selectedGenreMass = new LinkedHashMap<>();
        double totalSelectedMass = 0.0;

        while (!remaining.isEmpty() && selected.size() < limit) {
            T bestCandidate = null;
            double bestAdjustedScore = Double.NEGATIVE_INFINITY;
            Map<String, Double> bestContribution = Map.of();

            for (T candidate : remaining) {
                Map<String, Double> candidateContribution = genreContribution(genresExtractor.apply(candidate));
                double candidateMass = totalMass(candidateContribution);
                double divergence = calibrationDivergence(
                        normalizedTarget,
                        selectedGenreMass,
                        totalSelectedMass,
                        candidateContribution,
                        candidateMass
                );
                double adjustedScore = scoreExtractor.applyAsDouble(candidate)
                        - (scoringProperties.getCalibrationPenaltyWeight() * divergence);
                if (adjustedScore > bestAdjustedScore) {
                    bestAdjustedScore = adjustedScore;
                    bestCandidate = candidate;
                    bestContribution = candidateContribution;
                }
            }

            if (bestCandidate == null) {
                break;
            }

            selected.add(bestCandidate);
            mergeGenreMass(selectedGenreMass, bestContribution);
            totalSelectedMass += totalMass(bestContribution);
            remaining.remove(bestCandidate);
        }

        return selected;
    }

    double genreSimilarity(List<String> leftGenres, List<String> rightGenres) {
        if (leftGenres == null || leftGenres.isEmpty() || rightGenres == null || rightGenres.isEmpty()) {
            return 0.0;
        }
        Set<String> left = leftGenres.stream()
                .map(this::normalize)
                .filter(genre -> !genre.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> right = rightGenres.stream()
                .map(this::normalize)
                .filter(genre -> !genre.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);
        if (intersection.isEmpty()) {
            return 0.0;
        }
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        return clamp01((double) intersection.size() / union.size());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    boolean hasMoodIntent(Set<SoloMood> moods) {
        return moods != null
                && !(moods.size() == 1 && moods.contains(SoloMood.ANY))
                && !moods.isEmpty();
    }

    List<String> matchingMoodGenres(Set<SoloMood> moods, List<String> genres) {
        if (!hasMoodIntent(moods) || genres.isEmpty()) {
            return List.of();
        }
        Set<String> preferredGenres = moods.stream()
                .filter(mood -> mood != SoloMood.ANY)
                .map(SoloMood::preferredGenres)
                .flatMap(Set::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return genres.stream()
                .map(this::normalize)
                .filter(preferredGenres::contains)
                .distinct()
                .toList();
    }

    String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private <T> Map<String, Double> normalizeCalibrationTarget(
            List<T> rankedRecommendations,
            Function<T, List<String>> genresExtractor,
            Map<String, Double> targetGenreWeights
    ) {
        if (targetGenreWeights == null || targetGenreWeights.isEmpty()) {
            return Map.of();
        }

        Set<String> availableGenres = rankedRecommendations.stream()
                .map(genresExtractor)
                .filter(genres -> genres != null && !genres.isEmpty())
                .flatMap(List::stream)
                .map(this::normalize)
                .filter(genre -> !genre.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (availableGenres.isEmpty()) {
            return Map.of();
        }

        Map<String, Double> filteredTarget = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : targetGenreWeights.entrySet()) {
            String genre = normalize(entry.getKey());
            double weight = entry.getValue() == null ? 0.0 : entry.getValue();
            if (!genre.isBlank() && weight > 0.0 && availableGenres.contains(genre)) {
                filteredTarget.put(genre, weight);
            }
        }
        double totalWeight = totalMass(filteredTarget);
        if (totalWeight <= 0.0) {
            return Map.of();
        }

        Map<String, Double> normalizedTarget = new LinkedHashMap<>();
        filteredTarget.forEach((genre, weight) -> normalizedTarget.put(genre, weight / totalWeight));
        return normalizedTarget;
    }

    private Map<String, Double> genreContribution(List<String> genres) {
        if (genres == null || genres.isEmpty()) {
            return Map.of();
        }

        List<String> normalizedGenres = genres.stream()
                .map(this::normalize)
                .filter(genre -> !genre.isBlank())
                .distinct()
                .toList();
        if (normalizedGenres.isEmpty()) {
            return Map.of();
        }

        double contribution = 1.0 / normalizedGenres.size();
        Map<String, Double> weights = new LinkedHashMap<>();
        normalizedGenres.forEach(genre -> weights.put(genre, contribution));
        return weights;
    }

    private void mergeGenreMass(Map<String, Double> aggregate, Map<String, Double> contribution) {
        contribution.forEach((genre, weight) -> aggregate.merge(genre, weight, Double::sum));
    }

    private double calibrationDivergence(
            Map<String, Double> targetDistribution,
            Map<String, Double> selectedGenreMass,
            double totalSelectedMass,
            Map<String, Double> candidateContribution,
            double candidateMass
    ) {
        if (targetDistribution.isEmpty()) {
            return 0.0;
        }

        double projectedTotalMass = totalSelectedMass + candidateMass;
        if (projectedTotalMass <= 0.0) {
            return 0.0;
        }

        Set<String> allGenres = new LinkedHashSet<>(targetDistribution.keySet());
        allGenres.addAll(selectedGenreMass.keySet());
        allGenres.addAll(candidateContribution.keySet());

        double divergence = 0.0;
        for (String genre : allGenres) {
            double target = targetDistribution.getOrDefault(genre, 0.0);
            double actual = (selectedGenreMass.getOrDefault(genre, 0.0)
                    + candidateContribution.getOrDefault(genre, 0.0)) / projectedTotalMass;
            double midpoint = (target + actual) / 2.0;
            if (target > 0.0) {
                divergence += 0.5 * target * log2(target / midpoint);
            }
            if (actual > 0.0) {
                divergence += 0.5 * actual * log2(actual / midpoint);
            }
        }

        return clamp01(divergence);
    }

    private double totalMass(Map<String, Double> weights) {
        return weights.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    double clamp01(double value) {
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }

    // -------------------------------------------------------------------------
    // RankingFeatures record (owned by scorer)
    // -------------------------------------------------------------------------

    record RankingFeatures(
            double tasteAffinity,
            double dislikedTastePenalty,
            double moodMatch,
            double runtimeMatch,
            double quality,
            double popularity,
            double watchlistBoost,
            double freshness,
            double watchlistAge,
            double sourceCount,
            double collaborative
    ) {
    }
}
