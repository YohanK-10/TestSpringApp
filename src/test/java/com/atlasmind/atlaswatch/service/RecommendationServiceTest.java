package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.config.RecommendationScoringProperties;
import com.atlasmind.atlaswatch.dto.request.RecommendationRequestDto;
import com.atlasmind.atlaswatch.dto.request.SoloRecommendationRequestDto;
import com.atlasmind.atlaswatch.dto.response.RecommendationResponseDto;
import com.atlasmind.atlaswatch.dto.response.SoloRecommendationResponseDto;
import com.atlasmind.atlaswatch.models.*;
import com.atlasmind.atlaswatch.repository.*;
import com.atlasmind.atlaswatch.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock
    private WatchlistRepository watchlistRepository;
    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private MovieGenreRepository movieGenreRepository;
    @Mock
    private MovieKeywordRepository movieKeywordRepository;
    @Mock
    private MovieRepository movieRepository;
    @Mock
    private RecommendationImpressionRepository recommendationImpressionRepository;
    @Mock
    private CatalogIngestionService catalogIngestionService;
    @Mock
    private CollaborativeSimilarityService collaborativeSimilarityService;
    @Spy
    private UserTasteProfileService userTasteProfileService = new UserTasteProfileService();
    @Spy
    private RecommendationScoringProperties recommendationScoringProperties = new RecommendationScoringProperties();
    private RecommendationScorer recommendationScorer;
    private RecommendationReasonBuilder recommendationReasonBuilder;
    private RecommendationService recommendationService;
    private CacheManager cacheManager;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        recommendationScorer = new RecommendationScorer(recommendationScoringProperties);
        recommendationReasonBuilder = new RecommendationReasonBuilder(recommendationScoringProperties);
        cacheManager = new ConcurrentMapCacheManager("recommendations", "coldStartRecommendations", "userProfiles");
        meterRegistry = new SimpleMeterRegistry();
        ContentSimilarityService contentSimilarityService = new ContentSimilarityService();
        CandidateRetriever candidateRetriever = new CandidateRetriever(
                movieGenreRepository,
                movieKeywordRepository,
                movieRepository,
                recommendationScorer,
                contentSimilarityService
        );
        recommendationService = new RecommendationService(
                watchlistRepository,
                reviewRepository,
                movieGenreRepository,
                movieKeywordRepository,
                movieRepository,
                recommendationImpressionRepository,
                catalogIngestionService,
                userTasteProfileService,
                recommendationScorer,
                recommendationReasonBuilder,
                candidateRetriever,
                collaborativeSimilarityService,
                cacheManager,
                meterRegistry
        );
        lenient().when(recommendationImpressionRepository.findMovieIdsWithAtLeastImpressionsSince(
                anyLong(),
                any(LocalDateTime.class),
                anyLong()
        )).thenReturn(List.of());
        lenient().when(recommendationImpressionRepository.findDistinctMovieIdsSince(
                anyLong(), any(LocalDateTime.class))).thenReturn(List.of());
        lenient().when(recommendationImpressionRepository.findDistinctMovieIdsSinceAmong(
                anyLong(), any(LocalDateTime.class), anyList())).thenReturn(List.of());
        lenient().when(movieRepository.findRecommendationReadyMovies(anyDouble())).thenReturn(List.of());
        lenient().when(movieRepository.findRecommendationReadyMoviesExcluding(anyDouble(), anyCollection()))
                .thenReturn(List.of());
        lenient().when(movieKeywordRepository.findByMovieIdInWithKeyword(anyCollection())).thenReturn(List.of());
        lenient().when(collaborativeSimilarityService.scoreCandidates(anyList())).thenReturn(java.util.Map.of());
    }

    @Test
    void offlineEvaluationBypassesSeedingCachesAndImpressionWrites() {
        RecommendationRequestDto request = new RecommendationRequestDto();
        request.setMoods(List.of("any"));
        request.setRuntimePreference("any");
        request.setLimit(5);

        RecommendationEvaluationRun run = recommendationService.evaluateRecommendations(
                request,
                new RecommendationEvaluationPersona("cold-test", false, List.of(), List.of())
        );

        assertTrue(run.items().isEmpty());
        verifyNoInteractions(catalogIngestionService);
        verify(recommendationImpressionRepository, never()).saveAll(anyCollection());
        assertTrue(cacheManager.getCache("recommendations").getNativeCache() instanceof java.util.Map<?, ?> map
                && map.isEmpty());
        assertTrue(cacheManager.getCache("coldStartRecommendations").getNativeCache() instanceof java.util.Map<?, ?> map
                && map.isEmpty());
    }

    @Test
    void sessionRotationUsesQualifiedUnseenCandidatesWhenEnoughRemain() {
        CatalogRecommendation seenFirst = recommendation(1L, 101, "Seen first", 0.95);
        CatalogRecommendation seenSecond = recommendation(2L, 102, "Seen second", 0.90);
        CatalogRecommendation unseenFirst = recommendation(3L, 103, "Unseen first", 0.85);
        CatalogRecommendation unseenSecond = recommendation(4L, 104, "Unseen second", 0.80);

        List<CatalogRecommendation> rotated = recommendationService.applySessionRotation(
                List.of(seenFirst, seenSecond),
                List.of(seenFirst, seenSecond, unseenFirst, unseenSecond),
                Set.of(1L, 2L),
                Set.of(SoloMood.ANY),
                RuntimePreference.ANY,
                UserTasteProfile.empty(),
                2
        );

        assertEquals(List.of(103, 104), rotated.stream()
                .map(item -> item.movie().getTmdbId()).toList());
    }

    @Test
    void sessionRotationFallsBackToSeenCandidatesWithoutReducingFill() {
        CatalogRecommendation seenFirst = recommendation(1L, 101, "Seen first", 0.95);
        CatalogRecommendation seenSecond = recommendation(2L, 102, "Seen second", 0.90);
        CatalogRecommendation onlyUnseen = recommendation(3L, 103, "Only unseen", 0.70);

        List<CatalogRecommendation> rotated = recommendationService.applySessionRotation(
                List.of(seenFirst, seenSecond),
                List.of(seenFirst, seenSecond, onlyUnseen),
                Set.of(1L, 2L),
                Set.of(SoloMood.ANY),
                RuntimePreference.ANY,
                UserTasteProfile.empty(),
                2
        );

        assertEquals(2, rotated.size());
        assertEquals(103, rotated.getFirst().movie().getTmdbId());
    }

    @Test
    void sessionRotationDoesNotTradeRuntimeComplianceForNovelty() {
        CatalogRecommendation compliantSeen = runtimeRecommendation(1L, 101, "Compliant seen", 0.95, 120);
        CatalogRecommendation otherSeen = runtimeRecommendation(2L, 102, "Other seen", 0.90, 120);
        CatalogRecommendation compliantUnseen = runtimeRecommendation(3L, 103, "Compliant unseen", 0.85, 125);
        CatalogRecommendation longUnseen = runtimeRecommendation(4L, 104, "Long unseen", 0.80, 180);

        List<CatalogRecommendation> rotated = recommendationService.applySessionRotation(
                List.of(compliantSeen, otherSeen),
                List.of(compliantSeen, otherSeen, compliantUnseen, longUnseen),
                Set.of(1L, 2L),
                Set.of(SoloMood.ANY),
                RuntimePreference.MEDIUM,
                UserTasteProfile.empty(),
                2
        );

        long compliant = rotated.stream()
                .filter(item -> RuntimePreference.MEDIUM.score(item.movie().getRuntime()) >= 0.95)
                .count();
        assertEquals(2, compliant, "Rotation must not lower the baseline runtime-compliance count.");
    }

    @Test
    void sessionRotationDoesNotTradeMoodCoverageForNovelty() {
        CatalogRecommendation strongSeen = recommendation(1L, 101, "Strong seen", 0.95, 1.0);
        CatalogRecommendation alignedSeen = recommendation(2L, 102, "Aligned seen", 0.90, 0.8);
        CatalogRecommendation strongUnseen = recommendation(3L, 103, "Strong unseen", 0.85, 1.0);
        CatalogRecommendation weakUnseen = recommendation(4L, 104, "Weak unseen", 0.80, 0.2);

        List<CatalogRecommendation> rotated = recommendationService.applySessionRotation(
                List.of(strongSeen, alignedSeen),
                List.of(strongSeen, alignedSeen, strongUnseen, weakUnseen),
                Set.of(1L, 2L),
                Set.of(SoloMood.DARK),
                RuntimePreference.ANY,
                UserTasteProfile.empty(),
                2
        );

        assertTrue(rotated.stream().mapToDouble(CatalogRecommendation::moodMatch).sum() >= 1.8);
        assertTrue(rotated.stream().anyMatch(item -> item.movie().getTmdbId().equals(103)));
    }

    @Test
    void repeatedDisplayInsideSessionWindowDoesNotWriteAnotherImpression() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");
        Movie candidate = TestFixtures.movie(50L, 1050, "Already recorded this session");
        candidate.setPopularity(200.0);
        candidate.setMovieRating(8.0);
        Genre drama = TestFixtures.genre(50L, 18, "Drama");

        when(watchlistRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());
        when(watchlistRepository.findMovieIdsByUserIdAndStatus(1L, WatchListStatus.WATCHED)).thenReturn(List.of());
        when(reviewRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());
        when(recommendationImpressionRepository.findDistinctMovieIdsSinceAmong(
                eq(1L), any(LocalDateTime.class), anyList())).thenReturn(List.of(50L));
        stubPopularCandidates(candidate);
        stubTopRatedCandidates();
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(new MovieGenre(candidate, drama)));

        recommendationService.getRecommendations(
                user, new RecommendationRequestDto(List.of("any"), "any", 5));

        verify(recommendationImpressionRepository, never()).saveAll(anyCollection());
    }

    @Test
    void moodIntentGateDropsZeroMatchMoviesWhenEnoughAlignedChoicesExist() {
        Movie darkMystery = TestFixtures.movie(1L, 101, "Dark Mystery");
        Movie eerieDrama = TestFixtures.movie(2L, 102, "Eerie Drama");
        Movie familyFantasy = TestFixtures.movie(3L, 103, "Family Fantasy");

        CatalogRecommendation firstAligned = new CatalogRecommendation(
                darkMystery, List.of("Mystery", "Thriller"), 0.80, 0.8, false, null, List.of()
        );
        CatalogRecommendation secondAligned = new CatalogRecommendation(
                eerieDrama, List.of("Drama", "Horror"), 0.75, 0.6, false, null, List.of()
        );
        CatalogRecommendation unrelated = new CatalogRecommendation(
                familyFantasy, List.of("Family", "Fantasy"), 0.95, 0.0, false, null, List.of()
        );

        List<CatalogRecommendation> filtered = recommendationService.applyMoodIntentGate(
                List.of(unrelated, firstAligned, secondAligned),
                Set.of(SoloMood.DARK, SoloMood.EERIE),
                2
        );

        assertEquals(List.of(firstAligned, secondAligned), filtered);
    }

    @Test
    void moodIntentGateKeepsTheStrongestCoverageTierThatCanFillTheList() {
        Movie strongOneMovie = TestFixtures.movie(9L, 109, "Strong One");
        Movie strongTwoMovie = TestFixtures.movie(10L, 110, "Strong Two");
        Movie weakMovie = TestFixtures.movie(11L, 111, "Weak One-Mood Fit");

        CatalogRecommendation strongOne = new CatalogRecommendation(
                strongOneMovie, List.of("Horror", "Science Fiction"), 0.70, 0.8, false, null, List.of()
        );
        CatalogRecommendation strongTwo = new CatalogRecommendation(
                strongTwoMovie, List.of("Mystery", "Drama"), 0.68, 0.8, false, null, List.of()
        );
        CatalogRecommendation weak = new CatalogRecommendation(
                weakMovie, List.of("Romance"), 0.95, 0.2, false, null, List.of()
        );

        assertEquals(
                List.of(strongOne, strongTwo),
                recommendationService.applyMoodIntentGate(
                        List.of(weak, strongOne, strongTwo),
                        Set.of(
                                SoloMood.DARK,
                                SoloMood.EMOTIONAL,
                                SoloMood.THOUGHTFUL,
                                SoloMood.MIND_BENDING,
                                SoloMood.EERIE
                        ),
                        2
                )
        );
    }

    @Test
    void moodIntentGateDoesNotPadThreeMoodRequestWithPartialMatches() {
        Movie completeMovie = TestFixtures.movie(14L, 114, "Complete Three-Mood Fit");
        Movie twoMoodMovie = TestFixtures.movie(15L, 115, "Only Two Moods");
        Movie oneMoodMovie = TestFixtures.movie(16L, 116, "Only One Mood");

        CatalogRecommendation complete = new CatalogRecommendation(
                completeMovie, List.of("Crime", "Drama"), 0.72, 1.0, false, null, List.of()
        );
        CatalogRecommendation twoMoods = new CatalogRecommendation(
                twoMoodMovie, List.of("Thriller"), 0.90, 2.0 / 3.0, false, null, List.of()
        );
        CatalogRecommendation oneMood = new CatalogRecommendation(
                oneMoodMovie, List.of("Drama"), 0.98, 1.0 / 3.0, false, null, List.of()
        );

        assertEquals(
                List.of(complete),
                recommendationService.applyMoodIntentGate(
                        List.of(oneMood, twoMoods, complete),
                        Set.of(SoloMood.TENSE, SoloMood.DARK, SoloMood.EMOTIONAL),
                        5
                )
        );
    }

    @Test
    void moodIntentGateTreatsFiveMoodsAsABlendWithoutAdmittingWeakMatches() {
        Movie completeMovie = TestFixtures.movie(17L, 117, "Complete Five-Mood Fit");
        Movie threeMoodMovie = TestFixtures.movie(18L, 118, "Strong Three-of-Five Blend");
        Movie twoMoodMovie = TestFixtures.movie(19L, 119, "Weak Two-of-Five Fit");

        CatalogRecommendation complete = new CatalogRecommendation(
                completeMovie, List.of("Mystery", "Thriller"), 0.70, 1.0, false, null, List.of()
        );
        CatalogRecommendation threeMoods = new CatalogRecommendation(
                threeMoodMovie, List.of("Horror", "Drama"), 0.85, 3.0 / 5.0, false, null, List.of()
        );
        CatalogRecommendation twoMoods = new CatalogRecommendation(
                twoMoodMovie, List.of("Thriller"), 0.98, 2.0 / 5.0, false, null, List.of()
        );

        assertEquals(
                List.of(complete, threeMoods),
                recommendationService.applyMoodIntentGate(
                        List.of(twoMoods, complete, threeMoods),
                        Set.of(
                                SoloMood.TENSE,
                                SoloMood.DARK,
                                SoloMood.EMOTIONAL,
                                SoloMood.MIND_BENDING,
                                SoloMood.EERIE
                        ),
                        5
                )
        );
    }

    @Test
    void explicitMoodCoverageOutranksAWeakerGenericScore() {
        Movie broadFitMovie = TestFixtures.movie(4L, 104, "Broad Mood Fit");
        Movie genericFavoriteMovie = TestFixtures.movie(5L, 105, "Generic Favorite");

        CatalogRecommendation broadFit = new CatalogRecommendation(
                broadFitMovie, List.of("Drama", "Mystery"), 0.62, 1.0, false, null, List.of()
        );
        CatalogRecommendation genericFavorite = new CatalogRecommendation(
                genericFavoriteMovie, List.of("Thriller"), 0.91, 0.2, false, null, List.of()
        );

        Set<SoloMood> moods = Set.of(SoloMood.DARK, SoloMood.EMOTIONAL, SoloMood.THOUGHTFUL);

        assertTrue(
                recommendationService.intentAwareScore(broadFit, moods)
                        > recommendationService.intentAwareScore(genericFavorite, moods),
                "Explicit session intent should outrank a generic score advantage"
        );
        assertEquals(0.91, recommendationService.intentAwareScore(genericFavorite, Set.of(SoloMood.ANY)));
    }

    @Test
    void exactRuntimeMatchesWinWhenTheCatalogCanFillTheRequest() {
        Movie shortOneMovie = TestFixtures.movie(6L, 106, "Short One");
        shortOneMovie.setRuntime(95);
        Movie shortTwoMovie = TestFixtures.movie(7L, 107, "Short Two");
        shortTwoMovie.setRuntime(105);
        Movie toleranceOnlyMovie = TestFixtures.movie(8L, 108, "Not Actually Short");
        toleranceOnlyMovie.setRuntime(118);

        CatalogRecommendation shortOne = new CatalogRecommendation(
                shortOneMovie, List.of("Mystery"), 0.70, 0.8, false, null, List.of()
        );
        CatalogRecommendation shortTwo = new CatalogRecommendation(
                shortTwoMovie, List.of("Horror"), 0.68, 0.8, false, null, List.of()
        );
        CatalogRecommendation toleranceOnly = new CatalogRecommendation(
                toleranceOnlyMovie, List.of("Thriller"), 0.95, 0.8, false, null, List.of()
        );

        assertEquals(
                List.of(shortOne, shortTwo),
                recommendationService.applyRuntimeIntentGate(
                        List.of(toleranceOnly, shortOne, shortTwo),
                        RuntimePreference.SHORT,
                        2
                )
        );
    }

    @Test
    void explicitReleaseEraStrictlyFiltersTheShortlist() {
        Movie ninetiesMovie = TestFixtures.movie(12L, 112, "Nineties Pick");
        ninetiesMovie.setReleaseDate(LocalDate.of(1997, 6, 1));
        Movie modernMovie = TestFixtures.movie(13L, 113, "Modern Pick");
        modernMovie.setReleaseDate(LocalDate.of(2024, 6, 1));

        CatalogRecommendation nineties = new CatalogRecommendation(
                ninetiesMovie, List.of("Thriller"), 0.70, 0.5, false, null, List.of()
        );
        CatalogRecommendation modern = new CatalogRecommendation(
                modernMovie, List.of("Thriller"), 0.90, 0.5, false, null, List.of()
        );

        assertEquals(
                List.of(nineties),
                recommendationService.applyReleaseEraGate(
                        List.of(modern, nineties),
                        Set.of(ReleaseEra.NINETIES)
                )
        );
        assertEquals(
                List.of(modern, nineties),
                recommendationService.applyReleaseEraGate(
                        List.of(modern, nineties),
                        Set.of(ReleaseEra.ANY)
                )
        );
    }

    @Test
    void soloRecommendationsPreferMoodRuntimeAndOlderWatchlistMovies() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");

        Movie thriller = TestFixtures.movie(11L, 111, "Short Thriller");
        thriller.setRuntime(98);
        thriller.setMovieRating(8.4);

        Movie comfort = TestFixtures.movie(12L, 222, "Long Comfort");
        comfort.setRuntime(148);
        comfort.setMovieRating(7.2);

        WatchList oldThriller = TestFixtures.watchList(101L, user, thriller, WatchListStatus.PLAN_TO_WATCH);
        oldThriller.setAddedAt(LocalDateTime.now().minusDays(220));

        WatchList newerComfort = TestFixtures.watchList(102L, user, comfort, WatchListStatus.PLAN_TO_WATCH);
        newerComfort.setAddedAt(LocalDateTime.now().minusDays(7));

        Genre thrillerGenre = TestFixtures.genre(1L, 53, "Thriller");
        Genre dramaGenre = TestFixtures.genre(2L, 18, "Drama");
        Genre comedyGenre = TestFixtures.genre(3L, 35, "Comedy");

        when(watchlistRepository.findByUserIdWithDetails(1L)).thenReturn(List.of(oldThriller, newerComfort));
        when(reviewRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(
                        new MovieGenre(thriller, thrillerGenre),
                        new MovieGenre(thriller, dramaGenre),
                        new MovieGenre(comfort, comedyGenre)
                ));

        SoloRecommendationRequestDto request = new SoloRecommendationRequestDto();
        request.setMoods(List.of("tense"));
        request.setRuntimePreference("short");
        request.setLimit(5);

        List<SoloRecommendationResponseDto> results = recommendationService.getSoloRecommendations(user, request);

        assertEquals(2, results.size());
        assertEquals(111, results.get(0).getTmdbId());
        assertTrue(results.get(0).getScore() > results.get(1).getScore());
        assertTrue(results.get(0).getReasons().stream()
                .map(String::toLowerCase)
                .anyMatch(reason -> reason.contains("tense") && reason.contains("vibe")));
        assertTrue(results.get(0).getReasons().stream().anyMatch(reason -> reason.contains("runtime fits")));
    }

    @Test
    void recommendationsUseCatalogSignalsAndFlagWatchlistMovies() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");

        Movie watchlistMovie = TestFixtures.movie(10L, 110, "Shadow District");
        watchlistMovie.setRuntime(100);
        watchlistMovie.setMovieRating(8.2);
        watchlistMovie.setPopularity(90.0);

        Movie genreMatchMovie = TestFixtures.movie(11L, 111, "Fractured Case");
        genreMatchMovie.setRuntime(103);
        genreMatchMovie.setMovieRating(8.4);
        genreMatchMovie.setPopularity(135.0);

        Movie watchedMovie = TestFixtures.movie(12L, 112, "Already Seen");
        watchedMovie.setRuntime(102);

        Movie lovedMovie = TestFixtures.movie(13L, 113, "Loved Crime Story");
        Movie popularMovie = TestFixtures.movie(14L, 114, "Blockbuster Rush");
        popularMovie.setPopularity(250.0);
        popularMovie.setMovieRating(7.6);

        Movie topRatedMovie = TestFixtures.movie(15L, 115, "Awards Magnet");
        topRatedMovie.setPopularity(55.0);
        topRatedMovie.setMovieRating(9.2);

        WatchList watchlistEntry = TestFixtures.watchList(201L, user, watchlistMovie, WatchListStatus.PLAN_TO_WATCH);
        WatchList watchedEntry = TestFixtures.watchList(202L, user, watchedMovie, WatchListStatus.WATCHED);

        var strongReview = TestFixtures.review(301L, user, lovedMovie);
        strongReview.setRating(9);
        strongReview.setReviewText("Excellent crime thriller.");

        Genre thriller = TestFixtures.genre(1L, 53, "Thriller");
        Genre crime = TestFixtures.genre(2L, 80, "Crime");
        Genre mystery = TestFixtures.genre(3L, 9648, "Mystery");
        Genre action = TestFixtures.genre(4L, 28, "Action");
        Genre drama = TestFixtures.genre(5L, 18, "Drama");

        when(watchlistRepository.findByUserIdWithDetails(1L)).thenReturn(List.of(watchlistEntry, watchedEntry));
        when(watchlistRepository.findMovieIdsByUserIdAndStatus(1L, WatchListStatus.WATCHED)).thenReturn(List.of(12L));
        when(reviewRepository.findByUserIdWithDetails(1L)).thenReturn(List.of(strongReview));
        stubGenreCandidates(genreMatchMovie);
        stubPopularCandidates(popularMovie, genreMatchMovie);
        stubTopRatedCandidates(topRatedMovie, genreMatchMovie);
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(
                        new MovieGenre(watchlistMovie, thriller),
                        new MovieGenre(watchlistMovie, crime),
                        new MovieGenre(genreMatchMovie, thriller),
                        new MovieGenre(genreMatchMovie, mystery),
                        new MovieGenre(watchedMovie, thriller),
                        new MovieGenre(lovedMovie, thriller),
                        new MovieGenre(lovedMovie, crime),
                        new MovieGenre(popularMovie, action),
                        new MovieGenre(topRatedMovie, drama)
                ));

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("tense"), "short", 4);

        List<RecommendationResponseDto> results = recommendationService.getRecommendations(user, request);

        assertEquals(4, results.size());
        assertTrue(results.stream().noneMatch(result -> result.getTmdbId() == 112));
        assertTrue(results.stream().anyMatch(result -> result.isOnWatchlist()
                && "PLAN_TO_WATCH".equals(result.getWatchlistStatus())));
        assertTrue(results.stream().flatMap(result -> result.getReasons().stream())
                .anyMatch(reason -> reason.toLowerCase().contains("watchlist")));
        assertTrue(results.stream().flatMap(result -> result.getReasons().stream())
                .anyMatch(reason -> reason.toLowerCase().contains("rate highly")));
    }

    @Test
    void recommendationsPenalizeGenresTheUserRatesPoorly() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");

        Movie likedReviewMovie = TestFixtures.movie(60L, 660, "Loved Mystery");
        Movie dislikedReviewMovie = TestFixtures.movie(61L, 661, "Disliked Romance");

        Movie thrillerCandidate = TestFixtures.movie(62L, 662, "Tense Pick");
        thrillerCandidate.setMovieRating(7.8);
        thrillerCandidate.setPopularity(105.0);

        Movie romanceCandidate = TestFixtures.movie(63L, 663, "Soft Focus");
        romanceCandidate.setMovieRating(8.0);
        romanceCandidate.setPopularity(105.0);

        var positiveReview = TestFixtures.review(701L, user, likedReviewMovie);
        positiveReview.setRating(9);
        positiveReview.setReviewText("Great mystery payoff.");

        var negativeReview = TestFixtures.review(702L, user, dislikedReviewMovie);
        negativeReview.setRating(3);
        negativeReview.setReviewText("Not for me.");

        Genre thriller = TestFixtures.genre(21L, 53, "Thriller");
        Genre mystery = TestFixtures.genre(22L, 9648, "Mystery");
        Genre romance = TestFixtures.genre(23L, 10749, "Romance");

        when(watchlistRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());
        when(watchlistRepository.findMovieIdsByUserIdAndStatus(1L, WatchListStatus.WATCHED)).thenReturn(List.of());
        when(reviewRepository.findByUserIdWithDetails(1L)).thenReturn(List.of(positiveReview, negativeReview));
        stubGenreCandidates(thrillerCandidate);
        stubPopularCandidates(thrillerCandidate, romanceCandidate);
        stubTopRatedCandidates(romanceCandidate, thrillerCandidate);
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(
                        new MovieGenre(likedReviewMovie, thriller),
                        new MovieGenre(likedReviewMovie, mystery),
                        new MovieGenre(dislikedReviewMovie, romance),
                        new MovieGenre(thrillerCandidate, thriller),
                        new MovieGenre(thrillerCandidate, mystery),
                        new MovieGenre(romanceCandidate, romance)
                ));

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("any"), "any", 5);

        List<RecommendationResponseDto> results = recommendationService.getRecommendations(user, request);

        assertEquals(2, results.size());
        assertEquals(662, results.get(0).getTmdbId());
        assertEquals(663, results.get(1).getTmdbId());
    }

    @Test
    void recommendationsApplyRuntimeHardFiltersBeforeRanking() {
        Movie shortMovie = TestFixtures.movie(70L, 770, "Short Match");
        shortMovie.setRuntime(101);
        shortMovie.setMovieRating(8.1);

        Movie longMovie = TestFixtures.movie(71L, 771, "Long Epic");
        longMovie.setRuntime(164);
        longMovie.setMovieRating(8.9);

        Genre thriller = TestFixtures.genre(24L, 53, "Thriller");

        stubPopularCandidates(shortMovie, longMovie);
        stubTopRatedCandidates(longMovie, shortMovie);
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(
                        new MovieGenre(shortMovie, thriller),
                        new MovieGenre(longMovie, thriller)
                ));

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("any"), "short", 5);

        List<RecommendationResponseDto> results = recommendationService.getColdStartRecommendations(request);

        assertEquals(1, results.size());
        assertEquals(770, results.get(0).getTmdbId());
    }

    @Test
    void recommendationsRespectConfigurableFeatureWeights() {
        recommendationScoringProperties.setGenreAffinityWeight(0.0);
        recommendationScoringProperties.setMoodMatchWeight(0.0);
        recommendationScoringProperties.setRuntimeMatchWeight(0.0);
        recommendationScoringProperties.setQualityWeight(0.0);
        recommendationScoringProperties.setPopularityWeight(0.0);
        recommendationScoringProperties.setWatchlistBoostWeight(0.0);
        recommendationScoringProperties.setWatchlistAgeWeight(0.0);
        recommendationScoringProperties.setDislikedGenrePenaltyWeight(0.0);
        recommendationScoringProperties.setFreshnessWeight(1.0);

        Movie recentMovie = TestFixtures.movie(80L, 880, "Fresh Arrival");
        recentMovie.setReleaseDate(java.time.LocalDate.now().minusMonths(8));
        recentMovie.setMovieRating(7.1);
        recentMovie.setPopularity(70.0);

        Movie olderMovie = TestFixtures.movie(81L, 881, "Classic Favorite");
        olderMovie.setReleaseDate(java.time.LocalDate.of(1995, 6, 1));
        olderMovie.setMovieRating(9.4);
        olderMovie.setPopularity(70.0);

        Genre drama = TestFixtures.genre(25L, 18, "Drama");

        stubPopularCandidates(recentMovie, olderMovie);
        stubTopRatedCandidates(olderMovie, recentMovie);
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(
                        new MovieGenre(recentMovie, drama),
                        new MovieGenre(olderMovie, drama)
                ));

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("any"), "any", 5);

        List<RecommendationResponseDto> results = recommendationService.getColdStartRecommendations(request);

        assertEquals(2, results.size());
        assertEquals(880, results.get(0).getTmdbId());
        assertEquals(881, results.get(1).getTmdbId());
    }

    @Test
    void catalogRecommendationsUseDiversityRerankingToAvoidNearDuplicateGenreClusters() {
        recommendationScoringProperties.setDiversityPenaltyWeight(0.80);

        Movie thrillerLead = TestFixtures.movie(90L, 990, "Night Signal");
        thrillerLead.setMovieRating(8.6);
        thrillerLead.setPopularity(120.0);

        Movie thrillerFollowUp = TestFixtures.movie(91L, 991, "Shadow Wire");
        thrillerFollowUp.setMovieRating(8.4);
        thrillerFollowUp.setPopularity(118.0);

        Movie dramaAlternative = TestFixtures.movie(92L, 992, "Quiet Return");
        dramaAlternative.setMovieRating(8.2);
        dramaAlternative.setPopularity(115.0);

        Genre thriller = TestFixtures.genre(31L, 53, "Thriller");
        Genre mystery = TestFixtures.genre(32L, 9648, "Mystery");
        Genre drama = TestFixtures.genre(33L, 18, "Drama");

        stubPopularCandidates(thrillerLead, thrillerFollowUp, dramaAlternative);
        stubTopRatedCandidates(thrillerLead, thrillerFollowUp, dramaAlternative);
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(
                        new MovieGenre(thrillerLead, thriller),
                        new MovieGenre(thrillerLead, mystery),
                        new MovieGenre(thrillerFollowUp, thriller),
                        new MovieGenre(thrillerFollowUp, mystery),
                        new MovieGenre(dramaAlternative, drama)
                ));

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("any"), "any", 3);

        List<RecommendationResponseDto> results = recommendationService.getColdStartRecommendations(request);

        assertEquals(3, results.size());
        assertEquals(990, results.get(0).getTmdbId());
        assertEquals(992, results.get(1).getTmdbId());
        assertEquals(991, results.get(2).getTmdbId());
    }

    @Test
    void personalizedRecommendationsUseCalibrationRerankingToReflectPreferredGenreMix() {
        recommendationScoringProperties.setDiversityPenaltyWeight(0.0);
        recommendationScoringProperties.setCalibrationPenaltyWeight(0.55);

        User user = TestFixtures.user(1L, "alice", "alice@example.com");

        Movie thrillerSeed = TestFixtures.movie(93L, 993, "Seed Thriller");
        thrillerSeed.setMovieRating(9.0);

        Movie dramaSeed = TestFixtures.movie(94L, 994, "Seed Drama");
        dramaSeed.setMovieRating(9.0);

        Movie thrillerLead = TestFixtures.movie(95L, 995, "Lead Thriller");
        thrillerLead.setMovieRating(8.8);
        thrillerLead.setPopularity(140.0);

        Movie thrillerFollowUp = TestFixtures.movie(96L, 996, "Follow-Up Thriller");
        thrillerFollowUp.setMovieRating(8.7);
        thrillerFollowUp.setPopularity(135.0);

        Movie dramaAlternative = TestFixtures.movie(97L, 997, "Drama Alternative");
        dramaAlternative.setMovieRating(7.9);
        dramaAlternative.setPopularity(92.0);

        Genre thriller = TestFixtures.genre(37L, 53, "Thriller");
        Genre drama = TestFixtures.genre(38L, 18, "Drama");

        Review thrillerReview = TestFixtures.review(804L, user, thrillerSeed);
        thrillerReview.setRating(9);
        Review dramaReview = TestFixtures.review(805L, user, dramaSeed);
        dramaReview.setRating(9);

        when(watchlistRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());
        when(watchlistRepository.findMovieIdsByUserIdAndStatus(1L, WatchListStatus.WATCHED)).thenReturn(List.of());
        when(reviewRepository.findByUserIdWithDetails(1L)).thenReturn(List.of(thrillerReview, dramaReview));
        stubGenreCandidates(thrillerLead, thrillerFollowUp, dramaAlternative);
        stubPopularCandidates(thrillerLead, thrillerFollowUp, dramaAlternative);
        stubTopRatedCandidates(thrillerLead, thrillerFollowUp, dramaAlternative);
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(
                        new MovieGenre(thrillerSeed, thriller),
                        new MovieGenre(dramaSeed, drama),
                        new MovieGenre(thrillerLead, thriller),
                        new MovieGenre(thrillerFollowUp, thriller),
                        new MovieGenre(dramaAlternative, drama)
                ));

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("any"), "any", 2);

        List<RecommendationResponseDto> results = recommendationService.getRecommendations(user, request);

        assertEquals(2, results.size());
        assertEquals(995, results.get(0).getTmdbId());
        assertEquals(997, results.get(1).getTmdbId());

        io.micrometer.core.instrument.Timer calibrationStageTimer = meterRegistry
                .find("recommendation.pipeline.stage.duration")
                .tag("type", "personalized")
                .tag("stage", "calibrationReranking")
                .timer();
        assertTrue(calibrationStageTimer != null && calibrationStageTimer.count() >= 1,
                "Expected a personalized stage timer for calibration reranking");
    }

    @Test
    void soloRecommendationsUseDiversityRerankingToBreakUpRepeatedGenres() {
        recommendationScoringProperties.setDiversityPenaltyWeight(0.80);

        User user = TestFixtures.user(1L, "alice", "alice@example.com");

        Movie thrillerLead = TestFixtures.movie(100L, 1000, "Lead Thriller");
        thrillerLead.setMovieRating(8.5);

        Movie thrillerFollowUp = TestFixtures.movie(101L, 1001, "Second Thriller");
        thrillerFollowUp.setMovieRating(8.3);

        Movie comedyAlternative = TestFixtures.movie(102L, 1002, "Comic Break");
        comedyAlternative.setMovieRating(8.1);

        WatchList olderThriller = TestFixtures.watchList(801L, user, thrillerLead, WatchListStatus.PLAN_TO_WATCH);
        olderThriller.setAddedAt(LocalDateTime.now().minusDays(220));

        WatchList newerThriller = TestFixtures.watchList(802L, user, thrillerFollowUp, WatchListStatus.PLAN_TO_WATCH);
        newerThriller.setAddedAt(LocalDateTime.now().minusDays(60));

        WatchList comedyEntry = TestFixtures.watchList(803L, user, comedyAlternative, WatchListStatus.PLAN_TO_WATCH);
        comedyEntry.setAddedAt(LocalDateTime.now().minusDays(45));

        Genre thriller = TestFixtures.genre(34L, 53, "Thriller");
        Genre mystery = TestFixtures.genre(35L, 9648, "Mystery");
        Genre comedy = TestFixtures.genre(36L, 35, "Comedy");

        when(watchlistRepository.findByUserIdWithDetails(1L)).thenReturn(List.of(olderThriller, newerThriller, comedyEntry));
        when(reviewRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(
                        new MovieGenre(thrillerLead, thriller),
                        new MovieGenre(thrillerLead, mystery),
                        new MovieGenre(thrillerFollowUp, thriller),
                        new MovieGenre(thrillerFollowUp, mystery),
                        new MovieGenre(comedyAlternative, comedy)
                ));

        SoloRecommendationRequestDto request = new SoloRecommendationRequestDto();
        request.setMoods(List.of("any"));
        request.setRuntimePreference("any");
        request.setLimit(3);

        List<SoloRecommendationResponseDto> results = recommendationService.getSoloRecommendations(user, request);

        assertEquals(3, results.size());
        assertEquals(1000, results.get(0).getTmdbId());
        assertEquals(1002, results.get(1).getTmdbId());
        assertEquals(1001, results.get(2).getTmdbId());
    }

    @Test
    void recommendationsExcludeWatchedMoviesFromFinalResults() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");
        Movie watchedMovie = TestFixtures.movie(20L, 220, "Watched Already");
        Movie otherMovie = TestFixtures.movie(21L, 221, "Still Eligible");
        WatchList watchedEntry = TestFixtures.watchList(401L, user, watchedMovie, WatchListStatus.WATCHED);

        Genre thriller = TestFixtures.genre(7L, 53, "Thriller");

        when(watchlistRepository.findByUserIdWithDetails(1L)).thenReturn(List.of(watchedEntry));
        when(watchlistRepository.findMovieIdsByUserIdAndStatus(1L, WatchListStatus.WATCHED)).thenReturn(List.of(20L));
        when(reviewRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());
        stubPopularCandidates(watchedMovie, otherMovie);
        stubTopRatedCandidates(otherMovie);
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(
                        new MovieGenre(watchedMovie, thriller),
                        new MovieGenre(otherMovie, thriller)
                ));

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("tense"), "any", 3);

        List<RecommendationResponseDto> results = recommendationService.getRecommendations(user, request);

        assertEquals(1, results.size());
        assertEquals(221, results.get(0).getTmdbId());
    }

    @Test
    void recommendationsSkipIncompleteWatchlistMoviesFromCandidatePool() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");
        Movie incompleteWatchlistMovie = TestFixtures.movie(40L, 440, "Thin Cache Entry");
        incompleteWatchlistMovie.setRuntime(null);

        Movie readyMovie = TestFixtures.movie(41L, 441, "Catalog Ready");
        readyMovie.setRuntime(118);
        readyMovie.setMovieRating(8.0);
        readyMovie.setPopularity(140.0);

        WatchList watchlistEntry = TestFixtures.watchList(501L, user, incompleteWatchlistMovie, WatchListStatus.PLAN_TO_WATCH);
        Genre drama = TestFixtures.genre(11L, 18, "Drama");

        when(watchlistRepository.findByUserIdWithDetails(1L)).thenReturn(List.of(watchlistEntry));
        when(watchlistRepository.findMovieIdsByUserIdAndStatus(1L, WatchListStatus.WATCHED)).thenReturn(List.of());
        when(reviewRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());
        stubPopularCandidates(readyMovie);
        stubTopRatedCandidates(readyMovie);
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(
                        new MovieGenre(incompleteWatchlistMovie, drama),
                        new MovieGenre(readyMovie, drama)
                ));

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("any"), "any", 5);

        List<RecommendationResponseDto> results = recommendationService.getRecommendations(user, request);

        assertEquals(1, results.size());
        assertEquals(441, results.get(0).getTmdbId());
    }

    @Test
    void coldStartRecommendationsUseMoodAlignedCandidatesForAnonymousUsers() {
        Movie moodMovie = TestFixtures.movie(50L, 550, "Night Tension");
        moodMovie.setRuntime(101);
        moodMovie.setMovieRating(7.9);
        moodMovie.setPopularity(120.0);

        Genre thriller = TestFixtures.genre(12L, 53, "Thriller");

        stubGenreCandidates(moodMovie);
        stubPopularCandidates();
        stubTopRatedCandidates();
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(new MovieGenre(moodMovie, thriller)));

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("tense"), "any", 5);

        List<RecommendationResponseDto> results = recommendationService.getColdStartRecommendations(request);

        assertEquals(1, results.size());
        assertEquals(550, results.get(0).getTmdbId());
        assertTrue(results.get(0).getReasons().stream()
                .map(String::toLowerCase)
                .anyMatch(reason -> reason.contains("tense") || reason.contains("vibe")));
    }

    @Test
    void coldStartRecommendationsFallbackToPopularAndHighlyRatedCatalogMovies() {
        Movie popularMovie = TestFixtures.movie(30L, 330, "Crowd Favorite");
        popularMovie.setPopularity(320.0);
        popularMovie.setMovieRating(7.8);

        Movie highlyRatedMovie = TestFixtures.movie(31L, 331, "Critics Darling");
        highlyRatedMovie.setPopularity(65.0);
        highlyRatedMovie.setMovieRating(9.3);

        Genre adventure = TestFixtures.genre(9L, 12, "Adventure");
        Genre drama = TestFixtures.genre(10L, 18, "Drama");

        stubPopularCandidates(popularMovie);
        stubTopRatedCandidates(highlyRatedMovie);
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(
                        new MovieGenre(popularMovie, adventure),
                        new MovieGenre(highlyRatedMovie, drama)
                ));

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("any"), "any", 5);

        List<RecommendationResponseDto> results = recommendationService.getColdStartRecommendations(request);

        assertEquals(2, results.size());
        assertTrue(results.stream().noneMatch(RecommendationResponseDto::isOnWatchlist));
        assertTrue(results.stream().flatMap(result -> result.getReasons().stream())
                .anyMatch(reason -> reason.toLowerCase().contains("wider catalog")
                        || reason.toLowerCase().contains("popular catalog")
                        || reason.toLowerCase().contains("audience ratings")));
    }

    @Test
    void coldStartRecommendationsUseFallbackReasonWhenNoSpecificReasonApplies() {
        Movie quietMovie = TestFixtures.movie(32L, 332, "Quiet Catalog Pick");
        quietMovie.setPopularity(20.0);
        quietMovie.setMovieRating(5.6);
        quietMovie.setReleaseDate(java.time.LocalDate.of(2005, 1, 1));

        stubPopularCandidates(quietMovie);
        stubTopRatedCandidates();
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection())).thenReturn(List.of());

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("any"), "any", 5);

        List<RecommendationResponseDto> results = recommendationService.getColdStartRecommendations(request);

        assertEquals(1, results.size());
        assertTrue(results.get(0).getReasons().stream()
                .anyMatch(reason -> reason.contains("strong wider-catalog pick")));
    }

    @Test
    void coldStartRecommendationsUseExclusionAwareQueriesAfterEarlierChannelsAddCandidates() {
        Movie moodMovie = TestFixtures.movie(120L, 1120, "Mood Anchor");
        moodMovie.setPopularity(140.0);
        moodMovie.setMovieRating(7.9);

        Movie popularMovie = TestFixtures.movie(121L, 1121, "Popular Follow Up");
        popularMovie.setPopularity(240.0);
        popularMovie.setMovieRating(8.3);

        Movie topRatedMovie = TestFixtures.movie(122L, 1122, "Top Rated Follow Up");
        topRatedMovie.setPopularity(80.0);
        topRatedMovie.setMovieRating(9.1);

        Genre thriller = TestFixtures.genre(40L, 53, "Thriller");
        Genre action = TestFixtures.genre(41L, 28, "Action");
        Genre mystery = TestFixtures.genre(42L, 9648, "Mystery");

        when(movieGenreRepository.findDistinctRecommendationReadyMoviesByGenreNames(anyCollection(), anyDouble(), any(Pageable.class)))
                .thenReturn(List.of(moodMovie));
        lenient().when(movieGenreRepository.findDistinctRecommendationReadyMoviesByGenreNamesExcluding(
                anyCollection(),
                anyDouble(),
                anyCollection(),
                any(Pageable.class)
        )).thenReturn(List.of());
        lenient().when(movieRepository.findRecommendationReadyPopularMovies(anyDouble(), any(Pageable.class)))
                .thenReturn(List.of());
        when(movieRepository.findRecommendationReadyPopularMoviesExcluding(anyDouble(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(popularMovie));
        lenient().when(movieRepository.findRecommendationReadyTopRatedMovies(
                        anyDouble(), anyDouble(), anyDouble(), any(Pageable.class)))
                .thenReturn(List.of());
        when(movieRepository.findRecommendationReadyTopRatedMoviesExcluding(
                anyDouble(), anyDouble(), anyDouble(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(topRatedMovie));
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(
                        new MovieGenre(moodMovie, thriller),
                        new MovieGenre(popularMovie, action),
                        new MovieGenre(topRatedMovie, mystery)
                ));

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("tense"), "any", 5);

        List<RecommendationResponseDto> results = recommendationService.getColdStartRecommendations(request);

        assertEquals(3, results.size());
        assertTrue(results.stream().anyMatch(result -> result.getTmdbId() == 1120));
        assertTrue(results.stream().anyMatch(result -> result.getTmdbId() == 1121));
        assertTrue(results.stream().anyMatch(result -> result.getTmdbId() == 1122));
        verify(movieRepository, never()).findRecommendationReadyPopularMovies(anyDouble(), any(Pageable.class));
        verify(movieRepository).findRecommendationReadyPopularMoviesExcluding(anyDouble(), anyCollection(), any(Pageable.class));
        verify(movieRepository, never()).findRecommendationReadyTopRatedMovies(
                anyDouble(), anyDouble(), anyDouble(), any(Pageable.class));
        verify(movieRepository).findRecommendationReadyTopRatedMoviesExcluding(
                anyDouble(), anyDouble(), anyDouble(), anyCollection(), any(Pageable.class));
    }

    @Test
    void recommendationsReserveRoomForLaterChannelsWhenEarlierChannelsReturnDeepPools() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");

        Movie likedMovie = TestFixtures.movie(130L, 1130, "Liked Thriller Seed");
        var positiveReview = TestFixtures.review(901L, user, likedMovie);
        positiveReview.setRating(9);

        Genre thriller = TestFixtures.genre(50L, 53, "Thriller");
        Genre action = TestFixtures.genre(51L, 28, "Action");
        Genre mystery = TestFixtures.genre(52L, 9648, "Mystery");

        List<Movie> genreCandidates = new java.util.ArrayList<>();
        List<Movie> moodCandidates = new java.util.ArrayList<>();
        List<MovieGenre> allGenres = new java.util.ArrayList<>();
        allGenres.add(new MovieGenre(likedMovie, thriller));

        for (int index = 0; index < 60; index++) {
            Movie genreMovie = TestFixtures.movie(200L + index, 2200 + index, "Genre Candidate " + index);
            genreMovie.setMovieRating(6.1);
            genreMovie.setPopularity(75.0 + index);
            genreCandidates.add(genreMovie);
            allGenres.add(new MovieGenre(genreMovie, thriller));

            Movie moodMovie = TestFixtures.movie(400L + index, 2400 + index, "Mood Candidate " + index);
            moodMovie.setMovieRating(6.0);
            moodMovie.setPopularity(70.0 + index);
            moodCandidates.add(moodMovie);
            allGenres.add(new MovieGenre(moodMovie, action));
        }

        Movie popularMovie = TestFixtures.movie(700L, 2700, "Popular Late Entry");
        popularMovie.setMovieRating(9.0);
        popularMovie.setPopularity(320.0);
        allGenres.add(new MovieGenre(popularMovie, action));

        Movie topRatedMovie = TestFixtures.movie(701L, 2701, "Top Rated Late Entry");
        topRatedMovie.setMovieRating(9.5);
        topRatedMovie.setPopularity(140.0);
        allGenres.add(new MovieGenre(topRatedMovie, mystery));

        when(watchlistRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());
        when(watchlistRepository.findMovieIdsByUserIdAndStatus(1L, WatchListStatus.WATCHED)).thenReturn(List.of());
        when(reviewRepository.findByUserIdWithDetails(1L)).thenReturn(List.of(positiveReview));
        when(movieGenreRepository.findDistinctRecommendationReadyMoviesByGenreNames(anyCollection(), anyDouble(), any(Pageable.class)))
                .thenReturn(genreCandidates);
        when(movieGenreRepository.findDistinctRecommendationReadyMoviesByGenreNamesExcluding(
                anyCollection(),
                anyDouble(),
                anyCollection(),
                any(Pageable.class)
        )).thenReturn(moodCandidates);
        lenient().when(movieRepository.findRecommendationReadyPopularMovies(anyDouble(), any(Pageable.class)))
                .thenReturn(List.of());
        when(movieRepository.findRecommendationReadyPopularMoviesExcluding(anyDouble(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(popularMovie));
        lenient().when(movieRepository.findRecommendationReadyTopRatedMovies(
                        anyDouble(), anyDouble(), anyDouble(), any(Pageable.class)))
                .thenReturn(List.of());
        when(movieRepository.findRecommendationReadyTopRatedMoviesExcluding(
                anyDouble(), anyDouble(), anyDouble(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(topRatedMovie));
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection())).thenReturn(allGenres);

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("tense"), "any", 5);

        List<RecommendationResponseDto> results = recommendationService.getRecommendations(user, request);

        assertEquals(5, results.size());
        assertTrue(results.stream().anyMatch(result -> result.getTmdbId() == 2700));
        assertTrue(results.stream().anyMatch(result -> result.getTmdbId() == 2701));
    }

    @Test
    void recommendationsPenalizeRepeatedlyIgnoredMovies() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");

        Movie likedMovie = TestFixtures.movie(150L, 1150, "Liked Thriller Seed");
        likedMovie.setOverview("A tense conspiracy thriller with a brilliant detective.");
        var positiveReview = TestFixtures.review(950L, user, likedMovie);
        positiveReview.setRating(9);

        Movie penalizedMovie = TestFixtures.movie(151L, 1151, "Ignored Favorite");
        penalizedMovie.setMovieRating(8.5);
        penalizedMovie.setPopularity(150.0);

        Movie freshMovie = TestFixtures.movie(152L, 1152, "Fresh Option");
        freshMovie.setMovieRating(8.2);
        freshMovie.setPopularity(138.0);

        Genre thriller = TestFixtures.genre(60L, 53, "Thriller");

        when(watchlistRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());
        when(watchlistRepository.findMovieIdsByUserIdAndStatus(1L, WatchListStatus.WATCHED)).thenReturn(List.of());
        when(reviewRepository.findByUserIdWithDetails(1L)).thenReturn(List.of(positiveReview));
        when(recommendationImpressionRepository.findMovieIdsWithAtLeastImpressionsSince(
                anyLong(),
                any(LocalDateTime.class),
                anyLong()
        )).thenAnswer(invocation -> invocation.getArgument(2, Long.class) >= 3L
                ? List.of()
                : List.of(151L));
        stubGenreCandidates(penalizedMovie, freshMovie);
        stubPopularCandidates();
        stubTopRatedCandidates();
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(
                        new MovieGenre(likedMovie, thriller),
                        new MovieGenre(penalizedMovie, thriller),
                        new MovieGenre(freshMovie, thriller)
                ));

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("any"), "any", 5);

        List<RecommendationResponseDto> results = recommendationService.getRecommendations(user, request);

        assertEquals(2, results.size());
        assertEquals(1152, results.get(0).getTmdbId());
        assertEquals(1151, results.get(1).getTmdbId());
    }

    @Test
    void recommendationsSuppressMoviesIgnoredTooManyTimes() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");

        Movie likedMovie = TestFixtures.movie(160L, 1160, "Liked Action Seed");
        likedMovie.setOverview("A rescue mission across hostile territory.");
        var positiveReview = TestFixtures.review(960L, user, likedMovie);
        positiveReview.setRating(9);

        Movie suppressedMovie = TestFixtures.movie(161L, 1161, "Suppressed Pick");
        Movie remainingMovie = TestFixtures.movie(162L, 1162, "Remaining Pick");

        Genre action = TestFixtures.genre(61L, 28, "Action");

        when(watchlistRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());
        when(watchlistRepository.findMovieIdsByUserIdAndStatus(1L, WatchListStatus.WATCHED)).thenReturn(List.of());
        when(reviewRepository.findByUserIdWithDetails(1L)).thenReturn(List.of(positiveReview));
        when(recommendationImpressionRepository.findMovieIdsWithAtLeastImpressionsSince(
                anyLong(),
                any(LocalDateTime.class),
                anyLong()
        )).thenAnswer(invocation -> {
            long minimumCount = invocation.getArgument(2, Long.class);
            return minimumCount >= 3L ? List.of(161L) : List.of();
        });
        stubGenreCandidates(suppressedMovie, remainingMovie);
        stubPopularCandidates();
        stubTopRatedCandidates();
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(
                        new MovieGenre(likedMovie, action),
                        new MovieGenre(suppressedMovie, action),
                        new MovieGenre(remainingMovie, action)
                ));

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("any"), "any", 5);

        List<RecommendationResponseDto> results = recommendationService.getRecommendations(user, request);

        assertEquals(1, results.size());
        assertEquals(1162, results.get(0).getTmdbId());
    }

    @Test
    void recommendationsBoostContentSimilarMoviesBackedByAnotherChannel() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");

        Movie seedMovie = TestFixtures.movie(170L, 1170, "Deep Space Survival");
        seedMovie.setOverview("Astronauts stranded on a remote planet fight to survive after a mission disaster.");
        var positiveReview = TestFixtures.review(970L, user, seedMovie);
        positiveReview.setRating(9);

        Movie plotSimilarMovie = TestFixtures.movie(171L, 1171, "Orbit Rescue");
        plotSimilarMovie.setMovieRating(7.9);
        plotSimilarMovie.setPopularity(118.0);
        plotSimilarMovie.setOverview("A stranded astronaut crew must survive on a hostile planet until a rescue mission arrives.");

        Movie lessSimilarMovie = TestFixtures.movie(172L, 1172, "Galactic Parade");
        lessSimilarMovie.setMovieRating(8.2);
        lessSimilarMovie.setPopularity(135.0);
        lessSimilarMovie.setOverview("A famous performer leads a dazzling interstellar celebration while juggling romance and fame.");

        Genre scienceFiction = TestFixtures.genre(62L, 878, "Science Fiction");

        when(watchlistRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());
        when(watchlistRepository.findMovieIdsByUserIdAndStatus(1L, WatchListStatus.WATCHED)).thenReturn(List.of());
        when(reviewRepository.findByUserIdWithDetails(1L)).thenReturn(List.of(positiveReview));
        stubGenreCandidates(plotSimilarMovie, lessSimilarMovie);
        stubPopularCandidates();
        stubTopRatedCandidates();
        when(movieRepository.findRecommendationReadyMovies(anyDouble()))
                .thenReturn(List.of(seedMovie, plotSimilarMovie, lessSimilarMovie));
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(
                        new MovieGenre(seedMovie, scienceFiction),
                        new MovieGenre(plotSimilarMovie, scienceFiction),
                        new MovieGenre(lessSimilarMovie, scienceFiction)
                ));

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("any"), "any", 5);

        List<RecommendationResponseDto> results = recommendationService.getRecommendations(user, request);

        assertEquals(2, results.size());
        assertEquals(1171, results.get(0).getTmdbId());
        assertTrue(results.get(0).getReasons().stream()
                .map(String::toLowerCase)
                .anyMatch(reason -> reason.contains("plot and overall premise are close")
                        || reason.contains("recommendation signals")));
    }

    @Test
    void recommendationsReuseOneCachedResultThenRefreshForFeedbackChanges() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");

        Movie popularMovie = TestFixtures.movie(180L, 1180, "Cache Me If You Can");
        popularMovie.setPopularity(320.0);
        popularMovie.setMovieRating(8.4);

        Genre action = TestFixtures.genre(70L, 28, "Action");

        when(watchlistRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());
        when(watchlistRepository.findMovieIdsByUserIdAndStatus(1L, WatchListStatus.WATCHED)).thenReturn(List.of());
        when(reviewRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());
        stubPopularCandidates(popularMovie);
        stubTopRatedCandidates();
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(new MovieGenre(popularMovie, action)));
        when(movieRepository.findByTmdbIdIn(List.of(1180))).thenReturn(List.of(popularMovie));

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("any"), "any", 5);

        List<RecommendationResponseDto> firstResults = recommendationService.getRecommendations(user, request);
        List<RecommendationResponseDto> secondResults = recommendationService.getRecommendations(user, request);
        List<RecommendationResponseDto> thirdResults = recommendationService.getRecommendations(user, request);

        assertEquals(firstResults.size(), secondResults.size());
        assertEquals(firstResults.getFirst().getTmdbId(), secondResults.getFirst().getTmdbId());
        assertEquals(secondResults.size(), thirdResults.size());
        assertEquals(secondResults.getFirst().getTmdbId(), thirdResults.getFirst().getTmdbId());
        verify(catalogIngestionService, times(2)).ensureCatalogSeeded();
        verify(watchlistRepository, times(2)).findByUserIdWithDetails(1L);
        verify(reviewRepository, times(2)).findByUserIdWithDetails(1L);
        verify(recommendationImpressionRepository, times(3)).saveAll(anyCollection());
        verify(movieRepository, times(1)).findByTmdbIdIn(List.of(1180));
    }

    @Test
    void personalizedRecommendationsUseKeywordAffinityToBreakGenreTies() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");

        Movie reviewedSeed = TestFixtures.movie(300L, 1300, "Looped Signal");
        reviewedSeed.setMovieRating(9.0);
        Movie dislikedSeed = TestFixtures.movie(303L, 1303, "Operatic Collapse");
        dislikedSeed.setMovieRating(3.5);

        Movie keywordMatch = TestFixtures.movie(301L, 1301, "Temporal Echo");
        keywordMatch.setMovieRating(8.0);
        keywordMatch.setPopularity(120.0);

        Movie genreOnlyMatch = TestFixtures.movie(302L, 1302, "Galactic Drift");
        genreOnlyMatch.setMovieRating(8.0);
        genreOnlyMatch.setPopularity(120.0);

        Genre sciFi = TestFixtures.genre(90L, 878, "Science Fiction");
        Review positiveReview = TestFixtures.review(901L, user, reviewedSeed);
        positiveReview.setRating(9);
        Review negativeReview = TestFixtures.review(902L, user, dislikedSeed);
        negativeReview.setRating(2);
        negativeReview.setReviewText("Really disliked this style.");

        when(watchlistRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());
        when(watchlistRepository.findMovieIdsByUserIdAndStatus(1L, WatchListStatus.WATCHED)).thenReturn(List.of());
        when(reviewRepository.findByUserIdWithDetails(1L)).thenReturn(List.of(positiveReview, negativeReview));
        stubPopularCandidates(keywordMatch, genreOnlyMatch);
        stubTopRatedCandidates();
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(
                        new MovieGenre(reviewedSeed, sciFi),
                        new MovieGenre(dislikedSeed, sciFi),
                        new MovieGenre(keywordMatch, sciFi),
                        new MovieGenre(genreOnlyMatch, sciFi)
                ));
        when(movieKeywordRepository.findByMovieIdInWithKeyword(anyCollection()))
                .thenReturn(List.of(
                        TestFixtures.movieKeyword(reviewedSeed, TestFixtures.keyword(500L, 9101, "time loop")),
                        TestFixtures.movieKeyword(dislikedSeed, TestFixtures.keyword(503L, 9102, "space opera")),
                        TestFixtures.movieKeyword(keywordMatch, TestFixtures.keyword(501L, 9101, "time loop")),
                        TestFixtures.movieKeyword(genreOnlyMatch, TestFixtures.keyword(502L, 9102, "space opera"))
                ));

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("any"), "any", 5);

        List<RecommendationResponseDto> results = recommendationService.getRecommendations(user, request);

        assertEquals(2, results.size());
        assertEquals(1301, results.getFirst().getTmdbId());
    }

    @Test
    void coldStartRecommendationsReuseCachedResults() {
        Movie popularMovie = TestFixtures.movie(181L, 1181, "Cold Start Cache");
        popularMovie.setPopularity(250.0);
        popularMovie.setMovieRating(8.1);

        Genre drama = TestFixtures.genre(71L, 18, "Drama");

        stubPopularCandidates(popularMovie);
        stubTopRatedCandidates();
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(new MovieGenre(popularMovie, drama)));

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("any"), "any", 5);

        List<RecommendationResponseDto> firstResults = recommendationService.getColdStartRecommendations(request);
        List<RecommendationResponseDto> secondResults = recommendationService.getColdStartRecommendations(request);

        assertEquals(firstResults.size(), secondResults.size());
        assertEquals(firstResults.getFirst().getTmdbId(), secondResults.getFirst().getTmdbId());
        verify(catalogIngestionService, times(1)).ensureCatalogSeeded();
        verify(movieRepository, times(1)).findRecommendationReadyPopularMovies(anyDouble(), any(Pageable.class));
    }

    @Test
    void coldStartRotationRequestsBypassResultCaching() {
        Movie popularMovie = TestFixtures.movie(182L, 1182, "Refreshable Cold Start");
        popularMovie.setPopularity(260.0);
        popularMovie.setMovieRating(8.0);
        Genre drama = TestFixtures.genre(72L, 18, "Drama");

        stubPopularCandidates(popularMovie);
        stubTopRatedCandidates();
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(new MovieGenre(popularMovie, drama)));

        RecommendationRequestDto firstRequest = new RecommendationRequestDto(List.of("any"), "any", 5);
        firstRequest.setRefreshToken("rotation-a");
        RecommendationRequestDto secondRequest = new RecommendationRequestDto(List.of("any"), "any", 5);
        secondRequest.setRefreshToken("rotation-a");
        RecommendationRequestDto thirdRequest = new RecommendationRequestDto(List.of("any"), "any", 5);
        thirdRequest.setRefreshToken("rotation-b");

        recommendationService.getColdStartRecommendations(firstRequest);
        recommendationService.getColdStartRecommendations(secondRequest);
        recommendationService.getColdStartRecommendations(thirdRequest);

        // A request carrying rotation state is single-use by construction, so
        // even a repeated refresh token must recompute rather than replay a
        // slate the session has already been shown.
        verify(catalogIngestionService, times(3)).ensureCatalogSeeded();
    }

    @Test
    void coldStartRecommendationsUseStarterGenresToSteerRanking() {
        Movie thrillerCandidate = TestFixtures.movie(183L, 1183, "Starter Thriller");
        thrillerCandidate.setPopularity(110.0);
        thrillerCandidate.setMovieRating(8.0);

        Movie comedyCandidate = TestFixtures.movie(184L, 1184, "Starter Comedy");
        comedyCandidate.setPopularity(160.0);
        comedyCandidate.setMovieRating(8.2);

        Genre thriller = TestFixtures.genre(73L, 53, "Thriller");
        Genre comedy = TestFixtures.genre(74L, 35, "Comedy");

        stubGenreCandidates(thrillerCandidate);
        stubPopularCandidates(comedyCandidate, thrillerCandidate);
        stubTopRatedCandidates(comedyCandidate, thrillerCandidate);
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(
                        new MovieGenre(thrillerCandidate, thriller),
                        new MovieGenre(comedyCandidate, comedy)
                ));

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("any"), "any", 5);
        request.setStarterGenres(List.of("thriller"));

        List<RecommendationResponseDto> results = recommendationService.getColdStartRecommendations(request);

        assertEquals(2, results.size());
        assertEquals(1183, results.getFirst().getTmdbId());
    }

    @Test
    void pipelineTimerIsRecordedForEachPipelineType() {
        // ── solo pipeline ──
        User user = TestFixtures.user(1L, "alice", "alice@example.com");
        Movie thriller = TestFixtures.movie(200L, 2000, "Timer Thriller");
        thriller.setRuntime(100);
        thriller.setMovieRating(8.0);
        WatchList entry = TestFixtures.watchList(900L, user, thriller, WatchListStatus.PLAN_TO_WATCH);
        entry.setAddedAt(LocalDateTime.now().minusDays(10));
        Genre thrillerGenre = TestFixtures.genre(80L, 53, "Thriller");

        when(watchlistRepository.findByUserIdWithDetails(1L)).thenReturn(List.of(entry));
        when(reviewRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(new MovieGenre(thriller, thrillerGenre)));

        SoloRecommendationRequestDto soloRequest = new SoloRecommendationRequestDto();
        soloRequest.setMoods(List.of("any"));
        soloRequest.setRuntimePreference("any");
        soloRequest.setLimit(5);
        recommendationService.getSoloRecommendations(user, soloRequest);

        io.micrometer.core.instrument.Timer soloTimer = meterRegistry.find("recommendation.pipeline.duration")
                .tag("type", "solo").timer();
        assertTrue(soloTimer != null && soloTimer.count() >= 1,
                "Expected a solo pipeline timer recording");
        io.micrometer.core.instrument.Timer soloStageTimer = meterRegistry.find("recommendation.pipeline.stage.duration")
                .tag("type", "solo").tag("stage", "dataFetch").timer();
        assertTrue(soloStageTimer != null && soloStageTimer.count() >= 1,
                "Expected a solo stage timer for dataFetch");

        // ── cold-start pipeline ──
        Movie popular = TestFixtures.movie(201L, 2001, "Timer Popular");
        popular.setMovieRating(7.5);
        popular.setPopularity(200.0);
        stubPopularCandidates(popular);
        stubTopRatedCandidates();
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(new MovieGenre(popular, thrillerGenre)));

        RecommendationRequestDto coldRequest = new RecommendationRequestDto(List.of("any"), "any", 5);
        recommendationService.getColdStartRecommendations(coldRequest);

        io.micrometer.core.instrument.Timer coldTimer = meterRegistry.find("recommendation.pipeline.duration")
                .tag("type", "cold-start").timer();
        assertTrue(coldTimer != null && coldTimer.count() >= 1,
                "Expected a cold-start pipeline timer recording");
        io.micrometer.core.instrument.Timer coldStageTimer = meterRegistry.find("recommendation.pipeline.stage.duration")
                .tag("type", "cold-start").tag("stage", "candidateRetrieval").timer();
        assertTrue(coldStageTimer != null && coldStageTimer.count() >= 1,
                "Expected a cold-start stage timer for candidateRetrieval");

        // ── personalized pipeline ──
        when(watchlistRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());
        when(watchlistRepository.findMovieIdsByUserIdAndStatus(1L, WatchListStatus.WATCHED)).thenReturn(List.of());
        stubPopularCandidates(popular);
        stubTopRatedCandidates();

        RecommendationRequestDto personalizedRequest = new RecommendationRequestDto(List.of("any"), "any", 5);
        recommendationService.getRecommendations(user, personalizedRequest);

        io.micrometer.core.instrument.Timer personalizedTimer = meterRegistry.find("recommendation.pipeline.duration")
                .tag("type", "personalized").timer();
        assertTrue(personalizedTimer != null && personalizedTimer.count() >= 1,
                "Expected a personalized pipeline timer recording");
        io.micrometer.core.instrument.Timer personalizedStageTimer = meterRegistry.find("recommendation.pipeline.stage.duration")
                .tag("type", "personalized").tag("stage", "contextBuild").timer();
        assertTrue(personalizedStageTimer != null && personalizedStageTimer.count() >= 1,
                "Expected a personalized stage timer for contextBuild");
    }

    @Test
    void cacheCountersTrackHitsAndMisses() {
        Movie popular = TestFixtures.movie(210L, 2100, "Cache Counter Movie");
        popular.setPopularity(250.0);
        popular.setMovieRating(8.0);
        Genre drama = TestFixtures.genre(81L, 18, "Drama");

        stubPopularCandidates(popular);
        stubTopRatedCandidates();
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(new MovieGenre(popular, drama)));

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("any"), "any", 5);

        // First cold-start call = cache miss
        recommendationService.getColdStartRecommendations(request);

        io.micrometer.core.instrument.Counter missCounter = meterRegistry.find("recommendation.cache")
                .tag("result", "miss").tag("cache", "coldStartRecommendations").counter();
        assertTrue(missCounter != null && missCounter.count() >= 1,
                "Expected at least one cache miss counter increment");

        // Second cold-start call with same params = cache hit
        recommendationService.getColdStartRecommendations(request);

        io.micrometer.core.instrument.Counter hitCounter = meterRegistry.find("recommendation.cache")
                .tag("result", "hit").tag("cache", "coldStartRecommendations").counter();
        assertTrue(hitCounter != null && hitCounter.count() >= 1,
                "Expected at least one cache hit counter increment");
    }

    @Test
    void userProfileCacheCounterTracksHitsAndMisses() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");
        Movie popular = TestFixtures.movie(220L, 2200, "Profile Cache Movie");
        popular.setPopularity(200.0);
        popular.setMovieRating(7.8);
        Genre thriller = TestFixtures.genre(82L, 53, "Thriller");

        when(watchlistRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());
        when(watchlistRepository.findMovieIdsByUserIdAndStatus(1L, WatchListStatus.WATCHED)).thenReturn(List.of());
        when(reviewRepository.findByUserIdWithDetails(1L)).thenReturn(List.of());
        stubPopularCandidates(popular);
        stubTopRatedCandidates();
        when(movieGenreRepository.findByMovieIdInWithGenre(anyCollection()))
                .thenReturn(List.of(new MovieGenre(popular, thriller)));

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("any"), "any", 5);

        // First call builds profile → cache miss
        recommendationService.getRecommendations(user, request);

        io.micrometer.core.instrument.Counter profileMiss = meterRegistry.find("recommendation.cache")
                .tag("result", "miss").tag("cache", "userProfiles").counter();
        assertTrue(profileMiss != null && profileMiss.count() >= 1,
                "Expected a user profile cache miss on first call");

        // Evict recommendation cache to force re-entry into the pipeline,
        // but user profile cache should still be warm
        cacheManager.getCache("recommendations").clear();

        recommendationService.getRecommendations(user, request);

        io.micrometer.core.instrument.Counter profileHit = meterRegistry.find("recommendation.cache")
                .tag("result", "hit").tag("cache", "userProfiles").counter();
        assertTrue(profileHit != null && profileHit.count() >= 1,
                "Expected a user profile cache hit on second call");
    }

    private void stubGenreCandidates(Movie... movies) {
        lenient().when(movieGenreRepository.findDistinctRecommendationReadyMoviesByGenreNames(anyCollection(), anyDouble(), any(Pageable.class)))
                .thenReturn(List.of(movies));
        lenient().when(movieGenreRepository.findDistinctRecommendationReadyMoviesByGenreNamesExcluding(
                anyCollection(),
                anyDouble(),
                anyCollection(),
                any(Pageable.class)
        )).thenReturn(List.of(movies));
    }

    private void stubPopularCandidates(Movie... movies) {
        lenient().when(movieRepository.findRecommendationReadyPopularMovies(anyDouble(), any(Pageable.class)))
                .thenReturn(List.of(movies));
        lenient().when(movieRepository.findRecommendationReadyPopularMoviesExcluding(anyDouble(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(movies));
    }

    private void stubTopRatedCandidates(Movie... movies) {
        lenient().when(movieRepository.findRecommendationReadyTopRatedMovies(
                        anyDouble(), anyDouble(), anyDouble(), any(Pageable.class)))
                .thenReturn(List.of(movies));
        lenient().when(movieRepository.findRecommendationReadyTopRatedMoviesExcluding(
                        anyDouble(), anyDouble(), anyDouble(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(movies));
    }

    private CatalogRecommendation runtimeRecommendation(
            Long id,
            Integer tmdbId,
            String title,
            double score,
            int runtime
    ) {
        CatalogRecommendation base = recommendation(id, tmdbId, title, score);
        base.movie().setRuntime(runtime);
        return base;
    }

    private CatalogRecommendation recommendation(
            Long id,
            Integer tmdbId,
            String title,
            double score
    ) {
        Movie movie = TestFixtures.movie(id, tmdbId, title);
        movie.setMovieRating(score * 10);
        return new CatalogRecommendation(
                movie,
                List.of("Drama"),
                score,
                1.0,
                false,
                null,
                List.of()
        );
    }

    private CatalogRecommendation recommendation(
            Long id,
            Integer tmdbId,
            String title,
            double score,
            double moodMatch
    ) {
        Movie movie = TestFixtures.movie(id, tmdbId, title);
        movie.setMovieRating(score * 10);
        return new CatalogRecommendation(
                movie,
                List.of("Drama"),
                score,
                moodMatch,
                false,
                null,
                List.of()
        );
    }
}

