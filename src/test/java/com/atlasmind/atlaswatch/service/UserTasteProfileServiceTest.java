package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.models.MovieGenre;
import com.atlasmind.atlaswatch.models.MovieKeyword;
import com.atlasmind.atlaswatch.support.TestFixtures;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserTasteProfileServiceTest {

    private final UserTasteProfileService userTasteProfileService = new UserTasteProfileService();

    @Test
    void buildProfileCombinesPositiveNegativeAndWatchlistSignals() {
        var user = TestFixtures.user(1L, "alice", "alice@example.com");

        var lovedMovie = TestFixtures.movie(10L, 110, "Loved Thriller");
        var dislikedMovie = TestFixtures.movie(11L, 111, "Disliked Romance");
        var watchlistMovie = TestFixtures.movie(12L, 112, "Queued Sci-Fi");

        var thriller = TestFixtures.genre(1L, 53, "Thriller");
        var mystery = TestFixtures.genre(2L, 9648, "Mystery");
        var romance = TestFixtures.genre(3L, 10749, "Romance");
        var scienceFiction = TestFixtures.genre(4L, 878, "Science Fiction");

        var positiveReview = TestFixtures.review(20L, user, lovedMovie);
        positiveReview.setRating(9);
        positiveReview.setReviewText("Smart and gripping.");

        var negativeReview = TestFixtures.review(21L, user, dislikedMovie);
        negativeReview.setRating(3);
        negativeReview.setReviewText("Not for me.");

        var watchlistEntry = TestFixtures.watchList(30L, user, watchlistMovie,
                com.atlasmind.atlaswatch.models.WatchListStatus.PLAN_TO_WATCH);

        Map<Long, MovieSignalFeatures> signalFeaturesByMovieId = signalFeaturesByMovieId(
                new MovieGenre(lovedMovie, thriller),
                new MovieGenre(lovedMovie, mystery),
                new MovieGenre(dislikedMovie, romance),
                new MovieGenre(watchlistMovie, scienceFiction),
                TestFixtures.movieKeyword(lovedMovie, TestFixtures.keyword(100L, 5001, "mind-bending")),
                TestFixtures.movieKeyword(dislikedMovie, TestFixtures.keyword(101L, 5002, "love triangle")),
                TestFixtures.movieKeyword(watchlistMovie, TestFixtures.keyword(102L, 5003, "space travel"))
        );

        UserTasteProfile profile = userTasteProfileService.buildProfile(
                List.of(positiveReview, negativeReview),
                List.of(watchlistEntry),
                signalFeaturesByMovieId
        );

        assertFalse(profile.isColdStart());
        assertEquals(2, profile.reviewSignalCount());
        assertEquals(1, profile.watchlistSignalCount());
        assertTrue(profile.positiveWeight("thriller") > profile.positiveWeight("science fiction"));
        assertTrue(profile.negativeWeight("romance") > 0.0);
        assertTrue(profile.netWeight("thriller") > 0.0);
        assertTrue(profile.netWeight("romance") < 0.0);
        assertTrue(profile.positiveKeywordWeight("mind-bending") > profile.positiveKeywordWeight("space travel"));
        assertTrue(profile.netKeywordWeight("love triangle") < 0.0);
        assertTrue(profile.topPositiveGenres(2).contains("thriller"));
        assertTrue(profile.positiveGenreWeights().values().stream().allMatch(value -> value >= 0.0 && value <= 1.0));
        assertTrue(profile.negativeGenreWeights().values().stream().allMatch(value -> value >= 0.0 && value <= 1.0));
        assertTrue(profile.positiveKeywordWeights().values().stream().allMatch(value -> value >= 0.0 && value <= 1.0));
        assertTrue(profile.negativeKeywordWeights().values().stream().allMatch(value -> value >= 0.0 && value <= 1.0));
    }

    @Test
    void buildProfileReturnsColdStartWithoutUsableSignals() {
        UserTasteProfile profile = userTasteProfileService.buildProfile(List.of(), List.of(), Map.of());

        assertTrue(profile.isColdStart());
        assertFalse(profile.hasSignals());
        assertTrue(profile.positiveGenreWeights().isEmpty());
        assertTrue(profile.negativeGenreWeights().isEmpty());
        assertTrue(profile.netGenreWeights().isEmpty());
        assertTrue(profile.positiveKeywordWeights().isEmpty());
        assertTrue(profile.netKeywordWeights().isEmpty());
    }

    @Test
    void recencyMultiplierDecaysWithAgeAndRespectsFloor() {
        LocalDateTime now = LocalDateTime.now();

        double recentWeight = userTasteProfileService.recencyMultiplier(now.minusDays(7), now);
        double olderWeight = userTasteProfileService.recencyMultiplier(now.minusDays(180), now);
        double veryOldWeight = userTasteProfileService.recencyMultiplier(now.minusDays(5000), now);

        assertTrue(recentWeight < 1.0);
        assertTrue(recentWeight > olderWeight);
        assertEquals(UserTasteProfileService.RECENCY_FLOOR, veryOldWeight, 1e-9);
    }

    @Test
    void buildProfilePrefersRecentSignalsOverStaleOnes() {
        var user = TestFixtures.user(1L, "alice", "alice@example.com");

        var oldMovie = TestFixtures.movie(20L, 120, "Old Favorite");
        var recentMovie = TestFixtures.movie(21L, 121, "Recent Favorite");

        var thriller = TestFixtures.genre(10L, 53, "Thriller");
        var comedy = TestFixtures.genre(11L, 35, "Comedy");

        var oldReview = TestFixtures.review(40L, user, oldMovie);
        oldReview.setRating(9);
        oldReview.setCreatedAt(LocalDateTime.now().minusDays(540));

        var recentReview = TestFixtures.review(41L, user, recentMovie);
        recentReview.setRating(9);
        recentReview.setCreatedAt(LocalDateTime.now().minusDays(5));

        UserTasteProfile profile = userTasteProfileService.buildProfile(
                List.of(oldReview, recentReview),
                List.of(),
                signalFeaturesByMovieId(
                        new MovieGenre(oldMovie, thriller),
                        new MovieGenre(recentMovie, comedy),
                        TestFixtures.movieKeyword(oldMovie, TestFixtures.keyword(200L, 6001, "neo-noir")),
                        TestFixtures.movieKeyword(recentMovie, TestFixtures.keyword(201L, 6002, "found family"))
                )
        );

        assertTrue(profile.positiveWeight("comedy") > profile.positiveWeight("thriller"));
        assertTrue(profile.positiveKeywordWeight("found family") > profile.positiveKeywordWeight("neo-noir"));
        assertNotEquals(profile.positiveWeight("comedy"), profile.positiveWeight("thriller"));
    }

    @Test
    void buildProfileDoesNotMaxOutIsolatedStaleSignals() {
        var user = TestFixtures.user(1L, "alice", "alice@example.com");
        var oldMovie = TestFixtures.movie(30L, 130, "Distant Favorite");
        var thriller = TestFixtures.genre(12L, 53, "Thriller");

        var oldReview = TestFixtures.review(50L, user, oldMovie);
        oldReview.setRating(9);
        oldReview.setCreatedAt(LocalDateTime.now().minusDays(720));

        UserTasteProfile profile = userTasteProfileService.buildProfile(
                List.of(oldReview),
                List.of(),
                signalFeaturesByMovieId(
                        new MovieGenre(oldMovie, thriller),
                        TestFixtures.movieKeyword(oldMovie, TestFixtures.keyword(300L, 7001, "time travel"))
                )
        );

        assertTrue(profile.positiveWeight("thriller") > 0.0);
        assertTrue(profile.positiveWeight("thriller") < 0.5,
                "A lone stale signal should be meaningfully decayed instead of normalizing back to 1.0");
        assertTrue(profile.positiveKeywordWeight("time travel") > 0.0);
    }

    @Test
    void buildBootstrapProfileUsesStarterGenresKeywordsAndSeedMovies() {
        UserTasteProfile profile = userTasteProfileService.buildBootstrapProfile(
                List.of("thriller"),
                List.of("time loop"),
                List.of(new MovieSignalFeatures(
                        List.of("Thriller", "Mystery"),
                        List.of("Time Loop", "Paradox")
                ))
        );

        assertFalse(profile.isColdStart());
        assertTrue(profile.positiveWeight("thriller") > 0.0);
        assertTrue(profile.positiveWeight("mystery") > 0.0);
        assertTrue(profile.positiveKeywordWeight("time loop") > 0.0);
        assertTrue(profile.positiveKeywordWeight("paradox") > 0.0);
        assertEquals(0.0, profile.negativeWeight("thriller"));
    }

    private Map<Long, MovieSignalFeatures> signalFeaturesByMovieId(
            MovieGenre[] movieGenres,
            MovieKeyword[] movieKeywords
    ) {
        Map<Long, List<String>> genresByMovieId = new LinkedHashMap<>();
        for (MovieGenre movieGenre : movieGenres) {
            genresByMovieId.computeIfAbsent(movieGenre.getMovie().getId(), ignored -> new java.util.ArrayList<>())
                    .add(movieGenre.getGenre().getName());
        }
        Map<Long, List<String>> keywordsByMovieId = new LinkedHashMap<>();
        for (MovieKeyword movieKeyword : movieKeywords) {
            keywordsByMovieId.computeIfAbsent(movieKeyword.getMovie().getId(), ignored -> new java.util.ArrayList<>())
                    .add(movieKeyword.getKeyword().getName());
        }

        Map<Long, MovieSignalFeatures> signalFeaturesByMovieId = new LinkedHashMap<>();
        for (Long movieId : genresByMovieId.keySet()) {
            signalFeaturesByMovieId.put(movieId, new MovieSignalFeatures(
                    List.copyOf(genresByMovieId.getOrDefault(movieId, List.of())),
                    List.copyOf(keywordsByMovieId.getOrDefault(movieId, List.of()))
            ));
        }
        for (Long movieId : keywordsByMovieId.keySet()) {
            signalFeaturesByMovieId.putIfAbsent(movieId, new MovieSignalFeatures(
                    List.copyOf(genresByMovieId.getOrDefault(movieId, List.of())),
                    List.copyOf(keywordsByMovieId.getOrDefault(movieId, List.of()))
            ));
        }
        return signalFeaturesByMovieId;
    }

    private Map<Long, MovieSignalFeatures> signalFeaturesByMovieId(Object... signals) {
        List<MovieGenre> movieGenres = new java.util.ArrayList<>();
        List<MovieKeyword> movieKeywords = new java.util.ArrayList<>();
        for (Object signal : signals) {
            if (signal instanceof MovieGenre movieGenre) {
                movieGenres.add(movieGenre);
            } else if (signal instanceof MovieKeyword movieKeyword) {
                movieKeywords.add(movieKeyword);
            }
        }
        return signalFeaturesByMovieId(movieGenres.toArray(MovieGenre[]::new), movieKeywords.toArray(MovieKeyword[]::new));
    }
}

