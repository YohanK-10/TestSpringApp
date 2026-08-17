package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.models.Movie;
import com.atlasmind.atlaswatch.models.WatchList;

import java.util.List;
import java.util.Map;
import java.util.Set;

record RecommendationContext(
        Long userId,
        Map<Long, WatchList> watchlistByMovieId,
        Set<Long> watchedMovieIds,
        Set<Long> penalizedMovieIds,
        Set<Long> suppressedMovieIds,
        Set<Long> sessionSeenMovieIds,
        UserTasteProfile tasteProfile,
        List<Movie> contentSimilaritySeeds,
        Map<Integer, Double> collaborativeScoresByTmdbId,
        String rotationKey,
        boolean coldStart,
        boolean authenticated
) {
    static RecommendationContext createColdStart(
            UserTasteProfile tasteProfile,
            List<Movie> contentSimilaritySeeds,
            Set<Long> sessionSeenMovieIds,
            String rotationKey
    ) {
        return new RecommendationContext(
                null,
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                sessionSeenMovieIds == null ? Set.of() : Set.copyOf(sessionSeenMovieIds),
                tasteProfile == null ? UserTasteProfile.empty() : tasteProfile,
                contentSimilaritySeeds == null ? List.of() : List.copyOf(contentSimilaritySeeds),
                Map.of(),
                rotationKey,
                true,
                false
        );
    }
}
