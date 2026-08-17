package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.config.RecommendationScoringProperties;
import com.atlasmind.atlaswatch.dto.request.RecommendationRequestDto;
import com.atlasmind.atlaswatch.dto.response.RecommendationResponseDto;
import com.atlasmind.atlaswatch.models.*;
import com.atlasmind.atlaswatch.repository.*;
import com.atlasmind.atlaswatch.support.TestFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Offline evaluation harness for the recommendation engine.
 * <p>
 * Sets up a synthetic catalog and multiple users with different taste
 * profiles, generates recommendations for each, and computes
 * quantitative metrics using {@link RecommendationEvaluator}.
 * <p>
 * Run this test after recommendation algorithm changes to compare
 * the new metrics against the baseline in {@code docs/evaluation-baseline.md}.
 */
@ExtendWith(MockitoExtension.class)
class RecommendationEvaluationTest {

    @Mock private WatchlistRepository watchlistRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private MovieGenreRepository movieGenreRepository;
    @Mock private MovieKeywordRepository movieKeywordRepository;
    @Mock private MovieRepository movieRepository;
    @Mock private RecommendationImpressionRepository recommendationImpressionRepository;
    @Mock private CatalogIngestionService catalogIngestionService;
    @Mock private CollaborativeSimilarityService collaborativeSimilarityService;
    @Spy  private UserTasteProfileService userTasteProfileService = new UserTasteProfileService();
    @Spy  private RecommendationScoringProperties scoringProperties = new RecommendationScoringProperties();

    private RecommendationService recommendationService;

    // ── catalog ──
    private final List<Movie> catalog = new ArrayList<>();
    private final List<MovieGenre> allMovieGenres = new ArrayList<>();

    // ── per-user data ──
    private final Map<Long, List<WatchList>> watchlistsByUserId = new LinkedHashMap<>();
    private final Map<Long, List<Review>> reviewsByUserId = new LinkedHashMap<>();
    private final Map<Long, List<Long>> watchedIdsByUserId = new LinkedHashMap<>();
    private final Map<Long, Set<Long>> futurePositivesByUserId = new LinkedHashMap<>();

    // ── genres ──
    private Genre thriller, crime, comedy, drama, sciFi, action, romance, mystery;

    @BeforeEach
    void setUp() {
        RecommendationScorer scorer = new RecommendationScorer(scoringProperties);
        RecommendationReasonBuilder reasonBuilder = new RecommendationReasonBuilder(scoringProperties);
        CacheManager cacheManager = new ConcurrentMapCacheManager(
                "recommendations", "coldStartRecommendations", "userProfiles");
        ContentSimilarityService contentSimilarityService = new ContentSimilarityService();
        CandidateRetriever candidateRetriever = new CandidateRetriever(
                movieGenreRepository, movieKeywordRepository, movieRepository, scorer, contentSimilarityService);
        recommendationService = new RecommendationService(
                watchlistRepository, reviewRepository, movieGenreRepository, movieKeywordRepository, movieRepository,
                recommendationImpressionRepository, catalogIngestionService, userTasteProfileService,
                scorer, reasonBuilder, candidateRetriever, collaborativeSimilarityService,
                cacheManager, new SimpleMeterRegistry());

        createGenres();
        createCatalog();
        createUsers();
        configureMocks();
        lenient().when(movieKeywordRepository.findByMovieIdInWithKeyword(anyCollection())).thenReturn(List.of());
        lenient().when(collaborativeSimilarityService.scoreCandidates(anyList())).thenReturn(Map.of());
    }

    // ═══════════════════════════════════════════════════════
    //  Evaluation harness
    // ═══════════════════════════════════════════════════════

