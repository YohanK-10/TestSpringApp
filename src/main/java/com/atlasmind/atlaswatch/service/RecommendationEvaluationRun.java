package com.atlasmind.atlaswatch.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Diagnostics returned by the real ranking pipeline without cache or impression writes. */
public record RecommendationEvaluationRun(
        String algorithmVersion,
        String personaId,
        boolean warmPersona,
        long durationNanos,
        int mergedCandidateCount,
        Map<String, ChannelStats> channelStats,
        List<Item> items
) {
    public RecommendationEvaluationRun {
        channelStats = channelStats == null ? Map.of() : Map.copyOf(channelStats);
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record ChannelStats(
            int fetched,
            int sampled,
            int eligible,
            int uniqueAdded,
            int overlapDropped
    ) {
    }

    public record Item(
            int rank,
            Long movieId,
            Integer tmdbId,
            String title,
            List<String> genres,
            Integer runtimeMinutes,
            LocalDate releaseDate,
            Double rating,
            Integer voteCount,
            Double popularity,
            double moodCoverage,
            List<String> coveredMoods,
            boolean runtimeSatisfied,
            boolean eraSatisfied,
            String evidenceSource,
            List<String> sourceChannels
    ) {
        public Item {
            genres = genres == null ? List.of() : List.copyOf(genres);
            coveredMoods = coveredMoods == null ? List.of() : List.copyOf(coveredMoods);
            sourceChannels = sourceChannels == null ? List.of() : List.copyOf(sourceChannels);
        }
    }
}
