package com.atlasmind.atlaswatch.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/** Label-free diagnostics for the versioned session-intent prompt matrix. */
public final class SessionIntentBatchEvaluator {

    private SessionIntentBatchEvaluator() {
    }

    public record Observation(
            String clusterId,
            String promptId,
            String primaryMood,
            int requestedMoodCount,
            String personaId,
            boolean warmPersona,
            int rotation,
            int requestedLimit,
            RecommendationEvaluationRun run
    ) {
    }

    public record ConfidenceInterval(double estimate, double lower95, double upper95) {
    }

    /**
     * Session-scoped fields are nullable because item-level slices cannot
     * define them. Reporting them as {@code 0} would read as "this slice never
     * filled a shortlist" rather than "this question does not apply here".
     */
    public record SliceReport(
            String dimension,
            String value,
            Integer sessions,
            int recommendations,
            Double fullShortlistRate,
            double meanRuleMoodCoverage,
            double primaryMoodViolationRate
    ) {
    }

    public record BatchReport(
            int sessions,
            int clusters,
            int recommendations,
            int catalogSize,
            int uniqueMovies,
            double fullShortlistRate,
            double meanSlotFillRate,
            double meanRuleMoodCoverage,
            double primaryMoodViolationRate,
            double runtimeViolationRate,
            double eraViolationRate,
            double meanGenreDiversity,
            double meanIntraListSimilarity,
            double catalogCoverage,
            double meanRotationOverlap,
            double meanUniqueResultRate,
            double topOneRepeatRate,
            double latencyP50Ms,
            double latencyP95Ms,
            double meanMergedCandidates,
            Map<String, Double> meanChannelCandidatesAdded,
            Map<String, ConfidenceInterval> confidenceIntervals,
            List<SliceReport> slices
    ) {
    }

    public static BatchReport evaluate(
            List<Observation> observations,
            int catalogSize,
            int bootstrapIterations,
            long bootstrapSeed
    ) {
        List<Observation> safe = observations == null ? List.of() : List.copyOf(observations);
        Metrics metrics = metrics(safe, catalogSize);
        Map<String, ConfidenceInterval> intervals = new LinkedHashMap<>();
        intervals.put("fullShortlistRate", bootstrap(safe, bootstrapIterations, bootstrapSeed,
                value -> metrics(value, catalogSize).fullShortlistRate));
        intervals.put("meanRuleMoodCoverage", bootstrap(safe, bootstrapIterations, bootstrapSeed + 1,
                value -> metrics(value, catalogSize).meanRuleMoodCoverage));
        intervals.put("primaryMoodViolationRate", bootstrap(safe, bootstrapIterations, bootstrapSeed + 2,
                value -> metrics(value, catalogSize).primaryMoodViolationRate));
        intervals.put("meanRotationOverlap", bootstrap(safe, bootstrapIterations, bootstrapSeed + 3,
                value -> metrics(value, catalogSize).meanRotationOverlap));

        return new BatchReport(
                safe.size(),
                (int) safe.stream().map(Observation::clusterId).distinct().count(),
                metrics.recommendations,
                catalogSize,
                metrics.uniqueMovies,
                metrics.fullShortlistRate,
                metrics.meanSlotFillRate,
                metrics.meanRuleMoodCoverage,
                metrics.primaryMoodViolationRate,
                metrics.runtimeViolationRate,
                metrics.eraViolationRate,
                metrics.meanGenreDiversity,
                metrics.meanIntraListSimilarity,
                metrics.catalogCoverage,
                metrics.meanRotationOverlap,
                metrics.meanUniqueResultRate,
                metrics.topOneRepeatRate,
                metrics.latencyP50Ms,
                metrics.latencyP95Ms,
                metrics.meanMergedCandidates,
                metrics.meanChannelCandidatesAdded,
                Map.copyOf(intervals),
                buildSlices(safe)
        );
    }

