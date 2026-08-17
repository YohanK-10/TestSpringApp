package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.models.Review;
import com.atlasmind.atlaswatch.models.WatchList;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class UserTasteProfileService {

    private static final double REVIEW_TEXT_CONFIDENCE_BONUS = 0.25;
    private static final double WATCHLIST_SIGNAL_WEIGHT = 0.45;
    private static final double SIGNAL_SATURATION = 1.0;
    private static final double STARTER_GENRE_SIGNAL_WEIGHT = 1.0;
    private static final double STARTER_KEYWORD_SIGNAL_WEIGHT = 0.9;
    private static final double STARTER_SEED_GENRE_SIGNAL_WEIGHT = 0.8;
    private static final double STARTER_SEED_KEYWORD_SIGNAL_WEIGHT = 1.1;

    /**
     * Half-life for recency decay (in days). A review/watchlist entry from this
     * many days ago contributes half its original weight.
     */
    static final double RECENCY_HALF_LIFE_DAYS = 90.0;

    /**
     * Floor for the recency multiplier. Even very old entries contribute at
     * least this fraction of their original weight, preventing the profile from
     * completely ignoring a user's long-term preferences.
     */
    static final double RECENCY_FLOOR = 0.10;

    public UserTasteProfile buildProfile(
            List<Review> reviews,
            Collection<WatchList> watchlistEntries,
            Map<Long, MovieSignalFeatures> signalFeaturesByMovieId
    ) {
        Map<String, Double> positiveGenreSignals = new HashMap<>();
        Map<String, Double> negativeGenreSignals = new HashMap<>();
        Map<String, Double> positiveKeywordSignals = new HashMap<>();
        Map<String, Double> negativeKeywordSignals = new HashMap<>();
        int reviewSignalCount = 0;
        int watchlistSignalCount = 0;

        LocalDateTime now = LocalDateTime.now();

        if (reviews != null) {
            for (Review review : reviews) {
                MovieSignalFeatures signalFeatures = signalFeaturesByMovieId.getOrDefault(movieId(review), MovieSignalFeatures.EMPTY);
                List<String> genres = normalizedGenres(signalFeatures.genres());
                List<String> keywords = normalizedKeywords(signalFeatures.keywords());
                if (genres.isEmpty() && keywords.isEmpty()) {
                    continue;
                }

                double signal = reviewSignal(review);
                double recency = recencyMultiplier(review.getCreatedAt(), now);
                double weightedSignal = signal * recency;

                if (weightedSignal > 0) {
                    distributeSignal(positiveGenreSignals, genres, weightedSignal);
                    distributeSignal(positiveKeywordSignals, keywords, weightedSignal);
                    reviewSignalCount++;
                } else if (weightedSignal < 0) {
                    distributeSignal(negativeGenreSignals, genres, Math.abs(weightedSignal));
                    distributeSignal(negativeKeywordSignals, keywords, Math.abs(weightedSignal));
                    reviewSignalCount++;
                }
            }
        }

        if (watchlistEntries != null) {
            for (WatchList watchlistEntry : watchlistEntries) {
                MovieSignalFeatures signalFeatures = signalFeaturesByMovieId.getOrDefault(
                        movieId(watchlistEntry),
                        MovieSignalFeatures.EMPTY
                );
                List<String> genres = normalizedGenres(signalFeatures.genres());
                List<String> keywords = normalizedKeywords(signalFeatures.keywords());
                if (genres.isEmpty() && keywords.isEmpty()) {
                    continue;
                }

                double recency = recencyMultiplier(watchlistEntry.getAddedAt(), now);
                distributeSignal(positiveGenreSignals, genres, WATCHLIST_SIGNAL_WEIGHT * recency);
                distributeSignal(positiveKeywordSignals, keywords, WATCHLIST_SIGNAL_WEIGHT * recency);
                watchlistSignalCount++;
            }
        }

        Map<String, Double> positiveGenreWeights = scaleSignals(positiveGenreSignals);
        Map<String, Double> negativeGenreWeights = scaleSignals(negativeGenreSignals);
        Map<String, Double> netGenreWeights = buildNetWeights(positiveGenreWeights, negativeGenreWeights);
        Map<String, Double> positiveKeywordWeights = scaleSignals(positiveKeywordSignals);
        Map<String, Double> negativeKeywordWeights = scaleSignals(negativeKeywordSignals);
        Map<String, Double> netKeywordWeights = buildNetWeights(positiveKeywordWeights, negativeKeywordWeights);

        return new UserTasteProfile(
                positiveGenreWeights,
                negativeGenreWeights,
                netGenreWeights,
                positiveKeywordWeights,
                negativeKeywordWeights,
                netKeywordWeights,
                reviewSignalCount,
                watchlistSignalCount
        );
    }

    public UserTasteProfile buildBootstrapProfile(
            Collection<String> starterGenres,
            Collection<String> starterKeywords,
            Collection<MovieSignalFeatures> starterSeedFeatures
    ) {
        Map<String, Double> positiveGenreSignals = new HashMap<>();
        Map<String, Double> positiveKeywordSignals = new HashMap<>();

        distributeSignal(positiveGenreSignals, normalizedValues(toList(starterGenres)), STARTER_GENRE_SIGNAL_WEIGHT);
        distributeSignal(positiveKeywordSignals, normalizedValues(toList(starterKeywords)), STARTER_KEYWORD_SIGNAL_WEIGHT);

        if (starterSeedFeatures != null) {
            for (MovieSignalFeatures seedFeatures : starterSeedFeatures) {
                if (seedFeatures == null) {
                    continue;
                }
                distributeSignal(
                        positiveGenreSignals,
                        normalizedGenres(seedFeatures.genres()),
                        STARTER_SEED_GENRE_SIGNAL_WEIGHT
                );
                distributeSignal(
                        positiveKeywordSignals,
                        normalizedKeywords(seedFeatures.keywords()),
                        STARTER_SEED_KEYWORD_SIGNAL_WEIGHT
                );
            }
        }

        Map<String, Double> positiveGenreWeights = scaleSignals(positiveGenreSignals);
        Map<String, Double> positiveKeywordWeights = scaleSignals(positiveKeywordSignals);
        return new UserTasteProfile(
                positiveGenreWeights,
                Map.of(),
                buildNetWeights(positiveGenreWeights, Map.of()),
                positiveKeywordWeights,
                Map.of(),
                buildNetWeights(positiveKeywordWeights, Map.of()),
                0,
                0
        );
    }

    private double reviewSignal(Review review) {
        if (review == null || review.getRating() == null) {
            return 0.0;
        }

        double centeredRating = review.getRating() - 5.5;
        if (centeredRating == 0.0) {
            return 0.0;
        }

        double confidenceBonus = hasReviewText(review) ? REVIEW_TEXT_CONFIDENCE_BONUS : 0.0;
        if (centeredRating > 0) {
            return centeredRating + confidenceBonus;
        }

        return centeredRating - (confidenceBonus * 0.5);
    }

    private boolean hasReviewText(Review review) {
        return review.getReviewText() != null && !review.getReviewText().isBlank();
    }

    private void distributeSignal(Map<String, Double> target, List<String> genres, double weight) {
        if (weight <= 0 || genres.isEmpty()) {
            return;
        }

        double perGenreWeight = weight / genres.size();
        for (String genre : genres) {
            target.merge(genre, perGenreWeight, Double::sum);
        }
    }

    private Map<String, Double> scaleSignals(Map<String, Double> rawSignals) {
        if (rawSignals.isEmpty()) {
            return Map.of();
        }

        return rawSignals.entrySet().stream()
                .collect(LinkedHashMap::new,
                        (map, entry) -> map.put(entry.getKey(), scaledSignal(entry.getValue())),
                        LinkedHashMap::putAll);
    }

    private double scaledSignal(double rawSignal) {
        if (rawSignal <= 0.0) {
            return 0.0;
        }

        // Soft saturation keeps weights bounded in [0, 1] without erasing
        // absolute recency differences for isolated older signals.
        return rawSignal / (rawSignal + SIGNAL_SATURATION);
    }

    private Map<String, Double> buildNetWeights(
            Map<String, Double> positiveGenreWeights,
            Map<String, Double> negativeGenreWeights
    ) {
        Set<String> allGenres = new LinkedHashSet<>();
        allGenres.addAll(positiveGenreWeights.keySet());
        allGenres.addAll(negativeGenreWeights.keySet());

        if (allGenres.isEmpty()) {
            return Map.of();
        }

        Map<String, Double> netWeights = new LinkedHashMap<>();
        for (String genre : allGenres) {
            netWeights.put(
                    genre,
                    positiveGenreWeights.getOrDefault(genre, 0.0) - negativeGenreWeights.getOrDefault(genre, 0.0)
            );
        }
        return netWeights;
    }

    private List<String> normalizedGenres(List<String> genres) {
        return normalizedValues(genres);
    }

    private List<String> normalizedKeywords(List<String> keywords) {
        return normalizedValues(keywords);
    }

    private List<String> normalizedValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        return values.stream()
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private List<String> toList(Collection<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private Long movieId(Review review) {
        return review == null || review.getMovie() == null ? null : review.getMovie().getId();
    }

    private Long movieId(WatchList watchlistEntry) {
        return watchlistEntry == null || watchlistEntry.getMovie() == null ? null : watchlistEntry.getMovie().getId();
    }

    /**
     * Exponential half-life decay: {@code max(RECENCY_FLOOR, 2^(-ageDays / halfLife))}.
     * Returns 1.0 for timestamps equal to {@code now} or in the future, and
     * decays smoothly toward {@link #RECENCY_FLOOR} for older timestamps.
     * A {@code null} timestamp is treated as "age unknown" and returns 1.0
     * so the signal is not silently dropped.
     */
    double recencyMultiplier(LocalDateTime timestamp, LocalDateTime now) {
        if (timestamp == null || !timestamp.isBefore(now)) {
            return 1.0;
        }
        long ageDays = ChronoUnit.DAYS.between(timestamp, now);
        double decay = Math.pow(2.0, -ageDays / RECENCY_HALF_LIFE_DAYS);
        return Math.max(RECENCY_FLOOR, decay);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
