package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.models.Movie;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

@Slf4j
@Component
class CollaborativeSimilarityService {
    private final ObjectMapper objectMapper;
    private final Resource modelResource;
    private final boolean enabled;
    private volatile Map<Integer, double[]> vectorsByTmdbId = Map.of();
    private volatile int dimensions;

    CollaborativeSimilarityService(ObjectMapper objectMapper,
            @Value("${atlaswatch.recommendation.collaborative.model:classpath:models/movielens-latent-factors.json.gz}") Resource modelResource,
            @Value("${atlaswatch.recommendation.collaborative.enabled:true}") boolean enabled) {
        this.objectMapper = objectMapper;
        this.modelResource = modelResource;
        this.enabled = enabled;
    }

    @PostConstruct
    void loadModel() {
        if (!enabled) {
            log.info("Collaborative recommendation signal is disabled.");
            return;
        }
        try (InputStream raw = modelResource.getInputStream(); GZIPInputStream compressed = new GZIPInputStream(raw)) {
            ModelArtifact artifact = objectMapper.readValue(compressed, ModelArtifact.class);
            if (artifact.schemaVersion() != 2 || artifact.dimensions() <= 0 || artifact.items() == null) {
                throw new IllegalArgumentException("Unsupported collaborative model schema");
            }
            Map<Integer, double[]> loaded = new LinkedHashMap<>();
            artifact.items().forEach((tmdbId, vector) -> {
                try {
                    double[] sanitized = sanitize(vector, artifact.dimensions());
                    if (sanitized != null) loaded.put(Integer.valueOf(tmdbId), sanitized);
                } catch (NumberFormatException ignored) {
                    log.warn("Ignoring invalid TMDB id '{}' in collaborative model.", tmdbId);
                }
            });
            if (loaded.isEmpty()) throw new IllegalArgumentException("Collaborative model contains no valid item vectors");
            vectorsByTmdbId = Collections.unmodifiableMap(loaded);
            dimensions = artifact.dimensions();
            log.info("Loaded {}-factor collaborative recommendation model with {} TMDB items.", dimensions, loaded.size());
        } catch (Exception ex) {
            vectorsByTmdbId = Map.of();
            dimensions = 0;
            log.warn("Collaborative model unavailable; continuing without this optional signal: {}", ex.getMessage());
        }
    }

    Map<Integer, Double> scoreCandidates(List<Movie> positiveSeeds) {
        if (positiveSeeds == null) return Map.of();
        Map<Integer, Double> weights = new LinkedHashMap<>();
        positiveSeeds.stream().filter(Objects::nonNull).map(Movie::getTmdbId).filter(Objects::nonNull)
                .forEach(tmdbId -> weights.putIfAbsent(tmdbId, 1.0));
        return scoreCandidates(weights);
    }

    Map<Integer, Double> scoreCandidates(Map<Integer, Double> positiveSeedWeights) {
        if (positiveSeedWeights == null || positiveSeedWeights.isEmpty() || vectorsByTmdbId.isEmpty()) return Map.of();
        double[] profile = new double[dimensions];
        positiveSeedWeights.forEach((tmdbId, weight) -> {
            double[] vector = vectorsByTmdbId.get(tmdbId);
            if (vector != null && weight != null && Double.isFinite(weight) && weight > 0.0) {
                for (int index = 0; index < dimensions; index++) profile[index] += vector[index] * weight;
            }
        });
        Map<Integer, Double> rawScores = new LinkedHashMap<>();
        vectorsByTmdbId.forEach((tmdbId, vector) -> {
            if (!positiveSeedWeights.containsKey(tmdbId)) {
                double score = 0.0;
                for (int index = 0; index < dimensions; index++) score += profile[index] * vector[index];
                if (score > 0.0 && Double.isFinite(score)) rawScores.put(tmdbId, score);
            }
        });
        double maximum = rawScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        if (maximum <= 0.0) return Map.of();
        Map<Integer, Double> normalized = new LinkedHashMap<>();
        rawScores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> normalized.put(entry.getKey(), Math.min(1.0, entry.getValue() / maximum)));
        return Collections.unmodifiableMap(normalized);
    }

    private double[] sanitize(List<Double> vector, int expectedDimensions) {
        if (vector == null || vector.size() != expectedDimensions) return null;
        double[] result = new double[expectedDimensions];
        for (int index = 0; index < expectedDimensions; index++) {
            Double value = vector.get(index);
            if (value == null || !Double.isFinite(value)) return null;
            result[index] = value;
        }
        return result;
    }

    record ModelArtifact(int schemaVersion, String algorithm, double positiveThreshold,
                         int dimensions, Map<String, List<Double>> items) {}
}
