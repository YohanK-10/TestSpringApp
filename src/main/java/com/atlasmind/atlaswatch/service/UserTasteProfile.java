package com.atlasmind.atlaswatch.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record UserTasteProfile(
        Map<String, Double> positiveGenreWeights,
        Map<String, Double> negativeGenreWeights,
        Map<String, Double> netGenreWeights,
        Map<String, Double> positiveKeywordWeights,
        Map<String, Double> negativeKeywordWeights,
        Map<String, Double> netKeywordWeights,
        int reviewSignalCount,
        int watchlistSignalCount
) {

    public UserTasteProfile {
        positiveGenreWeights = immutableCopy(positiveGenreWeights);
        negativeGenreWeights = immutableCopy(negativeGenreWeights);
        netGenreWeights = immutableCopy(netGenreWeights);
        positiveKeywordWeights = immutableCopy(positiveKeywordWeights);
        negativeKeywordWeights = immutableCopy(negativeKeywordWeights);
        netKeywordWeights = immutableCopy(netKeywordWeights);
    }

    public static UserTasteProfile empty() {
        return new UserTasteProfile(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, 0);
    }

    public boolean hasSignals() {
        return !positiveGenreWeights.isEmpty()
                || !negativeGenreWeights.isEmpty()
                || !positiveKeywordWeights.isEmpty()
                || !negativeKeywordWeights.isEmpty();
    }

    public boolean isColdStart() {
        return !hasSignals();
    }

    public double positiveWeight(String normalizedGenre) {
        return positiveGenreWeights.getOrDefault(normalizedGenre, 0.0);
    }

    public double negativeWeight(String normalizedGenre) {
        return negativeGenreWeights.getOrDefault(normalizedGenre, 0.0);
    }

    public double netWeight(String normalizedGenre) {
        return netGenreWeights.getOrDefault(normalizedGenre, positiveWeight(normalizedGenre) - negativeWeight(normalizedGenre));
    }

    public double positiveKeywordWeight(String normalizedKeyword) {
        return positiveKeywordWeights.getOrDefault(normalizedKeyword, 0.0);
    }

    public double negativeKeywordWeight(String normalizedKeyword) {
        return negativeKeywordWeights.getOrDefault(normalizedKeyword, 0.0);
    }

    public double netKeywordWeight(String normalizedKeyword) {
        return netKeywordWeights.getOrDefault(
                normalizedKeyword,
                positiveKeywordWeight(normalizedKeyword) - negativeKeywordWeight(normalizedKeyword)
        );
    }

    public List<String> topPositiveGenres(int limit) {
        if (limit <= 0 || positiveGenreWeights.isEmpty()) {
            return List.of();
        }

        return positiveGenreWeights.keySet().stream()
                .filter(genre -> positiveWeight(genre) > 0.0)
                .filter(genre -> netWeight(genre) > 0.0)
                .sorted((left, right) -> {
                    int positiveComparison = Double.compare(positiveWeight(right), positiveWeight(left));
                    if (positiveComparison != 0) {
                        return positiveComparison;
                    }

                    int netComparison = Double.compare(netWeight(right), netWeight(left));
                    if (netComparison != 0) {
                        return netComparison;
                    }

                    return left.compareTo(right);
                })
                .limit(limit)
                .toList();
    }

    public Set<String> knownGenres() {
        return netGenreWeights.keySet();
    }

    public Set<String> knownKeywords() {
        return netKeywordWeights.keySet();
    }

    private static Map<String, Double> immutableCopy(Map<String, Double> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }

        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}

