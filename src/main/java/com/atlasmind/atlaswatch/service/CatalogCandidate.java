package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.models.Movie;

import java.util.List;

record CatalogCandidate(
        Movie movie,
        List<String> sourceChannels,
        double contentSimilarityScore
) {
    int sourceCount() {
        return sourceChannels == null ? 0 : sourceChannels.size();
    }
}
