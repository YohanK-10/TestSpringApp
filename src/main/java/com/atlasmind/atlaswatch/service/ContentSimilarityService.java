package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.models.Movie;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
class ContentSimilarityService {

    private static final Set<String> CONTENT_STOPWORDS = Set.of(
            "about", "after", "all", "also", "and", "are", "because", "before", "been", "being",
            "between", "but", "can", "during", "each", "for", "from", "into", "its", "more",
            "over", "that", "their", "them", "then", "there", "they", "this", "through", "when",
            "where", "which", "while", "with", "your"
    );

    List<PreparedCandidate> rankCandidates(
            List<Movie> seedMovies,
            List<Movie> candidateMovies,
            Map<Long, List<String>> keywordsByMovieId
    ) {
        if (seedMovies == null || seedMovies.isEmpty() || candidateMovies == null || candidateMovies.isEmpty()) {
            return List.of();
        }

        Set<Long> seedMovieIds = seedMovies.stream()
                .map(Movie::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<Long, List<String>> tokensByMovieId = new LinkedHashMap<>();
        for (Movie seedMovie : seedMovies) {
            tokensByMovieId.put(
                    seedMovie.getId(),
                    tokenizeContent(seedMovie.getOverview(), keywordsByMovieId.getOrDefault(seedMovie.getId(), List.of()))
            );
        }
        for (Movie candidateMovie : candidateMovies) {
            if (candidateMovie == null || candidateMovie.getId() == null || seedMovieIds.contains(candidateMovie.getId())) {
                continue;
            }

            List<String> tokens = tokenizeContent(
                    candidateMovie.getOverview(),
                    keywordsByMovieId.getOrDefault(candidateMovie.getId(), List.of())
            );
            if (!tokens.isEmpty()) {
                tokensByMovieId.put(candidateMovie.getId(), tokens);
            }
        }

        Map<String, Double> inverseDocumentFrequency = computeInverseDocumentFrequency(tokensByMovieId.values());
        Map<String, Double> seedProfileVector = averageVectors(seedMovies.stream()
                .map(Movie::getId)
                .map(tokensByMovieId::get)
                .filter(tokens -> tokens != null && !tokens.isEmpty())
                .map(tokens -> buildTfIdfVector(tokens, inverseDocumentFrequency))
                .toList());

        if (seedProfileVector.isEmpty()) {
            return List.of();
        }

        return candidateMovies.stream()
                .filter(Objects::nonNull)
                .filter(movie -> movie.getId() != null && !seedMovieIds.contains(movie.getId()))
                .map(movie -> {
                    Map<String, Double> candidateVector = buildTfIdfVector(
                            tokensByMovieId.getOrDefault(movie.getId(), List.of()),
                            inverseDocumentFrequency
                    );
                    return new PreparedCandidate(movie, cosineSimilarity(seedProfileVector, candidateVector));
                })
                .filter(candidate -> candidate.contentSimilarityScore() >= RecommendationScorer.MIN_CONTENT_SIMILARITY_SCORE)
                .sorted(Comparator
                        .comparingDouble(PreparedCandidate::contentSimilarityScore).reversed()
                        .thenComparing(candidate -> candidate.movie().getMovieRating(), Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(candidate -> candidate.movie().getPopularity(), Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private Map<String, Double> computeInverseDocumentFrequency(Collection<List<String>> documents) {
        if (documents == null || documents.isEmpty()) {
            return Map.of();
        }

        Map<String, Integer> documentFrequency = new HashMap<>();
        int documentCount = 0;

        for (List<String> document : documents) {
            if (document == null || document.isEmpty()) {
                continue;
            }

            documentCount++;
            new LinkedHashSet<>(document).forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
        }

        if (documentCount == 0) {
            return Map.of();
        }

        Map<String, Double> inverseDocumentFrequency = new HashMap<>();
        for (Map.Entry<String, Integer> entry : documentFrequency.entrySet()) {
            inverseDocumentFrequency.put(
                    entry.getKey(),
                    Math.log((double) (documentCount + 1) / (entry.getValue() + 1)) + 1.0
            );
        }
        return inverseDocumentFrequency;
    }

    private Map<String, Double> buildTfIdfVector(List<String> tokens, Map<String, Double> inverseDocumentFrequency) {
        if (tokens == null || tokens.isEmpty() || inverseDocumentFrequency == null || inverseDocumentFrequency.isEmpty()) {
            return Map.of();
        }

        Map<String, Integer> termFrequency = new HashMap<>();
        for (String token : tokens) {
            termFrequency.merge(token, 1, Integer::sum);
        }

        int totalTerms = tokens.size();
        if (totalTerms == 0) {
            return Map.of();
        }

        Map<String, Double> vector = new HashMap<>();
        for (Map.Entry<String, Integer> entry : termFrequency.entrySet()) {
            double tf = (double) entry.getValue() / totalTerms;
            double idf = inverseDocumentFrequency.getOrDefault(entry.getKey(), 0.0);
            double tfIdf = tf * idf;
            if (tfIdf > 0.0) {
                vector.put(entry.getKey(), tfIdf);
            }
        }

        return vector;
    }

    private Map<String, Double> averageVectors(List<Map<String, Double>> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            return Map.of();
        }

        Map<String, Double> summedVector = new HashMap<>();
        for (Map<String, Double> vector : vectors) {
            if (vector == null || vector.isEmpty()) {
                continue;
            }

            for (Map.Entry<String, Double> entry : vector.entrySet()) {
                summedVector.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }

        if (summedVector.isEmpty()) {
            return Map.of();
        }

        int vectorCount = Math.max(1, vectors.size());
        Map<String, Double> averagedVector = new HashMap<>();
        for (Map.Entry<String, Double> entry : summedVector.entrySet()) {
            averagedVector.put(entry.getKey(), entry.getValue() / vectorCount);
        }
        return averagedVector;
    }

    private double cosineSimilarity(Map<String, Double> leftVector, Map<String, Double> rightVector) {
        if (leftVector == null || leftVector.isEmpty() || rightVector == null || rightVector.isEmpty()) {
            return 0.0;
        }

        double dotProduct = 0.0;
        for (Map.Entry<String, Double> entry : leftVector.entrySet()) {
            dotProduct += entry.getValue() * rightVector.getOrDefault(entry.getKey(), 0.0);
        }

        if (dotProduct <= 0.0) {
            return 0.0;
        }

        double leftMagnitude = Math.sqrt(leftVector.values().stream()
                .mapToDouble(value -> value * value)
                .sum());
        double rightMagnitude = Math.sqrt(rightVector.values().stream()
                .mapToDouble(value -> value * value)
                .sum());

        if (leftMagnitude == 0.0 || rightMagnitude == 0.0) {
            return 0.0;
        }

        double similarity = dotProduct / (leftMagnitude * rightMagnitude);
        return Math.max(0.0, Math.min(1.0, similarity));
    }

    private List<String> tokenizeContent(String text, List<String> keywords) {
        List<String> tokens = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            tokens.addAll(Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                    .filter(token -> token.length() >= 3)
                    .filter(token -> !CONTENT_STOPWORDS.contains(token))
                    .toList());
        }
        if (keywords != null) {
            for (String keyword : keywords) {
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }
                String normalizedPhrase = keyword.trim().toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", " ")
                        .trim();
                if (normalizedPhrase.isBlank()) {
                    continue;
                }
                String compactPhrase = normalizedPhrase.replace(' ', '_');
                if (compactPhrase.length() >= 3) {
                    tokens.add(compactPhrase);
                }
                tokens.addAll(Arrays.stream(normalizedPhrase.split("\\s+"))
                        .filter(token -> token.length() >= 3)
                        .filter(token -> !CONTENT_STOPWORDS.contains(token))
                        .toList());
            }
        }
        return tokens;
    }
}
