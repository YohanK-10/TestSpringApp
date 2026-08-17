package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.models.Movie;
import com.atlasmind.atlaswatch.models.WatchList;

import java.util.List;

record WatchlistRecommendation(
        WatchList entry,
        List<String> genres,
        double score,
        List<String> reasons
) {
    Movie movie() {
        return entry.getMovie();
    }
}
