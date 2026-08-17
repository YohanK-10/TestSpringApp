package com.atlasmind.atlaswatch.service;

import java.util.List;

/** A synthetic, persistence-free taste profile used only by the offline evaluator. */
public record RecommendationEvaluationPersona(
        String id,
        boolean warm,
        List<String> starterGenres,
        List<String> starterKeywords
) {
    public RecommendationEvaluationPersona {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Evaluation persona id is required.");
        }
        starterGenres = starterGenres == null ? List.of() : List.copyOf(starterGenres);
        starterKeywords = starterKeywords == null ? List.of() : List.copyOf(starterKeywords);
    }

    long syntheticUserId() {
        return -1L - Integer.toUnsignedLong(id.hashCode());
    }
}
