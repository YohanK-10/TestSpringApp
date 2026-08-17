package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.models.Movie;

record PreparedCandidate(
        Movie movie,
        double contentSimilarityScore
) {
}
