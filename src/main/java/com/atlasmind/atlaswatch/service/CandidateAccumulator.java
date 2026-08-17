package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.models.Movie;

import java.util.LinkedHashSet;
import java.util.List;

final class CandidateAccumulator {
    private final Movie movie;
    private final LinkedHashSet<String> sourceChannels;
    private double contentSimilarityScore;

    private CandidateAccumulator(Movie movie, LinkedHashSet<String> sourceChannels, double contentSimilarityScore) {
        this.movie = movie;
        this.sourceChannels = sourceChannels;
        this.contentSimilarityScore = contentSimilarityScore;
    }

    static CandidateAccumulator from(String channelName, PreparedCandidate candidate) {
        LinkedHashSet<String> sourceChannels = new LinkedHashSet<>();
        sourceChannels.add(channelName);
        return new CandidateAccumulator(candidate.movie(), sourceChannels, candidate.contentSimilarityScore());
    }

    void addSourceChannel(String channelName, double additionalContentSimilarityScore) {
        sourceChannels.add(channelName);
        contentSimilarityScore = Math.max(contentSimilarityScore, additionalContentSimilarityScore);
    }

    CatalogCandidate toCatalogCandidate() {
        return new CatalogCandidate(movie, List.copyOf(sourceChannels), contentSimilarityScore);
    }
}
