package com.atlasmind.atlaswatch.service;

import java.util.List;
import java.util.Map;

record CandidateRetrievalResult(
        List<CatalogCandidate> candidates,
        Map<String, ChannelRetrievalStats> channelStats,
        int mergedCandidateCount
) {
    CandidateRetrievalResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        channelStats = channelStats == null ? Map.of() : Map.copyOf(channelStats);
    }
}
