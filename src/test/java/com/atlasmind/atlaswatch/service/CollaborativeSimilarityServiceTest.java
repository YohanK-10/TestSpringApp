package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.models.Movie;
import com.atlasmind.atlaswatch.support.TestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollaborativeSimilarityServiceTest {

    @Test
    void projectsPositiveSeedsThroughLatentFactorsAndRemovesSeenMovies() throws Exception {
        String model = """
                {"schemaVersion":2,"algorithm":"test","positiveThreshold":4.0,"dimensions":2,
                 "items":{"10":[1.0,0.0],"20":[0.0,1.0],"30":[0.8,0.8],"40":[0.2,0.1]}}
                """;
        CollaborativeSimilarityService service = serviceWith(model);
        Movie firstSeed = TestFixtures.movie(1L, 10, "First seed");
        Movie secondSeed = TestFixtures.movie(2L, 20, "Second seed");

        Map<Integer, Double> scores = service.scoreCandidates(List.of(firstSeed, secondSeed));

        assertEquals(1.0, scores.get(30), 1e-9);
        assertEquals(0.3 / 1.6, scores.get(40), 1e-9);
        assertEquals(List.of(30, 40), List.copyOf(scores.keySet()));
        assertFalse(scores.containsKey(10));
        assertFalse(scores.containsKey(20));
    }

    @Test
    void corruptModelFailsOpenWithoutProducingCandidates() throws Exception {
        CollaborativeSimilarityService service = serviceWith("not-json");

        assertTrue(service.scoreCandidates(List.of(TestFixtures.movie(1L, 10, "Seed"))).isEmpty());
    }

    @Test
    void reviewStrengthWeightsTheLatentProfile() throws Exception {
        String model = """
                {"schemaVersion":2,"algorithm":"test","positiveThreshold":4.0,"dimensions":2,
                 "items":{"10":[1.0,0.0],"20":[0.0,1.0],"30":[0.8,0.2],"40":[0.2,0.8]}}
                """;
        CollaborativeSimilarityService service = serviceWith(model);

        Map<Integer, Double> scores = service.scoreCandidates(Map.of(10, 0.5, 20, 1.0));

        assertEquals(List.of(40, 30), List.copyOf(scores.keySet()));
        assertEquals(1.0, scores.get(40), 1e-9);
        assertEquals(2.0 / 3.0, scores.get(30), 1e-9);
    }

    private CollaborativeSimilarityService serviceWith(String json) throws Exception {
        CollaborativeSimilarityService service = new CollaborativeSimilarityService(
                new ObjectMapper(), new ByteArrayResource(gzip(json)), true);
        service.loadModel();
        return service;
    }

    private byte[] gzip(String value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream output = new GZIPOutputStream(bytes)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }
}