    @Test
    void evaluationHarnessProducesBaselineMetrics() {
        List<RecommendationEvaluator.UserEvaluation> evaluations = new ArrayList<>();

        for (long userId : reviewsByUserId.keySet()) {
            User user = TestFixtures.user(userId, "user" + userId, "user" + userId + "@test.com");
            RecommendationRequestDto request = new RecommendationRequestDto(List.of("any"), "any", 5);

            List<RecommendationResponseDto> results = recommendationService.getRecommendations(user, request);

            List<RecommendationEvaluator.RecommendedMovie> recommendedMovies = results.stream()
                    .map(r -> new RecommendationEvaluator.RecommendedMovie(
                            findMovieIdByTmdbId(r.getTmdbId()),
                            r.getGenres() != null ? r.getGenres() : List.of()))
                    .toList();

            evaluations.add(new RecommendationEvaluator.UserEvaluation(
                    userId, recommendedMovies,
                    futurePositivesByUserId.getOrDefault(userId, Set.of())));
        }

        RecommendationEvaluator.EvaluationReport report =
                RecommendationEvaluator.evaluate(evaluations, catalog.size());

        System.out.println(report.toFormattedReport());

        // ── sanity assertions ──
        assertTrue(report.usersEvaluated() >= 3, "should evaluate at least 3 users");
        assertEquals(reviewsByUserId.size() * 5, report.totalRecommendations(),
                "each synthetic user should receive a full recommendation list");
        assertEquals(1.0, report.hitRate(), 1e-9,
                "each synthetic user should get at least one relevant recommendation");
        assertTrue(report.meanPrecisionAtK() >= 0.25,
                "precision should stay above the baseline guardrail");
        assertTrue(report.meanRecallAtK() >= 0.50,
                "the shortlist should retrieve at least half of the known relevant movies");
        assertTrue(report.meanReciprocalRank() >= 0.40,
                "the first relevant result should usually appear near the top");
        assertTrue(report.meanNdcgAtK() >= 0.40,
                "relevant results should remain concentrated near the top of the list");
        assertTrue(report.catalogCoverage() >= 0.60,
                "recommendations should cover a meaningful part of the catalog");
        assertTrue(report.meanGenreDiversity() >= 0.35,
                "recommendations should remain reasonably diverse");
        assertTrue(report.meanIntraListSimilarity() <= 0.50,
                "lists should not collapse into a highly homogeneous cluster");
    }

    @Test
    void coldStartEvaluationProducesRecommendationsWithoutUserHistory() {
        RecommendationRequestDto tenseRequest = new RecommendationRequestDto(List.of("tense"), "any", 5);
        RecommendationRequestDto comfortingRequest = new RecommendationRequestDto(List.of("comforting"), "any", 5);

        List<RecommendationResponseDto> tenseResults =
                recommendationService.getColdStartRecommendations(tenseRequest);
        List<RecommendationResponseDto> comfortingResults =
                recommendationService.getColdStartRecommendations(comfortingRequest);

        assertFalse(tenseResults.isEmpty(), "tense cold-start should return results");
        assertFalse(comfortingResults.isEmpty(), "comforting cold-start should return results");

        // Different moods should produce at least partially different lists
        Set<Integer> tenseIds = tenseResults.stream()
                .map(RecommendationResponseDto::getTmdbId).collect(Collectors.toSet());
        Set<Integer> comfortingIds = comfortingResults.stream()
                .map(RecommendationResponseDto::getTmdbId).collect(Collectors.toSet());
        assertNotEquals(tenseIds, comfortingIds,
                "different moods should produce at least partially different results");
    }

    @Test
    void coldStartStarterHintsSteerResultsTowardSeededCluster() {
        RecommendationRequestDto baselineRequest = new RecommendationRequestDto(List.of("tense"), "any", 5);
        RecommendationRequestDto guidedRequest = new RecommendationRequestDto(List.of("tense"), "any", 5);
        guidedRequest.setStarterGenres(List.of("science fiction"));
        guidedRequest.setSeedTmdbIds(List.of(1011));

        List<RecommendationResponseDto> baselineResults =
                recommendationService.getColdStartRecommendations(baselineRequest);
        List<RecommendationResponseDto> guidedResults =
                recommendationService.getColdStartRecommendations(guidedRequest);

        long baselineSciFiCount = countRecommendationsWithGenre(baselineResults, "Science Fiction");
        long guidedSciFiCount = countRecommendationsWithGenre(guidedResults, "Science Fiction");

        assertTrue(guidedSciFiCount >= baselineSciFiCount,
                "starter hints should not reduce sci-fi alignment for a sci-fi seeded cold-start request");
        assertTrue(guidedSciFiCount >= 2,
                "a sci-fi seeded cold-start request should surface multiple sci-fi recommendations");
        assertTrue(guidedResults.stream()
                        .map(RecommendationResponseDto::getTmdbId)
                        .anyMatch(tmdbId -> tmdbId == 1012 || tmdbId == 1013),
                "a sci-fi seed should pull in related sci-fi neighbors, not only the most globally popular titles");
    }

