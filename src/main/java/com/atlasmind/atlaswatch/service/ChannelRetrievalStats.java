package com.atlasmind.atlaswatch.service;

record ChannelRetrievalStats(
        String channelName,
        int fetchedCount,
        int sampledCount,
        int eligibleCount,
        int uniqueAddedCount,
        int overlapDroppedCount
) {
}
