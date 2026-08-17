package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.models.Movie;

import java.util.List;

record CatalogRecommendation(
        Movie movie,
        List<String> genres,
        double score,
        double moodMatch,
        boolean onWatchlist,
        String watchlistStatus,
        List<String> reasons
) {
}