    // ═══════════════════════════════════════════════════════
    //  Evaluator unit tests
    // ═══════════════════════════════════════════════════════

    @Test
    void evaluatorComputesHitRateCorrectly() {
        var hit = new RecommendationEvaluator.UserEvaluation(1L,
                List.of(new RecommendationEvaluator.RecommendedMovie(10L, List.of("Thriller")),
                        new RecommendationEvaluator.RecommendedMovie(20L, List.of("Drama"))),
                Set.of(10L, 30L));
        var miss = new RecommendationEvaluator.UserEvaluation(2L,
                List.of(new RecommendationEvaluator.RecommendedMovie(40L, List.of("Comedy"))),
                Set.of(50L));

        var report = RecommendationEvaluator.evaluate(List.of(hit, miss), 50);

        assertEquals(0.5, report.hitRate());
        assertEquals(1, report.totalHits());
    }

    @Test
    void evaluatorComputesCatalogCoverageCorrectly() {
        var user1 = new RecommendationEvaluator.UserEvaluation(1L,
                List.of(new RecommendationEvaluator.RecommendedMovie(1L, List.of()),
                        new RecommendationEvaluator.RecommendedMovie(2L, List.of())),
                Set.of());
        var user2 = new RecommendationEvaluator.UserEvaluation(2L,
                List.of(new RecommendationEvaluator.RecommendedMovie(2L, List.of()),
                        new RecommendationEvaluator.RecommendedMovie(3L, List.of())),
                Set.of());

        var report = RecommendationEvaluator.evaluate(List.of(user1, user2), 10);

        assertEquals(3, report.uniqueMoviesRecommended());
        assertEquals(0.3, report.catalogCoverage(), 0.001);
    }

    @Test
    void evaluatorComputesGenreDiversityCorrectly() {
        // All genres different → diversity 1.0
        var diverseRecs = List.of(
                new RecommendationEvaluator.RecommendedMovie(1L, List.of("Thriller")),
                new RecommendationEvaluator.RecommendedMovie(2L, List.of("Comedy")),
                new RecommendationEvaluator.RecommendedMovie(3L, List.of("Drama")));
        assertEquals(1.0, RecommendationEvaluator.genreDiversity(diverseRecs));

        // All genres identical → diversity < 1.0
        var homogeneousRecs = List.of(
                new RecommendationEvaluator.RecommendedMovie(1L, List.of("Thriller")),
                new RecommendationEvaluator.RecommendedMovie(2L, List.of("Thriller")));
        assertEquals(0.5, RecommendationEvaluator.genreDiversity(homogeneousRecs));
    }

    @Test
    void evaluatorComputesRankSensitiveAndGradedMetrics() {
        var evaluation = new RecommendationEvaluator.UserEvaluation(
                1L,
                List.of(
                        new RecommendationEvaluator.RecommendedMovie(10L, List.of("Thriller")),
                        new RecommendationEvaluator.RecommendedMovie(20L, List.of("Comedy")),
                        new RecommendationEvaluator.RecommendedMovie(30L, List.of("Drama"))),
                Set.of(10L, 30L, 40L),
                Map.of(10L, 3, 20L, 0, 30L, 2, 40L, 1));

        var report = RecommendationEvaluator.evaluate(List.of(evaluation), 50);

        assertEquals(2.0 / 3.0, report.meanPrecisionAtK(), 1e-9);
        assertEquals(2.0 / 3.0, report.meanRecallAtK(), 1e-9);
        assertEquals(1.0, report.meanReciprocalRank(), 1e-9);
        assertEquals(8.5 / (7.0 + (3.0 / log2(3)) + 0.5), report.meanNdcgAtK(), 1e-9);
    }

    @Test
    void evaluatorReportsConstraintViolationRates() {
        var moodViolation = new RecommendationEvaluator.RecommendedMovie(
                1L,
                List.of("Comedy"),
                new RecommendationEvaluator.RecommendationConstraints(false, true, true));
        var runtimeAndEraViolation = new RecommendationEvaluator.RecommendedMovie(
                2L,
                List.of("Drama"),
                new RecommendationEvaluator.RecommendationConstraints(true, false, false));
        var evaluation = new RecommendationEvaluator.UserEvaluation(
                1L, List.of(moodViolation, runtimeAndEraViolation), Set.of(1L));

        var report = RecommendationEvaluator.evaluate(List.of(evaluation), 10);

        assertEquals(0.5, report.moodConstraintViolationRate(), 1e-9);
        assertEquals(0.5, report.runtimeConstraintViolationRate(), 1e-9);
        assertEquals(0.5, report.eraConstraintViolationRate(), 1e-9);
    }