    /** Paired cluster bootstrap for future baseline/challenger deltas. */
    public static ConfidenceInterval pairedClusterBootstrapDelta(
            Map<String, Double> baselineByCluster,
            Map<String, Double> challengerByCluster,
            int iterations,
            long seed
    ) {
        List<String> clusters = baselineByCluster.keySet().stream()
                .filter(challengerByCluster::containsKey)
                .sorted()
                .toList();
        if (clusters.isEmpty()) {
            return new ConfidenceInterval(0, 0, 0);
        }
        double estimate = clusters.stream().mapToDouble(cluster ->
                challengerByCluster.get(cluster) - baselineByCluster.get(cluster)).average().orElse(0);
        Random random = new Random(seed);
        List<Double> samples = new ArrayList<>(Math.max(1, iterations));
        for (int iteration = 0; iteration < Math.max(1, iterations); iteration++) {
            double sum = 0;
            for (int index = 0; index < clusters.size(); index++) {
                String cluster = clusters.get(random.nextInt(clusters.size()));
                sum += challengerByCluster.get(cluster) - baselineByCluster.get(cluster);
            }
            samples.add(sum / clusters.size());
        }
        samples.sort(Double::compareTo);
        return new ConfidenceInterval(estimate, percentile(samples, 0.025), percentile(samples, 0.975));
    }

