package com.atlasmind.atlaswatch.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionIntentBatchEvaluatorTest {

    @Test
    void reportsRotationAndConstraintMetricsWithDeterministicClusterBootstrap() {
        var first = observation("p1:cold", 0, List.of(item(1, 10, 1.0, true)));
        var second = observation("p1:cold", 1, List.of(
                item(1, 10, 1.0, true), item(2, 20, 0.5, false)));

        var left = SessionIntentBatchEvaluator.evaluate(List.of(first, second), 100, 300, 42L);
        var right = SessionIntentBatchEvaluator.evaluate(List.of(first, second), 100, 300, 42L);

        assertEquals(2, left.sessions());
        assertEquals(3, left.recommendations());
        assertEquals(0.5, left.fullShortlistRate(), 1e-9);
        assertEquals(0.5, left.meanRotationOverlap(), 1e-9);
        assertEquals(1.0 / 3.0, left.primaryMoodViolationRate(), 1e-9);
        assertEquals(left.confidenceIntervals(), right.confidenceIntervals());
        var overlapInterval = left.confidenceIntervals().get("meanRotationOverlap");
        assertTrue(overlapInterval.lower95() <= overlapInterval.estimate());
        assertTrue(overlapInterval.upper95() >= overlapInterval.estimate());
        assertTrue(left.slices().stream().anyMatch(slice ->
                slice.dimension().equals("primaryMood") && slice.value().equals("tense")));
    }

    @Test
    void reportsUndefinedSessionSlicesAsNullAndExposesConstraintClusterMetrics() {
        var first = observation("p1:cold", 0, List.of(item(1, 10, 1.0, true)));
        var second = observation("p1:cold", 1, List.of(
                item(1, 10, 1.0, true), item(2, 20, 0.5, false)));
        var report = SessionIntentBatchEvaluator.evaluate(List.of(first, second), 100, 300, 42L);

        // Item-level slices cannot define a session-level rate; null keeps the
        // report from claiming a measured zero.
        var evidenceSlice = report.slices().stream()
                .filter(slice -> slice.dimension().equals("evidenceSource")).findFirst().orElseThrow();
        assertNull(evidenceSlice.sessions());
        assertNull(evidenceSlice.fullShortlistRate());

        var moodCountSlice = report.slices().stream()
                .filter(slice -> slice.dimension().equals("requestedMoodCount")).findFirst().orElseThrow();
        assertEquals("2", moodCountSlice.value());
        assertEquals(2, moodCountSlice.sessions());
        assertNotNull(moodCountSlice.fullShortlistRate());

        // Runtime compliance must be poolable for paired promotion gating.
        assertEquals(Map.of("p1:cold", 0.0), SessionIntentBatchEvaluator.metricByCluster(
                List.of(first, second), 100, "runtimeViolationRate"));
    }

    @Test
    void pairedBootstrapResamplesWholeClustersAndReportsObservedDelta() {
        var interval = SessionIntentBatchEvaluator.pairedClusterBootstrapDelta(
                Map.of("a", 0.2, "b", 0.4, "c", 0.6),
                Map.of("a", 0.3, "b", 0.7, "c", 0.8),
                1_000,
                7L
        );

        assertEquals(0.2, interval.estimate(), 1e-9);
        assertTrue(interval.lower95() <= interval.estimate());
        assertTrue(interval.upper95() >= interval.estimate());
    }

    private SessionIntentBatchEvaluator.Observation observation(
            String cluster,
            int rotation,
            List<RecommendationEvaluationRun.Item> items
    ) {
        var run = new RecommendationEvaluationRun(
                "test", "cold", false, 1_000_000, 50, Map.of(), items);
        return new SessionIntentBatchEvaluator.Observation(
                cluster, "p1", "tense", 2, "cold", false, rotation, 2, run);
    }

    private RecommendationEvaluationRun.Item item(int rank, int tmdbId, double coverage, boolean primaryCovered) {
        return new RecommendationEvaluationRun.Item(
                rank, (long) tmdbId, tmdbId, "Movie " + tmdbId, List.of("Thriller"),
                110, LocalDate.of(2000, 1, 1), 7.0, 100, 10.0, coverage,
                primaryCovered ? List.of("Tense") : List.of("Dark"), true, true,
                "KEYWORD_BACKED", List.of("mood-aligned"));
    }
}