    @Test
    void jaccardSimilarityIsSymmetricAndBounded() {
        assertEquals(1.0, RecommendationEvaluator.jaccardSimilarity(
                List.of("Thriller", "Crime"), List.of("Thriller", "Crime")));
        assertEquals(0.0, RecommendationEvaluator.jaccardSimilarity(
                List.of("Thriller"), List.of("Comedy")));
        assertEquals(0.0, RecommendationEvaluator.jaccardSimilarity(
                List.of(), List.of()));

        double ab = RecommendationEvaluator.jaccardSimilarity(
                List.of("Thriller", "Crime"), List.of("Crime", "Drama"));
        double ba = RecommendationEvaluator.jaccardSimilarity(
                List.of("Crime", "Drama"), List.of("Thriller", "Crime"));
        assertEquals(ab, ba, 1e-9, "Jaccard should be symmetric");
        assertTrue(ab >= 0 && ab <= 1, "Jaccard should be in [0, 1]");
    }

    @Test
    void emptyEvaluationsProduceZeroReport() {
        var report = RecommendationEvaluator.evaluate(List.of(), 100);

        assertEquals(0, report.usersEvaluated());
        assertEquals(0, report.totalRecommendations());
        assertEquals(0, report.hitRate());
    }

    // ═══════════════════════════════════════════════════════
    //  Test data setup
    // ═══════════════════════════════════════════════════════

    private void createGenres() {
        thriller = TestFixtures.genre(1L, 53, "Thriller");
        crime    = TestFixtures.genre(2L, 80, "Crime");
        comedy   = TestFixtures.genre(3L, 35, "Comedy");
        drama    = TestFixtures.genre(4L, 18, "Drama");
        sciFi    = TestFixtures.genre(5L, 878, "Science Fiction");
        action   = TestFixtures.genre(6L, 28, "Action");
        romance  = TestFixtures.genre(7L, 10749, "Romance");
        mystery  = TestFixtures.genre(8L, 9648, "Mystery");
    }

    private void createCatalog() {
        // Thriller cluster
        addMovie(1L, 1001, "Dark Signal", 8.2, 140.0, "A detective hunts a shadowy figure across the city.", thriller, crime);
        addMovie(2L, 1002, "Night Wire", 7.9, 120.0, "Intercepted transmissions reveal a conspiracy.", thriller, mystery);
        addMovie(3L, 1003, "Cold Pursuit", 7.6, 110.0, "A father takes justice into his own hands.", thriller, action);
        addMovie(4L, 1004, "Silent Target", 8.0, 130.0, "An assassin is hired to eliminate a crime boss.", thriller, crime);

        // Comedy / Drama cluster
        addMovie(5L, 1005, "Laugh Track", 7.8, 115.0, "A stand-up comedian navigates fame and failure.", comedy);
        addMovie(6L, 1006, "Summer Fools", 7.5, 95.0, "Friends reunite for a chaotic summer road trip.", comedy, romance);
        addMovie(7L, 1007, "Open Mic Night", 7.3, 85.0, "Amateur comedians compete for a life-changing gig.", comedy, drama);

        // Drama cluster
        addMovie(8L, 1008, "Quiet River", 8.5, 105.0, "A retired teacher reflects on a life of missed chances.", drama);
        addMovie(9L, 1009, "Broken Promise", 8.1, 100.0, "A couple confronts secrets that threaten their marriage.", drama, romance);
        addMovie(10L, 1010, "Still Waters", 7.7, 90.0, "A small-town mystery unfolds along a quiet river.", drama, mystery);

        // Sci-Fi cluster
        addMovie(11L, 1011, "Orbit Station", 8.3, 160.0, "Astronauts on a failing space station fight to survive.", sciFi, action);
        addMovie(12L, 1012, "Clone Signal", 7.8, 125.0, "A scientist discovers her clones are hunting her.", sciFi, thriller);
        addMovie(13L, 1013, "Void Walker", 8.0, 135.0, "A pilot investigates strange signals from deep space.", sciFi, mystery);

        // Action / Crime
        addMovie(14L, 1014, "Strike Force", 7.4, 180.0, "An elite unit dismantles a smuggling ring.", action, thriller);
        addMovie(15L, 1015, "Highway Chase", 7.1, 150.0, "A rogue cop pursues criminals across state lines.", action, crime);
    }

