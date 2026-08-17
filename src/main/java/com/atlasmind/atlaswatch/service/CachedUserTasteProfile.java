package com.atlasmind.atlaswatch.service;

import java.util.Map;

/**
 * Redis-serializable wrapper around {@link UserTasteProfile}.
 * <p>
 * {@code UserTasteProfile} is a Java record (final class), which the
 * {@code GenericJackson2JsonRedisSerializer} with {@code NON_FINAL} typing
 * cannot round-trip on its own. This non-final POJO provides the necessary
 * type information for Jackson deserialization.
 */
public class CachedUserTasteProfile {

    private Map<String, Double> positiveGenreWeights = Map.of();
    private Map<String, Double> negativeGenreWeights = Map.of();
    private Map<String, Double> netGenreWeights = Map.of();
    private Map<String, Double> positiveKeywordWeights = Map.of();
    private Map<String, Double> negativeKeywordWeights = Map.of();
    private Map<String, Double> netKeywordWeights = Map.of();
    private int reviewSignalCount;
    private int watchlistSignalCount;

    public CachedUserTasteProfile() {
    }

    public CachedUserTasteProfile(UserTasteProfile profile) {
        if (profile != null) {
            this.positiveGenreWeights = profile.positiveGenreWeights();
            this.negativeGenreWeights = profile.negativeGenreWeights();
            this.netGenreWeights = profile.netGenreWeights();
            this.positiveKeywordWeights = profile.positiveKeywordWeights();
            this.negativeKeywordWeights = profile.negativeKeywordWeights();
            this.netKeywordWeights = profile.netKeywordWeights();
            this.reviewSignalCount = profile.reviewSignalCount();
            this.watchlistSignalCount = profile.watchlistSignalCount();
        }
    }

    public UserTasteProfile toProfile() {
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

    public Map<String, Double> getPositiveGenreWeights() {
        return positiveGenreWeights;
    }

    public void setPositiveGenreWeights(Map<String, Double> positiveGenreWeights) {
        this.positiveGenreWeights = positiveGenreWeights == null ? Map.of() : positiveGenreWeights;
    }

    public Map<String, Double> getNegativeGenreWeights() {
        return negativeGenreWeights;
    }

    public void setNegativeGenreWeights(Map<String, Double> negativeGenreWeights) {
        this.negativeGenreWeights = negativeGenreWeights == null ? Map.of() : negativeGenreWeights;
    }

    public Map<String, Double> getNetGenreWeights() {
        return netGenreWeights;
    }

    public void setNetGenreWeights(Map<String, Double> netGenreWeights) {
        this.netGenreWeights = netGenreWeights == null ? Map.of() : netGenreWeights;
    }

    public Map<String, Double> getPositiveKeywordWeights() {
        return positiveKeywordWeights;
    }

    public void setPositiveKeywordWeights(Map<String, Double> positiveKeywordWeights) {
        this.positiveKeywordWeights = positiveKeywordWeights == null ? Map.of() : positiveKeywordWeights;
    }

    public Map<String, Double> getNegativeKeywordWeights() {
        return negativeKeywordWeights;
    }

    public void setNegativeKeywordWeights(Map<String, Double> negativeKeywordWeights) {
        this.negativeKeywordWeights = negativeKeywordWeights == null ? Map.of() : negativeKeywordWeights;
    }

    public Map<String, Double> getNetKeywordWeights() {
        return netKeywordWeights;
    }

    public void setNetKeywordWeights(Map<String, Double> netKeywordWeights) {
        this.netKeywordWeights = netKeywordWeights == null ? Map.of() : netKeywordWeights;
    }

    public int getReviewSignalCount() {
        return reviewSignalCount;
    }

    public void setReviewSignalCount(int reviewSignalCount) {
        this.reviewSignalCount = reviewSignalCount;
    }

    public int getWatchlistSignalCount() {
        return watchlistSignalCount;
    }

    public void setWatchlistSignalCount(int watchlistSignalCount) {
        this.watchlistSignalCount = watchlistSignalCount;
    }
}
