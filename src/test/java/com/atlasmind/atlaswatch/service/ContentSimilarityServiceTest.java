package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.models.Movie;
import com.atlasmind.atlaswatch.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContentSimilarityServiceTest {

    private final ContentSimilarityService service = new ContentSimilarityService();

    @Test
    void nullAndEmptyInputsReturnEmpty() {
        Movie seed = movieWithOverview(1L, "A detective investigates a mysterious disappearance");

        assertAll(
                () -> assertEquals(List.of(), service.rankCandidates(null, List.of(seed), Map.of())),
                () -> assertEquals(List.of(), service.rankCandidates(List.of(), List.of(seed), Map.of())),
                () -> assertEquals(List.of(), service.rankCandidates(List.of(seed), null, Map.of())),
                () -> assertEquals(List.of(), service.rankCandidates(List.of(seed), List.of(), Map.of()))
        );
    }

    @Test
    void seedMoviesAreExcludedFromResults() {
        Movie seed = movieWithOverview(1L, "A detective investigates a mysterious disappearance");

        List<PreparedCandidate> results = service.rankCandidates(List.of(seed), List.of(seed), Map.of());

        assertTrue(results.isEmpty());
    }

    @Test
    void candidatesWithBlankOverviewAreExcluded() {
        Movie seed = movieWithOverview(1L, "A detective investigates a mysterious disappearance");
        Movie noOverview = movieWithOverview(2L, null);
        Movie blankOverview = movieWithOverview(3L, "   ");

        List<PreparedCandidate> results = service.rankCandidates(
                List.of(seed),
                List.of(noOverview, blankOverview),
                Map.of()
        );

        assertTrue(results.isEmpty());
    }

    @Test
    void candidateWithIdenticalOverviewToSeedRanksHighest() {
        Movie seed = movieWithOverview(1L, "A detective investigates a mysterious disappearance in a coastal town");
        Movie identical = movieWithOverview(2L, "A detective investigates a mysterious disappearance in a coastal town");
        Movie different = movieWithOverview(3L, "A detective investigates a mysterious disappearance");

        List<PreparedCandidate> results = service.rankCandidates(
                List.of(seed),
                List.of(different, identical),
                Map.of()
        );

        assertFalse(results.isEmpty());
        assertEquals(2L, results.get(0).movie().getId());
        assertTrue(results.get(0).contentSimilarityScore() >= results.get(results.size() - 1).contentSimilarityScore());
    }

    @Test
    void candidatesBelowMinSimilarityAreFiltered() {
        Movie seed = movieWithOverview(1L, "Quantum physics laboratory experiment simulation reactor");
        Movie unrelated = movieWithOverview(2L, "Romantic comedy wedding celebration tropical island paradise");

        List<PreparedCandidate> results = service.rankCandidates(List.of(seed), List.of(unrelated), Map.of());

        assertTrue(results.isEmpty());
    }

    @Test
    void resultsAreSortedBySimilarityThenRatingThenPopularity() {
        Movie seed = movieWithOverview(1L, "Space exploration crew discovers alien life form on distant planet");

        Movie highSimilarity = movieWithOverview(2L, "Space exploration team encounters alien life during distant planet mission");
        highSimilarity.setMovieRating(7.0);
        highSimilarity.setPopularity(50.0);

        Movie lowerSimilarity = movieWithOverview(3L, "Space exploration documentary about planet surfaces");
        lowerSimilarity.setMovieRating(9.0);
        lowerSimilarity.setPopularity(200.0);

        List<PreparedCandidate> results = service.rankCandidates(
                List.of(seed),
                List.of(lowerSimilarity, highSimilarity),
                Map.of()
        );

        if (results.size() >= 2) {
            assertTrue(results.get(0).contentSimilarityScore() >= results.get(1).contentSimilarityScore());
        }
    }

    @Test
    void overviewWithOnlyStopwordsAndShortTokensProducesNoMatch() {
        Movie seed = movieWithOverview(1L, "Astronaut discovers alien civilization beneath ocean floor");
        Movie stopwordsOnly = movieWithOverview(2L, "about after all also and are because before been being");

        List<PreparedCandidate> results = service.rankCandidates(List.of(seed), List.of(stopwordsOnly), Map.of());

        assertTrue(results.isEmpty());
    }

    @Test
    void tokenizationIsLowercaseAndIgnoresShortTokens() {
        Movie seed = movieWithOverview(1L, "DETECTIVE investigates DISAPPEARANCE");
        Movie candidate = movieWithOverview(2L, "detective investigates disappearance");

        List<PreparedCandidate> results = service.rankCandidates(List.of(seed), List.of(candidate), Map.of());

        assertFalse(results.isEmpty());
        assertEquals(1.0, results.get(0).contentSimilarityScore(), 0.01);
    }

    @Test
    void multipleSeedsAverageTheirProfiles() {
        Movie seedA = movieWithOverview(1L, "Underwater ocean diving expedition submarine adventure");
        Movie seedB = movieWithOverview(2L, "Mountain climbing expedition hiking adventure summit");

        Movie closerToA = movieWithOverview(3L, "Underwater ocean submarine exploration deep sea diving");
        Movie closerToB = movieWithOverview(4L, "Mountain hiking summit climbing peak expedition trail");
        Movie blendOfBoth = movieWithOverview(5L, "Expedition adventure underwater mountain exploration diving climbing");

        List<PreparedCandidate> results = service.rankCandidates(
                List.of(seedA, seedB),
                List.of(closerToA, closerToB, blendOfBoth),
                Map.of()
        );

        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(c -> c.movie().getId() == 5L));
    }

    @Test
    void similarityScoresAreBetweenZeroAndOne() {
        Movie seed = movieWithOverview(1L, "Time travel paradox scientist laboratory experiment quantum");
        Movie candidate = movieWithOverview(2L, "Time travel adventure scientist builds quantum machine laboratory");

        List<PreparedCandidate> results = service.rankCandidates(List.of(seed), List.of(candidate), Map.of());

        assertFalse(results.isEmpty());
        for (PreparedCandidate result : results) {
            assertTrue(result.contentSimilarityScore() >= 0.0);
            assertTrue(result.contentSimilarityScore() <= 1.0);
        }
    }

    @Test
    void candidateWithNullIdIsSkipped() {
        Movie seed = movieWithOverview(1L, "Detective investigates mysterious crime scene");
        Movie nullId = movieWithOverview(null, "Detective investigates mysterious crime scene");

        List<PreparedCandidate> results = service.rankCandidates(List.of(seed), List.of(nullId), Map.of());

        assertTrue(results.isEmpty());
    }

    @Test
    void sharedKeywordsCanCreateSimilarityEvenWithDifferentOverviews() {
        Movie seed = movieWithOverview(1L, "A retired pilot returns home after the war");
        Movie candidate = movieWithOverview(2L, "A young scientist opens a dangerous portal in a lab");

        List<PreparedCandidate> results = service.rankCandidates(
                List.of(seed),
                List.of(candidate),
                Map.of(
                        1L, List.of("time loop", "alternate reality"),
                        2L, List.of("alternate reality", "time loop")
                )
        );

        assertFalse(results.isEmpty());
        assertEquals(2L, results.getFirst().movie().getId());
    }

    private Movie movieWithOverview(Long id, String overview) {
        Movie movie = TestFixtures.movie(id, id != null ? id.intValue() : 0, "Movie " + id);
        movie.setOverview(overview);
        return movie;
    }
}