    private void addMovie(Long id, int tmdbId, String title, double rating, double popularity,
                           String overview, Genre... genres) {
        Movie m = TestFixtures.movie(id, tmdbId, title);
        m.setMovieRating(rating);
        m.setPopularity(popularity);
        m.setRuntime(110);
        m.setOverview(overview);
        catalog.add(m);
        for (Genre g : genres) {
            allMovieGenres.add(new MovieGenre(m, g));
        }
    }

    private void createUsers() {
        // ── Alice: Thriller / Crime enthusiast ──
        User alice = TestFixtures.user(1L, "alice", "alice@test.com");
        Review aliceR1 = TestFixtures.review(101L, alice, catalog.get(0)); // Dark Signal
        aliceR1.setRating(9);
        Review aliceR2 = TestFixtures.review(102L, alice, catalog.get(3)); // Silent Target
        aliceR2.setRating(8);
        reviewsByUserId.put(1L, List.of(aliceR1, aliceR2));
        watchlistsByUserId.put(1L, List.of());
        watchedIdsByUserId.put(1L, List.of());
        futurePositivesByUserId.put(1L, Set.of(2L, 3L)); // Night Wire, Cold Pursuit

        // ── Bob: Comedy / Drama fan ──
        User bob = TestFixtures.user(2L, "bob", "bob@test.com");
        Review bobR1 = TestFixtures.review(201L, bob, catalog.get(4)); // Laugh Track
        bobR1.setRating(9);
        Review bobR2 = TestFixtures.review(202L, bob, catalog.get(7)); // Quiet River
        bobR2.setRating(8);
        WatchList bobW1 = TestFixtures.watchList(211L, bob, catalog.get(8), WatchListStatus.PLAN_TO_WATCH);
        bobW1.setAddedAt(LocalDateTime.now().minusDays(30));
        reviewsByUserId.put(2L, List.of(bobR1, bobR2));
        watchlistsByUserId.put(2L, List.of(bobW1));
        watchedIdsByUserId.put(2L, List.of());
        futurePositivesByUserId.put(2L, Set.of(6L, 7L, 9L)); // Summer Fools, Open Mic Night, Broken Promise

        // ── Carol: Sci-Fi / Action lover ──
        User carol = TestFixtures.user(3L, "carol", "carol@test.com");
        Review carolR1 = TestFixtures.review(301L, carol, catalog.get(10)); // Orbit Station
        carolR1.setRating(9);
        WatchList carolW1 = TestFixtures.watchList(311L, carol, catalog.get(13), WatchListStatus.PLAN_TO_WATCH);
        carolW1.setAddedAt(LocalDateTime.now().minusDays(60));
        reviewsByUserId.put(3L, List.of(carolR1));
        watchlistsByUserId.put(3L, List.of(carolW1));
        watchedIdsByUserId.put(3L, List.of());
        futurePositivesByUserId.put(3L, Set.of(12L, 13L, 15L)); // Clone Signal, Void Walker, Highway Chase

        // ── Dave: Drama / Mystery with some watched movies ──
        User dave = TestFixtures.user(4L, "dave", "dave@test.com");
        Review daveR1 = TestFixtures.review(401L, dave, catalog.get(9)); // Still Waters
        daveR1.setRating(9);
        Review daveR2 = TestFixtures.review(402L, dave, catalog.get(1)); // Night Wire
        daveR2.setRating(7);
        WatchList daveW1 = TestFixtures.watchList(411L, dave, catalog.get(7), WatchListStatus.WATCHED);
        reviewsByUserId.put(4L, List.of(daveR1, daveR2));
        watchlistsByUserId.put(4L, List.of(daveW1));
        watchedIdsByUserId.put(4L, List.of(8L)); // Quiet River watched
        futurePositivesByUserId.put(4L, Set.of(4L, 9L)); // Silent Target, Broken Promise
    }

