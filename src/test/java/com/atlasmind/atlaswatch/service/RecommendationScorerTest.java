package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.config.RecommendationScoringProperties;
import com.atlasmind.atlaswatch.models.Movie;
import com.atlasmind.atlaswatch.support.TestFixtures;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommendationScorerTest {

    private final RecommendationScoringProperties scoringProperties = new RecommendationScoringProperties();
    private final RecommendationScorer recommendationScorer = new RecommendationScorer(scoringProperties);

    @Test
    void multiMoodScoreMeasuresCoverageOfEachRequestedMood() {
        Set<SoloMood> moods = Set.of(
                SoloMood.DARK,
                SoloMood.EMOTIONAL,
                SoloMood.THOUGHTFUL,
                SoloMood.MIND_BENDING,
                SoloMood.EERIE
        );

        double fullSemanticMatch = recommendationScorer.moodMatchScore(
                moods,
                List.of("Drama", "Horror", "Mystery", "Thriller"),
                List.of("murder mystery", "grief", "moral dilemma", "time loop", "haunted house"),
                "A nonlinear story about identity."
        );
        double partialSemanticMatch = recommendationScorer.moodMatchScore(
                moods,
                List.of("Action", "Crime", "Thriller"),
                List.of("murder", "loss of father"),
                null
        );
        double noMatch = recommendationScorer.moodMatchScore(
                moods,
                List.of("Adventure", "Animation", "Comedy", "Family", "Fantasy")
        );

        assertEquals(1.0, fullSemanticMatch, 1e-9);
        assertEquals(0.4, partialSemanticMatch, 1e-9);
        assertEquals(0.0, noMatch, 1e-9);
    }

    @Test
    void broadDramaAndMysteryDoNotClaimEveryComplexMood() {
        Set<SoloMood> moods = Set.of(
                SoloMood.DARK,
                SoloMood.EMOTIONAL,
                SoloMood.THOUGHTFUL,
                SoloMood.MIND_BENDING,
                SoloMood.EERIE
        );

        assertEquals(0.0, recommendationScorer.moodMatchScore(
                moods,
                List.of("Drama", "Mystery")
        ), 1e-9);
    }

    @Test
    void directGenreMoodsStillWorkWhenTheGenreIsSpecific() {
        assertEquals(1.0, recommendationScorer.moodMatchScore(
                Set.of(SoloMood.FUNNY, SoloMood.ADVENTUROUS),
                List.of("Comedy", "Adventure")
        ), 1e-9);
    }

    @Test
    void semanticCueMatchingUsesPhraseBoundaries() {
        assertEquals(0.0, recommendationScorer.moodMatchScore(
                Set.of(SoloMood.EMOTIONAL),
                List.of("Drama"),
                List.of(),
                "A glossy production about a successful artist."
        ), 1e-9);
        assertEquals(1.0, recommendationScorer.moodMatchScore(
                Set.of(SoloMood.EMOTIONAL),
                List.of("Drama"),
                List.of("loss of child"),
                null
        ), 1e-9);
    }

    @Test
    void freshnessIsOptInInsteadOfAHiddenDefaultPreference() {
        assertEquals(0.0, scoringProperties.getFreshnessWeight(), 1e-9);
    }

    @Test
    void confidenceAdjustedQualityDoesNotTrustTinyVoteSamples() {
        Movie established = TestFixtures.movie(301L, 3001, "Established");
        established.setMovieRating(8.0);
        established.setVoteCount(10_000);

        Movie tinySample = TestFixtures.movie(302L, 3002, "Tiny Sample");
        tinySample.setMovieRating(9.5);
        tinySample.setVoteCount(5);

        assertTrue(recommendationScorer.confidenceAdjustedRating(established)
                > recommendationScorer.confidenceAdjustedRating(tinySample));
        assertTrue(recommendationScorer.qualityScore(established)
                > recommendationScorer.qualityScore(tinySample));
    }

    @Test
    void missingVoteCountFallsBackToPriorInsteadOfRawAverage() {
        Movie unknownConfidence = TestFixtures.movie(303L, 3003, "Unknown Confidence");
        unknownConfidence.setMovieRating(9.8);
        unknownConfidence.setVoteCount(null);

        assertEquals(
                scoringProperties.getQualityPriorMean(),
                recommendationScorer.confidenceAdjustedRating(unknownConfidence),
                1e-9
        );
    }

    @Test
    void priorCanBeDisabledForControlledComparison() {
        scoringProperties.setQualityPriorWeight(0.0);
        Movie movie = TestFixtures.movie(304L, 3004, "Raw Rating Baseline");
        movie.setMovieRating(8.7);
        movie.setVoteCount(1);

        assertEquals(8.7, recommendationScorer.confidenceAdjustedRating(movie), 1e-9);
    }

    @Test
    void candidateSamplingDoesNotHideASecondFreshnessPreference() {
        Movie classic = TestFixtures.movie(201L, 2001, "Classic");
        classic.setMovieRating(8.0);
        classic.setPopularity(40.0);
        classic.setReleaseDate(LocalDate.of(1960, 1, 1));

        Movie recent = TestFixtures.movie(202L, 2002, "Recent");
        recent.setMovieRating(8.0);
        recent.setPopularity(40.0);
        recent.setReleaseDate(LocalDate.now());

        assertEquals(
                recommendationScorer.samplingWeight(new PreparedCandidate(classic, 0.0)),
                recommendationScorer.samplingWeight(new PreparedCandidate(recent, 0.0)),
                1e-9
        );
    }

    @Test
    void genreSimilarityUsesJaccardInsteadOfSubsetMatch() {
        double similarity = recommendationScorer.genreSimilarity(
                List.of("Thriller"),
                List.of("Thriller", "Crime", "Mystery")
        );

        assertEquals(1.0 / 3.0, similarity, 1e-9);
    }

    @Test
    void genreSimilarityNormalizesCaseAndIgnoresDuplicates() {
        double similarity = recommendationScorer.genreSimilarity(
                List.of("Thriller", "thriller", "Crime"),
                List.of("crime", "THRILLER")
        );

        assertEquals(1.0, similarity, 1e-9);
    }

    @Test
    void genreSimilarityReturnsZeroForDisjointOrMissingGenres() {
        assertEquals(0.0, recommendationScorer.genreSimilarity(
                List.of("Thriller"),
                List.of("Comedy")
        ));
        assertEquals(0.0, recommendationScorer.genreSimilarity(
                List.of(),
                List.of("Comedy")
        ));
        assertEquals(0.0, recommendationScorer.genreSimilarity(
                null,
                List.of("Comedy")
        ));
    }

    @Test
    void keywordAffinityContributesWhenGenreSignalsTie() {
        UserTasteProfile profile = new UserTasteProfile(
                java.util.Map.of("science fiction", 0.8),
                java.util.Map.of(),
                java.util.Map.of("science fiction", 0.8),
                java.util.Map.of("time loop", 0.9),
                java.util.Map.of("space opera", 0.8),
                java.util.Map.of("time loop", 0.9, "space opera", -0.8),
                2,
                0
        );

        double keywordMatchScore = recommendationScorer.preferenceAffinityScore(
                profile,
                List.of("Science Fiction"),
                List.of("Time Loop")
        );
        double keywordMismatchScore = recommendationScorer.preferenceAffinityScore(
                profile,
                List.of("Science Fiction"),
                List.of("Space Opera")
        );
        double keywordPenalty = recommendationScorer.dislikedPreferencePenaltyScore(
                profile,
                List.of("Science Fiction"),
                List.of("Space Opera")
        );

        assertTrue(keywordMatchScore > keywordMismatchScore);
        assertTrue(keywordPenalty > 0.0);
    }

    @Test
    void calibrationRerankingPullsListTowardPreferredGenreDistribution() {
        scoringProperties.setCalibrationPenaltyWeight(0.55);

        ScoredGenres thrillerLead = new ScoredGenres("thriller-lead", 0.95, List.of("Thriller"));
        ScoredGenres thrillerFollowUp = new ScoredGenres("thriller-follow-up", 0.94, List.of("Thriller"));
        ScoredGenres dramaAlternative = new ScoredGenres("drama-alt", 0.90, List.of("Drama"));
        ScoredGenres thrillerThird = new ScoredGenres("thriller-third", 0.89, List.of("Thriller"));

        List<ScoredGenres> reranked = recommendationScorer.rerankForCalibration(
                List.of(thrillerLead, thrillerFollowUp, dramaAlternative, thrillerThird),
                3,
                ScoredGenres::score,
                ScoredGenres::genres,
                Map.of("thriller", 0.5, "drama", 0.5)
        );

        assertEquals(List.of("thriller-lead", "drama-alt", "thriller-follow-up"),
                reranked.stream().map(ScoredGenres::id).toList());
    }

    @Test
    void calibrationRerankingSkipsOverfittingWhenOnlyOneTargetGenreExists() {
        scoringProperties.setCalibrationPenaltyWeight(0.80);

        ScoredGenres thrillerLead = new ScoredGenres("thriller-lead", 0.95, List.of("Thriller"));
        ScoredGenres actionDetour = new ScoredGenres("action-detour", 0.90, List.of("Action"));
        ScoredGenres mysteryDetour = new ScoredGenres("mystery-detour", 0.89, List.of("Mystery"));

        List<ScoredGenres> reranked = recommendationScorer.rerankForCalibration(
                List.of(thrillerLead, actionDetour, mysteryDetour),
                3,
                ScoredGenres::score,
                ScoredGenres::genres,
                Map.of("thriller", 1.0)
        );

        assertEquals(List.of("thriller-lead", "action-detour", "mystery-detour"),
                reranked.stream().map(ScoredGenres::id).toList());
    }

    private record ScoredGenres(String id, double score, List<String> genres) {
    }
}
