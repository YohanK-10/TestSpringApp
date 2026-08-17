package com.atlasmind.atlaswatch.service;

import java.util.List;

record ChannelCandidateBatch(
        String channelName,
        List<PreparedCandidate> fetchedCandidates,
        List<PreparedCandidate> sampledCandidates
) {
}