    private void configureMocks() {
        // ── per-user data via thenAnswer ──
        lenient().when(watchlistRepository.findByUserIdWithDetails(anyLong())).thenAnswer(inv ->
                watchlistsByUserId.getOrDefault(inv.<Long>getArgument(0), List.of()));

        lenient().when(reviewRepository.findByUserIdWithDetails(anyLong())).thenAnswer(inv ->
                reviewsByUserId.getOrDefault(inv.<Long>getArgument(0), List.of()));

        lenient().when(watchlistRepository.findMovieIdsByUserIdAndStatus(anyLong(), eq(WatchListStatus.WATCHED)))
                .thenAnswer(inv -> watchedIdsByUserId.getOrDefault(inv.<Long>getArgument(0), List.of()));

        // ── genre lookup (universal) ──
        lenient().when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection())).thenAnswer(inv -> {
            Collection<Long> movieIds = inv.getArgument(0);
            return allMovieGenres.stream()
                    .filter(mg -> movieIds.contains(mg.getMovie().getId()))
                    .toList();
        });

        // ── candidate retrieval channels ──
        List<Movie> byPopularity = catalog.stream()
                .sorted(Comparator.comparingDouble(Movie::getPopularity).reversed()).toList();
        List<Movie> byRating = catalog.stream()
                .sorted(Comparator.comparingDouble(Movie::getMovieRating).reversed()).toList();

        lenient().when(movieGenreRepository.findDistinctRecommendationReadyMoviesByGenreNames(
                anyCollection(), anyDouble(), any(Pageable.class))).thenReturn(catalog);
        lenient().when(movieGenreRepository.findDistinctRecommendationReadyMoviesByGenreNamesExcluding(
                anyCollection(), anyDouble(), anyCollection(), any(Pageable.class))).thenAnswer(inv -> {
            Collection<Long> excluded = inv.getArgument(2);
            return catalog.stream().filter(m -> !excluded.contains(m.getId())).toList();
        });

        lenient().when(movieRepository.findRecommendationReadyPopularMovies(anyDouble(), any(Pageable.class)))
                .thenReturn(byPopularity);
        lenient().when(movieRepository.findRecommendationReadyPopularMoviesExcluding(
                anyDouble(), anyCollection(), any(Pageable.class))).thenAnswer(inv -> {
            Collection<Long> excluded = inv.getArgument(1);
            return byPopularity.stream().filter(m -> !excluded.contains(m.getId())).toList();
        });

        lenient().when(movieRepository.findRecommendationReadyTopRatedMovies(
                        anyDouble(), anyDouble(), anyDouble(), any(Pageable.class)))
                .thenReturn(byRating);
        lenient().when(movieRepository.findRecommendationReadyTopRatedMoviesExcluding(
                anyDouble(), anyDouble(), anyDouble(), anyCollection(), any(Pageable.class))).thenAnswer(inv -> {
            Collection<Long> excluded = inv.getArgument(3);
            return byRating.stream().filter(m -> !excluded.contains(m.getId())).toList();
        });

        lenient().when(movieRepository.findRecommendationReadyMovies(anyDouble())).thenReturn(catalog);
        lenient().when(movieRepository.findRecommendationReadyMoviesExcluding(anyDouble(), anyCollection()))
                .thenAnswer(inv -> {
                    Collection<Long> excluded = inv.getArgument(1);
                    return catalog.stream().filter(m -> !excluded.contains(m.getId())).toList();
                });

        // ── impressions (none) ──
        lenient().when(recommendationImpressionRepository.findMovieIdsWithAtLeastImpressionsSince(
                anyLong(), any(LocalDateTime.class), anyLong())).thenReturn(List.of());

        // ── for cache-hit impression replay ──
        lenient().when(movieRepository.findByTmdbIdIn(anyList())).thenAnswer(inv -> {
            List<Integer> tmdbIds = inv.getArgument(0);
            return catalog.stream().filter(m -> tmdbIds.contains(m.getTmdbId())).toList();
        });
    }

    private long findMovieIdByTmdbId(int tmdbId) {
        return catalog.stream()
                .filter(m -> m.getTmdbId() == tmdbId)
                .map(Movie::getId)
                .findFirst()
                .orElse(-1L);
    }

    private long countRecommendationsWithGenre(List<RecommendationResponseDto> results, String genreName) {
        return results.stream()
                .filter(result -> result.getGenres() != null && result.getGenres().stream().anyMatch(genreName::equalsIgnoreCase))
                .count();
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }
}