    /** Supplies cluster-level inputs for paired baseline/challenger analysis. */
    public static Map<String, Double> metricByCluster(
            List<Observation> observations,
            int catalogSize,
            String metricName
    ) {
        if (observations == null || observations.isEmpty()) {
            return Map.of();
        }
        return observations.stream()
                .collect(Collectors.groupingBy(Observation::clusterId, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> metric(metrics(entry.getValue(), catalogSize), metricName),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private static double metric(Metrics metrics, String metricName) {
        return switch (metricName) {
            case "fullShortlistRate" -> metrics.fullShortlistRate;
            case "meanSlotFillRate" -> metrics.meanSlotFillRate;
            case "meanRuleMoodCoverage" -> metrics.meanRuleMoodCoverage;
            case "primaryMoodViolationRate" -> metrics.primaryMoodViolationRate;
            case "meanRotationOverlap" -> metrics.meanRotationOverlap;
            case "meanUniqueResultRate" -> metrics.meanUniqueResultRate;
            case "topOneRepeatRate" -> metrics.topOneRepeatRate;
            case "runtimeViolationRate" -> metrics.runtimeViolationRate;
            case "eraViolationRate" -> metrics.eraViolationRate;
            default -> throw new IllegalArgumentException("Unsupported cluster metric: " + metricName);
        };
    }

    private static ConfidenceInterval bootstrap(
            List<Observation> observations,
            int iterations,
            long seed,
            ToDoubleFunction<List<Observation>> statistic
    ) {
        double estimate = statistic.applyAsDouble(observations);
        Map<String, List<Observation>> byCluster = observations.stream()
                .collect(Collectors.groupingBy(Observation::clusterId, LinkedHashMap::new, Collectors.toList()));
        List<String> clusters = new ArrayList<>(byCluster.keySet());
        if (clusters.isEmpty()) {
            return new ConfidenceInterval(estimate, estimate, estimate);
        }
        Random random = new Random(seed);
        List<Double> samples = new ArrayList<>(Math.max(1, iterations));
        for (int iteration = 0; iteration < Math.max(1, iterations); iteration++) {
            List<Observation> sample = new ArrayList<>();
            for (int index = 0; index < clusters.size(); index++) {
                String sampledCluster = clusters.get(random.nextInt(clusters.size()));
                String resampledClusterId = sampledCluster + "#bootstrap-" + index;
                byCluster.get(sampledCluster).stream()
                        .map(observation -> withClusterId(observation, resampledClusterId))
                        .forEach(sample::add);
            }
            samples.add(statistic.applyAsDouble(sample));
        }
        samples.sort(Double::compareTo);
        return new ConfidenceInterval(estimate, percentile(samples, 0.025), percentile(samples, 0.975));
    }

    private static Observation withClusterId(Observation observation, String clusterId) {
        return new Observation(
                clusterId,
                observation.promptId(),
                observation.primaryMood(),
                observation.requestedMoodCount(),
                observation.personaId(),
                observation.warmPersona(),
                observation.rotation(),
                observation.requestedLimit(),
                observation.run()
        );
    }

    private static Metrics metrics(List<Observation> observations, int catalogSize) {
        List<RecommendationEvaluationRun.Item> items = observations.stream()
                .flatMap(observation -> observation.run().items().stream())
                .toList();
        int recommendationCount = items.size();
        Set<Integer> unique = items.stream().map(RecommendationEvaluationRun.Item::tmdbId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        double full = mean(observations, observation ->
                observation.run().items().size() >= observation.requestedLimit() ? 1 : 0);
        double slotFill = mean(observations, observation -> observation.requestedLimit() <= 0 ? 0
                : Math.min(1.0, (double) observation.run().items().size() / observation.requestedLimit()));
        double moodCoverage = items.stream().mapToDouble(RecommendationEvaluationRun.Item::moodCoverage)
                .average().orElse(0);
        long primaryViolations = observations.stream().mapToLong(observation -> observation.run().items().stream()
                .filter(item -> !containsMood(item.coveredMoods(), observation.primaryMood())).count()).sum();
        long runtimeViolations = items.stream().filter(item -> !item.runtimeSatisfied()).count();
        long eraViolations = items.stream().filter(item -> !item.eraSatisfied()).count();

        List<Double> latencies = observations.stream().map(observation -> observation.run().durationNanos() / 1_000_000.0)
                .sorted().toList();
        Map<String, Double> channelMeans = observations.stream()
                .flatMap(observation -> observation.run().channelStats().entrySet().stream())
                .collect(Collectors.groupingBy(Map.Entry::getKey, LinkedHashMap::new,
                        Collectors.averagingInt(entry -> entry.getValue().uniqueAdded())));

        Map<String, List<Observation>> byCluster = observations.stream()
                .collect(Collectors.groupingBy(Observation::clusterId, LinkedHashMap::new, Collectors.toList()));
        List<Double> overlaps = new ArrayList<>();
        List<Double> uniqueRates = new ArrayList<>();
        int repeatedTops = 0;
        int comparableTops = 0;
        for (List<Observation> cluster : byCluster.values()) {
            List<Observation> ordered = cluster.stream().sorted(Comparator.comparingInt(Observation::rotation)).toList();
            Set<Integer> clusterUnique = new LinkedHashSet<>();
            int slots = 0;
            Set<Integer> priorTops = new LinkedHashSet<>();
            for (int index = 0; index < ordered.size(); index++) {
                List<Integer> ids = ids(ordered.get(index));
                clusterUnique.addAll(ids);
                slots += ids.size();
                if (index > 0) {
                    overlaps.add(jaccard(ids(ordered.get(index - 1)), ids));
                }
                if (!ids.isEmpty()) {
                    if (!priorTops.isEmpty()) {
                        comparableTops++;
                        if (priorTops.contains(ids.getFirst())) {
                            repeatedTops++;
                        }
                    }
                    priorTops.add(ids.getFirst());
                }
            }
            uniqueRates.add(slots == 0 ? 0 : (double) clusterUnique.size() / slots);
        }

        return new Metrics(
                recommendationCount,
                unique.size(),
                full,
                slotFill,
                moodCoverage,
                recommendationCount == 0 ? 0 : (double) primaryViolations / recommendationCount,
                recommendationCount == 0 ? 0 : (double) runtimeViolations / recommendationCount,
                recommendationCount == 0 ? 0 : (double) eraViolations / recommendationCount,
                mean(observations, observation -> genreDiversity(observation.run().items())),
                mean(observations, observation -> intraListSimilarity(observation.run().items())),
                catalogSize <= 0 ? 0 : (double) unique.size() / catalogSize,
                overlaps.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                uniqueRates.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                comparableTops == 0 ? 0 : (double) repeatedTops / comparableTops,
                percentile(latencies, 0.50),
                percentile(latencies, 0.95),
                mean(observations, observation -> observation.run().mergedCandidateCount()),
                Map.copyOf(channelMeans)
        );
    }

    private static List<SliceReport> buildSlices(List<Observation> observations) {
        List<SliceReport> slices = new ArrayList<>();
        addSessionSlices(slices, "primaryMood", observations,
                observation -> normalize(observation.primaryMood()));
        addSessionSlices(slices, "personaTemperature", observations,
                observation -> observation.warmPersona() ? "warm" : "cold");
        // How many moods a session requested is the sharpest predictor of
        // shortlist collapse, because the intent gate treats an exact
        // three-mood request as strictly conjunctive.
        addSessionSlices(slices, "requestedMoodCount", observations,
                observation -> Integer.toString(observation.requestedMoodCount()));

        Map<String, List<ItemWithPrimary>> evidence = new LinkedHashMap<>();
        for (Observation observation : observations) {
            for (RecommendationEvaluationRun.Item item : observation.run().items()) {
                evidence.computeIfAbsent(item.evidenceSource(), ignored -> new ArrayList<>())
                        .add(new ItemWithPrimary(item, observation.primaryMood()));
            }
        }
        evidence.forEach((value, entries) -> slices.add(new SliceReport(
                "evidenceSource", value, null, entries.size(), null,
                entries.stream().mapToDouble(entry -> entry.item().moodCoverage()).average().orElse(0),
                entries.isEmpty() ? 0 : (double) entries.stream()
                        .filter(entry -> !containsMood(entry.item().coveredMoods(), entry.primaryMood())).count()
                        / entries.size()
        )));
        return List.copyOf(slices);
    }

    private static void addSessionSlices(
            List<SliceReport> target,
            String dimension,
            List<Observation> observations,
            java.util.function.Function<Observation, String> classifier
    ) {
        observations.stream().collect(Collectors.groupingBy(classifier, LinkedHashMap::new, Collectors.toList()))
                .forEach((value, group) -> {
                    Metrics metrics = metrics(group, 0);
                    target.add(new SliceReport(dimension, value, group.size(), metrics.recommendations,
                            metrics.fullShortlistRate, metrics.meanRuleMoodCoverage,
                            metrics.primaryMoodViolationRate));
                });
    }

    private static double genreDiversity(List<RecommendationEvaluationRun.Item> items) {
        long slots = items.stream().mapToLong(item -> item.genres().size()).sum();
        long unique = items.stream().flatMap(item -> item.genres().stream()).map(SessionIntentBatchEvaluator::normalize)
                .distinct().count();
        return slots == 0 ? 0 : (double) unique / slots;
    }

    private static double intraListSimilarity(List<RecommendationEvaluationRun.Item> items) {
        if (items.size() < 2) return 0;
        double sum = 0;
        int pairs = 0;
        for (int left = 0; left < items.size(); left++) {
            for (int right = left + 1; right < items.size(); right++) {
                sum += jaccard(items.get(left).genres(), items.get(right).genres());
                pairs++;
            }
        }
        return pairs == 0 ? 0 : sum / pairs;
    }

    private static double jaccard(Collection<?> left, Collection<?> right) {
        Set<?> a = new LinkedHashSet<>(left);
        Set<?> b = new LinkedHashSet<>(right);
        Set<Object> intersection = new LinkedHashSet<>(a);
        intersection.retainAll(b);
        Set<Object> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    private static List<Integer> ids(Observation observation) {
        return observation.run().items().stream().map(RecommendationEvaluationRun.Item::tmdbId)
                .filter(java.util.Objects::nonNull).toList();
    }

    private static boolean containsMood(List<String> values, String mood) {
        String expected = normalize(mood);
        return "any".equals(expected) || values.stream().map(SessionIntentBatchEvaluator::normalize)
                .anyMatch(expected::equals);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('-', ' ').trim();
    }

    private static double mean(List<Observation> values, ToDoubleFunction<Observation> mapper) {
        return values.stream().mapToDouble(mapper).average().orElse(0);
    }

    private static double percentile(List<Double> sorted, double percentile) {
        if (sorted == null || sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private record ItemWithPrimary(RecommendationEvaluationRun.Item item, String primaryMood) {
    }

    private record Metrics(
            int recommendations,
            int uniqueMovies,
            double fullShortlistRate,
            double meanSlotFillRate,
            double meanRuleMoodCoverage,
            double primaryMoodViolationRate,
            double runtimeViolationRate,
            double eraViolationRate,
            double meanGenreDiversity,
            double meanIntraListSimilarity,
            double catalogCoverage,
            double meanRotationOverlap,
            double meanUniqueResultRate,
            double topOneRepeatRate,
            double latencyP50Ms,
            double latencyP95Ms,
            double meanMergedCandidates,
            Map<String, Double> meanChannelCandidatesAdded
    ) {
    }
}
