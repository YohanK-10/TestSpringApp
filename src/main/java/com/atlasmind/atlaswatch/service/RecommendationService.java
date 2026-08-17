package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.dto.request.RecommendationRequestDto;
import com.atlasmind.atlaswatch.dto.request.SoloRecommendationRequestDto;
import com.atlasmind.atlaswatch.dto.response.RecommendationResponseDto;
import com.atlasmind.atlaswatch.dto.response.SoloRecommendationResponseDto;
import com.atlasmind.atlaswatch.models.*;
import com.atlasmind.atlaswatch.repository.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StopWatch;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private static final String RECOMMENDATION_ALGORITHM_VERSION = "v15-session-aware-rotation";
    private static final String PIPELINE_DURATION_METRIC = "recommendation.pipeline.duration";
    private static final String PIPELINE_STAGE_DURATION_METRIC = "recommendation.pipeline.stage.duration";

    private final WatchlistRepository watchlistRepository;
    private final ReviewRepository reviewRepository;
    private final MovieGenreRepository movieGenreRepository;
    private final MovieKeywordRepository movieKeywordRepository;
    private final MovieRepository movieRepository;
    private final RecommendationImpressionRepository recommendationImpressionRepository;
    private final CatalogIngestionService catalogIngestionService;
    private final UserTasteProfileService userTasteProfileService;
    private final RecommendationScorer recommendationScorer;
    private final RecommendationReasonBuilder recommendationReasonBuilder;
    private final CandidateRetriever candidateRetriever;
    private final CollaborativeSimilarityService collaborativeSimilarityService;
    private final CacheManager cacheManager;
    private final MeterRegistry meterRegistry;

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 10;
    private static final int CONTENT_SIMILARITY_SEED_LIMIT = 2;
    private static final int COLLABORATIVE_SEED_LIMIT = 10;
    private static final int CALIBRATION_RERANKING_POOL_MULTIPLIER = 3;
    private static final int POSITIVE_REVIEW_SEED_THRESHOLD = 8;
    private static final double IGNORED_RECOMMENDATION_PENALTY = 0.15;
    private static final int IGNORED_RECOMMENDATION_LOOKBACK_DAYS = 14;
    private static final long IGNORED_RECOMMENDATION_THRESHOLD = 2L;
    private static final int SUPPRESSED_RECOMMENDATION_LOOKBACK_DAYS = 30;
    private static final long SUPPRESSED_RECOMMENDATION_THRESHOLD = 3L;
    private static final int SESSION_IMPRESSION_WINDOW_HOURS = 6;
    private static final String PERSONALIZED_RECOMMENDATIONS_CACHE = "recommendations";
    private static final String COLD_START_RECOMMENDATIONS_CACHE = "coldStartRecommendations";
    private static final String USER_PROFILES_CACHE = "userProfiles";

    @Transactional(readOnly = true)
    public List<SoloRecommendationResponseDto> getSoloRecommendations(
            User user,
            SoloRecommendationRequestDto request
    ) {
        StopWatch timer = new StopWatch();
        Set<SoloMood> moods = SoloMood.from(
                request != null ? request.getMoods() : null,
                request != null ? request.getMood() : null
        );
        RuntimePreference runtimePreference = RuntimePreference.from(
                request != null ? request.getRuntimePreference() : null
        );
        int limit = normalizeLimit(request != null ? request.getLimit() : null);

        timer.start("dataFetch");
        List<WatchList> candidates = watchlistRepository.findByUserIdWithDetails(user.getId())
                .stream()
                .filter(this::isActiveWatchlistEntry)
                .toList();

        if (candidates.isEmpty()) {
            timer.stop();
            logPipelineTiming("Solo recommendations (empty watchlist)", timer);
            return List.of();
        }

        List<Review> userReviews = reviewRepository.findByUserIdWithDetails(user.getId());

        Set<Long> movieIds = candidates.stream()
                .map(entry -> entry.getMovie().getId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        userReviews.stream()
                .map(review -> review.getMovie().getId())
                .forEach(movieIds::add);

        Map<Long, MovieSignalFeatures> signalFeaturesByMovieId = buildSignalFeaturesByMovieId(movieIds);
        timer.stop();

        timer.start("profileBuild");
        UserTasteProfile tasteProfile = getOrBuildProfile(user.getId(), userReviews, candidates, signalFeaturesByMovieId);
        timer.stop();

        timer.start("scoring");
        List<WatchlistRecommendation> rankedRecommendations = candidates.stream()
                .map(entry -> scoreWatchlistEntry(entry, moods, runtimePreference, tasteProfile, signalFeaturesByMovieId))
                .sorted(Comparator
                        .comparingDouble(WatchlistRecommendation::score).reversed()
                        .thenComparing((WatchlistRecommendation rec) -> rec.movie().getMovieRating(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(rec -> rec.entry().getAddedAt(),
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        timer.stop();

        timer.start("diversityReranking");
        List<WatchlistRecommendation> reranked = recommendationScorer.rerankForDiversity(
                rankedRecommendations,
                limit,
                WatchlistRecommendation::score,
                WatchlistRecommendation::genres
        );
        timer.stop();

        timer.start("responseMapping");
        List<SoloRecommendationResponseDto> result = reranked.stream()
                .map(this::toSoloResponse)
                .toList();
        timer.stop();

        logPipelineTiming("Solo recommendations", timer);
        return result;
    }

    @Transactional
    public List<RecommendationResponseDto> getRecommendations(User user, RecommendationRequestDto request) {
        StopWatch timer = new StopWatch();

        boolean cacheable = !isSessionSpecific(request);

        timer.start("cacheCheck");
        String cacheKey = buildPersonalizedRecommendationCacheKey(user, request);
        CachedRecommendationResponses cachedResponses = cacheable
                ? getCachedRecommendations(PERSONALIZED_RECOMMENDATIONS_CACHE, cacheKey)
                : null;
        timer.stop();

        if (cachedResponses != null) {
            recordRecommendationImpressionsFromResponses(user, cachedResponses.getResponses());
            // Personalized results feed the ignored/suppressed feedback loop, so
            // keep any reused entry to a single replay before refreshing it.
            evictCachedRecommendations(PERSONALIZED_RECOMMENDATIONS_CACHE, cacheKey);
            logPipelineTiming("Personalized recommendations (cache hit)", timer);
            return cachedResponses.getResponses();
        }

        timer.start("catalogSeed");
        seedCatalogIfNeeded();
        timer.stop();

        timer.start("contextBuild");
        RecommendationContext context = buildRecommendationContext(user, request);
        timer.stop();

        List<CatalogRecommendation> recommendations = buildCatalogRecommendations(request, context, timer);

        timer.start("responseMapping");
        List<RecommendationResponseDto> responses = toRecommendationResponses(recommendations);
        timer.stop();

        timer.start("cacheWrite");
        if (cacheable) {
            putCachedRecommendations(PERSONALIZED_RECOMMENDATIONS_CACHE, cacheKey, responses);
        }
        timer.stop();

        timer.start("impressions");
        recordRecommendationImpressions(user, recommendations);
        timer.stop();

        logPipelineTiming("Personalized recommendations", timer);
        return responses;
    }

    @Transactional(readOnly = true)
    public List<RecommendationResponseDto> getColdStartRecommendations(RecommendationRequestDto request) {
        StopWatch timer = new StopWatch();

        boolean cacheable = !isSessionSpecific(request);

        timer.start("cacheCheck");
        String cacheKey = buildColdStartRecommendationCacheKey(request);
        CachedRecommendationResponses cachedResponses = cacheable
                ? getCachedRecommendations(COLD_START_RECOMMENDATIONS_CACHE, cacheKey)
                : null;
        timer.stop();

        if (cachedResponses != null) {
            logPipelineTiming("Cold-start recommendations (cache hit)", timer);
            return cachedResponses.getResponses();
        }

        timer.start("catalogSeed");
        seedCatalogIfNeeded();
        timer.stop();

        timer.start("contextBuild");
        RecommendationContext context = buildColdStartContext(request);
        timer.stop();

        List<CatalogRecommendation> recommendations = buildCatalogRecommendations(
                request, context, timer
        );

        timer.start("responseMapping");
        List<RecommendationResponseDto> responses = toRecommendationResponses(recommendations);
        timer.stop();

        timer.start("cacheWrite");
        if (cacheable) {
            putCachedRecommendations(COLD_START_RECOMMENDATIONS_CACHE, cacheKey, responses);
        }
        timer.stop();

        logPipelineTiming("Cold-start recommendations", timer);
        return responses;
    }

    /**
     * Runs the production retrieval/scoring/reranking path for offline
     * evaluation, deliberately bypassing catalog seeding, caches, user tables,
     * and recommendation-impression writes.
     */
    @Transactional(readOnly = true)
    public RecommendationEvaluationRun evaluateRecommendations(
            RecommendationRequestDto request,
            RecommendationEvaluationPersona persona
    ) {
        Objects.requireNonNull(persona, "Evaluation persona is required.");
        StopWatch timer = new StopWatch();
        timer.start("contextBuild");
        UserTasteProfile profile = persona.warm()
                ? userTasteProfileService.buildBootstrapProfile(
                        persona.starterGenres(), persona.starterKeywords(), List.of())
                : UserTasteProfile.empty();
        RecommendationContext context = new RecommendationContext(
                persona.warm() ? persona.syntheticUserId() : null,
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                resolveExplicitSessionSeenMovieIds(request),
                profile,
                List.of(),
                Map.of(),
                resolveRotationKey(request),
                !persona.warm(),
                persona.warm()
        );
        timer.stop();

        AtomicReference<CandidateRetrievalResult> retrievalTrace = new AtomicReference<>();
        List<CatalogRecommendation> recommendations = buildCatalogRecommendations(
                request, context, timer, retrievalTrace
        );
        CandidateRetrievalResult retrieval = retrievalTrace.get();
        Set<SoloMood> moods = SoloMood.from(request != null ? request.getMoods() : null, null);
        RuntimePreference runtimePreference = RuntimePreference.from(
                request != null ? request.getRuntimePreference() : null
        );
        Set<ReleaseEra> releaseEras = ReleaseEra.from(
                request != null ? request.getReleaseEras() : null
        );
        Map<Long, MovieSignalFeatures> featuresByMovieId = buildSignalFeaturesByMovieId(
                recommendations.stream().map(item -> item.movie().getId()).toList()
        );
        Map<Long, List<String>> sourcesByMovieId = retrieval == null
                ? Map.of()
                : retrieval.candidates().stream().collect(Collectors.toMap(
                        candidate -> candidate.movie().getId(),
                        candidate -> candidate.sourceChannels() == null ? List.of() : candidate.sourceChannels(),
                        (left, right) -> left
                ));

        List<RecommendationEvaluationRun.Item> items = new ArrayList<>();
        for (int index = 0; index < recommendations.size(); index++) {
            CatalogRecommendation recommendation = recommendations.get(index);
            Movie movie = recommendation.movie();
            MovieSignalFeatures features = featuresByMovieId.getOrDefault(movie.getId(), MovieSignalFeatures.EMPTY);
            List<SoloMood> covered = moods.stream()
                    .filter(mood -> mood != SoloMood.ANY)
                    .filter(mood -> mood.isCovered(features.genres(), features.keywords(), movie.getOverview()))
                    .toList();
            items.add(new RecommendationEvaluationRun.Item(
                    index + 1,
                    movie.getId(),
                    movie.getTmdbId(),
                    movie.getMovieTitle(),
                    features.genres(),
                    movie.getRuntime(),
                    movie.getReleaseDate(),
                    movie.getMovieRating(),
                    movie.getVoteCount(),
                    movie.getPopularity(),
                    recommendation.moodMatch(),
                    covered.stream().map(SoloMood::displayLabel).toList(),
                    runtimePreference == RuntimePreference.ANY
                            || runtimePreference.score(movie.getRuntime()) >= 0.95,
                    !ReleaseEra.hasIntent(releaseEras)
                            || releaseEras.stream().anyMatch(era -> era.matches(movie.getReleaseDate())),
                    evidenceSource(covered, features, movie.getOverview()),
                    sourcesByMovieId.getOrDefault(movie.getId(), List.of())
            ));
        }

        Map<String, RecommendationEvaluationRun.ChannelStats> channelStats = retrieval == null
                ? Map.of()
                : retrieval.channelStats().entrySet().stream().collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new RecommendationEvaluationRun.ChannelStats(
                                entry.getValue().fetchedCount(),
                                entry.getValue().sampledCount(),
                                entry.getValue().eligibleCount(),
                                entry.getValue().uniqueAddedCount(),
                                entry.getValue().overlapDroppedCount()
                        ),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        return new RecommendationEvaluationRun(
                RECOMMENDATION_ALGORITHM_VERSION,
                persona.id(),
                persona.warm(),
                timer.getTotalTimeNanos(),
                retrieval == null ? 0 : retrieval.mergedCandidateCount(),
                channelStats,
                items
        );
    }

    private RecommendationContext buildRecommendationContext(User user, RecommendationRequestDto request) {
        List<WatchList> watchlistEntries = watchlistRepository.findByUserIdWithDetails(user.getId());
        List<Review> reviews = reviewRepository.findByUserIdWithDetails(user.getId());

        Map<Long, WatchList> activeWatchlistByMovieId = watchlistEntries.stream()
                .filter(this::isActiveWatchlistEntry)
                .collect(Collectors.toMap(
                        entry -> entry.getMovie().getId(),
                        entry -> entry,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        List<Long> watchedMovieIdsFromQuery = watchlistRepository.findMovieIdsByUserIdAndStatus(
                user.getId(),
                WatchListStatus.WATCHED
        );
        Set<Long> watchedMovieIds = watchedMovieIdsFromQuery == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(watchedMovieIdsFromQuery);
        Set<Long> interactedMovieIds = watchlistEntries.stream()
                .map(entry -> entry.getMovie() != null ? entry.getMovie().getId() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        reviews.stream()
                .map(review -> review.getMovie() != null ? review.getMovie().getId() : null)
                .filter(Objects::nonNull)
                .forEach(interactedMovieIds::add);

        Set<Long> profileMovieIds = new LinkedHashSet<>(activeWatchlistByMovieId.keySet());
        reviews.stream()
                .map(review -> review.getMovie().getId())
                .forEach(profileMovieIds::add);

        Map<Long, MovieSignalFeatures> signalFeaturesByMovieId = buildSignalFeaturesByMovieId(profileMovieIds);
        UserTasteProfile historicalProfile = getOrBuildProfile(
                user.getId(),
                reviews,
                activeWatchlistByMovieId.values(),
                signalFeaturesByMovieId
        );
        List<Movie> requestSeedMovies = resolveSeedMovies(request);
        Map<Long, MovieSignalFeatures> requestSeedSignalFeatures = buildSignalFeaturesByMovieId(
                requestSeedMovies.stream().map(Movie::getId).toList()
        );
        UserTasteProfile tasteProfile = historicalProfile.isColdStart()
                ? buildStarterProfile(request, requestSeedSignalFeatures)
                : historicalProfile;
        Set<Long> penalizedMovieIds = resolveIgnoredMovieIds(
                user.getId(),
                interactedMovieIds,
                IGNORED_RECOMMENDATION_LOOKBACK_DAYS,
                IGNORED_RECOMMENDATION_THRESHOLD
        );
        Set<Long> suppressedMovieIds = resolveIgnoredMovieIds(
                user.getId(),
                interactedMovieIds,
                SUPPRESSED_RECOMMENDATION_LOOKBACK_DAYS,
                SUPPRESSED_RECOMMENDATION_THRESHOLD
        );
        Set<Long> sessionSeenMovieIds = resolveSessionSeenMovieIds(user.getId(), request);
        List<Movie> contentSimilaritySeeds = resolveContentSimilaritySeeds(
                reviews,
                activeWatchlistByMovieId.values(),
                tasteProfile,
                signalFeaturesByMovieId
        );
        if (contentSimilaritySeeds.isEmpty() && !requestSeedMovies.isEmpty()) {
            contentSimilaritySeeds = requestSeedMovies.stream()
                    .filter(Objects::nonNull)
                    .limit(CONTENT_SIMILARITY_SEED_LIMIT)
                    .toList();
        }
        Map<Integer, Double> collaborativeSeeds = resolveCollaborativeSeeds(reviews, activeWatchlistByMovieId.values());
        if (collaborativeSeeds.isEmpty() && !requestSeedMovies.isEmpty()) {
            requestSeedMovies.stream().filter(Objects::nonNull).map(Movie::getTmdbId).filter(Objects::nonNull)
                    .limit(COLLABORATIVE_SEED_LIMIT).forEach(tmdbId -> collaborativeSeeds.putIfAbsent(tmdbId, 0.5));
        }
        Map<Integer, Double> collaborativeScores = collaborativeSimilarityService.scoreCandidates(collaborativeSeeds);
        boolean coldStart = historicalProfile.isColdStart();

        return new RecommendationContext(
                user.getId(),
                activeWatchlistByMovieId,
                watchedMovieIds,
                penalizedMovieIds,
                suppressedMovieIds,
                sessionSeenMovieIds,
                tasteProfile,
                contentSimilaritySeeds,
                collaborativeScores,
                resolveRotationKey(request),
                coldStart,
                true
        );
    }

    private RecommendationContext buildColdStartContext(RecommendationRequestDto request) {
        List<Movie> requestSeedMovies = resolveSeedMovies(request);
        Map<Long, MovieSignalFeatures> signalFeaturesByMovieId = buildSignalFeaturesByMovieId(
                requestSeedMovies.stream().map(Movie::getId).toList()
        );
        UserTasteProfile starterProfile = buildStarterProfile(request, signalFeaturesByMovieId);
        List<Movie> contentSimilaritySeeds = requestSeedMovies.stream()
                .filter(Objects::nonNull)
                .limit(CONTENT_SIMILARITY_SEED_LIMIT)
                .toList();

        return RecommendationContext.createColdStart(
                starterProfile,
                contentSimilaritySeeds,
                resolveExplicitSessionSeenMovieIds(request),
                resolveRotationKey(request)
        );
    }

    private List<CatalogRecommendation> buildCatalogRecommendations(
            RecommendationRequestDto request,
            RecommendationContext context,
            StopWatch timer
    ) {
        return buildCatalogRecommendations(request, context, timer, null);
    }

    private List<CatalogRecommendation> buildCatalogRecommendations(
            RecommendationRequestDto request,
            RecommendationContext context,
            StopWatch timer,
            AtomicReference<CandidateRetrievalResult> retrievalTrace
    ) {
        Set<SoloMood> moods = SoloMood.from(request != null ? request.getMoods() : null, null);
        RuntimePreference runtimePreference = RuntimePreference.from(
                request != null ? request.getRuntimePreference() : null
        );
        Set<ReleaseEra> releaseEras = ReleaseEra.from(request != null ? request.getReleaseEras() : null);
        int limit = normalizeLimit(request != null ? request.getLimit() : null);

        timer.start("candidateRetrieval");
        CandidateRetrievalResult retrieval = candidateRetriever.retrieveCandidatesWithStats(context, moods, limit);
        List<CatalogCandidate> candidates = retrieval.candidates();
        if (retrievalTrace != null) {
            retrievalTrace.set(retrieval);
        }
        timer.stop();

        if (candidates.isEmpty()) {
            return List.of();
        }

        timer.start("scoring");
        Map<Long, MovieSignalFeatures> signalFeaturesByMovieId = buildSignalFeaturesByMovieId(
                candidates.stream().map(candidate -> candidate.movie().getId()).toList()
        );
        List<CatalogRecommendation> scoredRecommendations = candidates.stream()
                .filter(candidate -> recommendationScorer.isRecommendationReady(candidate.movie()))
                .filter(candidate -> recommendationScorer.passesScoringHardFilters(candidate.movie(), runtimePreference))
                .map(candidate -> scoreCatalogMovie(
                        candidate,
                        moods,
                        runtimePreference,
                        releaseEras,
                        context,
                        signalFeaturesByMovieId
                ))
                .toList();
        // Era is an explicit eligibility constraint. Apply it before the
        // fill-aware mood/runtime fallbacks so those gates choose the best
        // available tier inside the requested eras rather than selecting a
        // global tier and discarding out-of-era movies afterwards.
        List<CatalogRecommendation> eraRelevantRecommendations = applyReleaseEraGate(
                scoredRecommendations,
                releaseEras
        );
        List<CatalogRecommendation> moodRelevantRecommendations = applyMoodIntentGate(
                eraRelevantRecommendations,
                moods,
                limit
        );
        List<CatalogRecommendation> sessionRelevantRecommendations = applyRuntimeIntentGate(
                moodRelevantRecommendations,
                runtimePreference,
                limit
        );
        List<CatalogRecommendation> rankedRecommendations = sessionRelevantRecommendations.stream()
                .sorted(Comparator
                        .comparingDouble((CatalogRecommendation recommendation) ->
                                intentAwareScore(recommendation, moods)).reversed()
                        .thenComparing((CatalogRecommendation rec) -> rec.movie().getMovieRating(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing((CatalogRecommendation rec) -> rec.movie().getPopularity(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(rec -> rec.movie().getMovieTitle(), Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
        timer.stop();

        int rerankingPoolSize = Math.min(
                rankedRecommendations.size(),
                Math.max(limit, limit * CALIBRATION_RERANKING_POOL_MULTIPLIER)
        );

        timer.start("diversityReranking");
        List<CatalogRecommendation> diversityReranked = recommendationScorer.rerankForDiversity(
                rankedRecommendations,
                rerankingPoolSize,
                recommendation -> intentAwareScore(recommendation, moods),
                CatalogRecommendation::genres
        );
        timer.stop();

        List<CatalogRecommendation> baselineResult;
        if (context.tasteProfile().positiveGenreWeights().isEmpty()) {
            baselineResult = diversityReranked.stream().limit(limit).toList();
        } else {
            timer.start("calibrationReranking");
            baselineResult = recommendationScorer.rerankForCalibration(
                    diversityReranked,
                    limit,
                    recommendation -> intentAwareScore(recommendation, moods),
                    CatalogRecommendation::genres,
                    context.tasteProfile().positiveGenreWeights()
            );
            timer.stop();
        }

        return applySessionRotation(
                baselineResult,
                rankedRecommendations,
                context.sessionSeenMovieIds(),
                moods,
                runtimePreference,
                context.tasteProfile(),
                limit
        );
    }

    /**
     * Prefers qualified movies not yet displayed in this viewing session. If
     * the eligible unseen pool is too small, already-shown movies remain an
     * explicit fallback so rotation never reduces shortlist fill by itself.
     */
    List<CatalogRecommendation> applySessionRotation(
            List<CatalogRecommendation> baselineResult,
            List<CatalogRecommendation> rankedCandidates,
            Set<Long> sessionSeenMovieIds,
            Set<SoloMood> moods,
            RuntimePreference runtimePreference,
            UserTasteProfile tasteProfile,
            int limit
    ) {
        if (baselineResult == null || baselineResult.isEmpty()
                || sessionSeenMovieIds == null || sessionSeenMovieIds.isEmpty()) {
            return baselineResult == null ? List.of() : List.copyOf(baselineResult);
        }

        List<CatalogRecommendation> unseenCandidates = rankedCandidates.stream()
                .filter(recommendation -> !sessionSeenMovieIds.contains(recommendation.movie().getId()))
                .toList();
        if (unseenCandidates.isEmpty()) {
            return List.copyOf(baselineResult);
        }
        List<CatalogRecommendation> result;
        if (unseenCandidates.size() >= limit) {
            result = new ArrayList<>(rerankCatalogSubset(unseenCandidates, moods, tasteProfile, limit));
        } else {
            result = new ArrayList<>(
                    rerankCatalogSubset(unseenCandidates, moods, tasteProfile, unseenCandidates.size())
            );
        }
        Set<Long> selectedIds = result.stream()
                .map(recommendation -> recommendation.movie().getId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        baselineResult.stream()
                .filter(recommendation -> selectedIds.add(recommendation.movie().getId()))
                .limit(Math.max(0, limit - result.size()))
                .forEach(result::add);
        rankedCandidates.stream()
                .filter(recommendation -> selectedIds.add(recommendation.movie().getId()))
                .limit(Math.max(0, limit - result.size()))
                .forEach(result::add);
        return preserveBaselineRuntimeCompliance(
                preserveBaselineMoodCoverage(result, baselineResult, moods),
                baselineResult,
                runtimePreference
        );
    }

    /**
     * Rotation is a presentation objective, while the user's requested mood
     * remains a relevance constraint. Restore the strongest missing baseline
     * items only when unseen substitutions would lower aggregate rule-derived
     * mood coverage. This keeps as many qualified unseen items as possible
     * without allowing freshness to weaken the pre-rotation slate.
     */
    private List<CatalogRecommendation> preserveBaselineMoodCoverage(
            List<CatalogRecommendation> rotated,
            List<CatalogRecommendation> baseline,
            Set<SoloMood> moods
    ) {
        if (!recommendationScorer.hasMoodIntent(moods)) {
            return List.copyOf(rotated);
        }
        return preserveBaselineFloor(rotated, baseline, CatalogRecommendation::moodMatch);
    }

    /**
     * Runtime is an explicit session constraint, so rotation may not buy
     * freshness with a worse-fitting film. Hold the pre-rotation slate's count
     * of exact runtime matches as a floor, using the same substitution rule as
     * mood coverage.
     */
    private List<CatalogRecommendation> preserveBaselineRuntimeCompliance(
            List<CatalogRecommendation> rotated,
            List<CatalogRecommendation> baseline,
            RuntimePreference runtimePreference
    ) {
        if (runtimePreference == null || runtimePreference == RuntimePreference.ANY) {
            return List.copyOf(rotated);
        }
        return preserveBaselineFloor(rotated, baseline, recommendation ->
                runtimePreference.score(recommendation.movie().getRuntime()) >= 0.95 ? 1.0 : 0.0);
    }

    private List<CatalogRecommendation> preserveBaselineFloor(
            List<CatalogRecommendation> rotated,
            List<CatalogRecommendation> baseline,
            java.util.function.ToDoubleFunction<CatalogRecommendation> qualityOf
    ) {
        if (rotated.isEmpty() || baseline.isEmpty()) {
            return List.copyOf(rotated);
        }

        int comparableSize = Math.min(rotated.size(), baseline.size());
        double coverageFloor = baseline.stream().limit(comparableSize)
                .mapToDouble(qualityOf).sum();
        double rotatedCoverage = rotated.stream().limit(comparableSize)
                .mapToDouble(qualityOf).sum();
        if (rotatedCoverage + 1e-9 >= coverageFloor) {
            return List.copyOf(rotated);
        }

        List<CatalogRecommendation> guarded = new ArrayList<>(rotated);
        Set<Long> baselineIds = baseline.stream().map(item -> item.movie().getId())
                .collect(Collectors.toSet());
        Set<Long> selectedIds = guarded.stream().map(item -> item.movie().getId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<CatalogRecommendation> missingBaseline = baseline.stream()
                .filter(item -> !selectedIds.contains(item.movie().getId()))
                .sorted(Comparator.comparingDouble(qualityOf).reversed())
                .toList();
        List<Integer> replaceableIndexes = java.util.stream.IntStream.range(0, comparableSize)
                .boxed()
                .filter(index -> !baselineIds.contains(guarded.get(index).movie().getId()))
                .sorted(Comparator.comparingDouble(index -> qualityOf.applyAsDouble(guarded.get(index))))
                .toList();

        int replacements = Math.min(missingBaseline.size(), replaceableIndexes.size());
        for (int index = 0; index < replacements && rotatedCoverage + 1e-9 < coverageFloor; index++) {
            int replacementIndex = replaceableIndexes.get(index);
            CatalogRecommendation current = guarded.get(replacementIndex);
            CatalogRecommendation replacement = missingBaseline.get(index);
            if (qualityOf.applyAsDouble(replacement) <= qualityOf.applyAsDouble(current)) {
                continue;
            }
            guarded.set(replacementIndex, replacement);
            rotatedCoverage += qualityOf.applyAsDouble(replacement) - qualityOf.applyAsDouble(current);
        }
        return List.copyOf(guarded);
    }

    private List<CatalogRecommendation> rerankCatalogSubset(
            List<CatalogRecommendation> candidates,
            Set<SoloMood> moods,
            UserTasteProfile tasteProfile,
            int limit
    ) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        int poolSize = Math.min(
                candidates.size(),
                Math.max(limit, limit * CALIBRATION_RERANKING_POOL_MULTIPLIER)
        );
        List<CatalogRecommendation> diverse = recommendationScorer.rerankForDiversity(
                candidates,
                poolSize,
                recommendation -> intentAwareScore(recommendation, moods),
                CatalogRecommendation::genres
        );
        if (tasteProfile == null || tasteProfile.positiveGenreWeights().isEmpty()) {
            return diverse.stream().limit(limit).toList();
        }
        return recommendationScorer.rerankForCalibration(
                diverse,
                limit,
                recommendation -> intentAwareScore(recommendation, moods),
                CatalogRecommendation::genres,
                tasteProfile.positiveGenreWeights()
        );
    }

    private String evidenceSource(
            List<SoloMood> coveredMoods,
            MovieSignalFeatures features,
            String overview
    ) {
        if (coveredMoods.stream().anyMatch(mood -> mood.hasKeywordEvidence(features.keywords()))) {
            return "KEYWORD_BACKED";
        }
        if (coveredMoods.stream().anyMatch(mood -> mood.hasOverviewEvidence(overview))) {
            return "OVERVIEW_ONLY";
        }
        if (coveredMoods.stream().anyMatch(mood -> mood.hasGenreEvidence(features.genres()))) {
            return "GENRE_ONLY";
        }
        return "NONE";
    }

    List<CatalogRecommendation> applyMoodIntentGate(
            List<CatalogRecommendation> recommendations,
            Set<SoloMood> moods,
            int limit
    ) {
        if (!recommendationScorer.hasMoodIntent(moods)
                || recommendations == null
                || recommendations.isEmpty()) {
            return recommendations == null ? List.of() : recommendations;
        }

        int requestedMoodCount = (int) moods.stream()
                .filter(mood -> mood != SoloMood.ANY)
                .count();

        // Keep the strongest coverage tier that can still fill the request,
        // but never pad a multi-mood shortlist with a weak match. One mood
        // retains the graceful catalog fallback, two require at least one,
        // and an exact three-mood request requires all three. Broader blends
        // are exploratory rather than conjunctive, so they require at least
        // 60% coverage. Returning fewer strong picks is still preferable to
        // claiming that a one- or two-mood film satisfies a five-mood blend.
        int minimumCoveredMoodCount = switch (requestedMoodCount) {
            case 1 -> 0;
            case 2 -> 1;
            case 3 -> 3;
            default -> (int) Math.ceil(requestedMoodCount * 0.60);
        };
        for (int coveredMoodCount = requestedMoodCount;
             coveredMoodCount >= Math.max(1, minimumCoveredMoodCount);
             coveredMoodCount--) {
            double minimumCoverage = (double) coveredMoodCount / requestedMoodCount;
            List<CatalogRecommendation> coverageTier = recommendations.stream()
                    .filter(recommendation -> recommendation.moodMatch() + 1e-9 >= minimumCoverage)
                    .toList();
            if (coverageTier.size() >= limit) {
                return coverageTier;
            }
        }

        if (minimumCoveredMoodCount == 0) {
            return recommendations;
        }
        double minimumCoverage = (double) minimumCoveredMoodCount / requestedMoodCount;
        return recommendations.stream()
                .filter(recommendation -> recommendation.moodMatch() + 1e-9 >= minimumCoverage)
                .toList();
    }

    double intentAwareScore(CatalogRecommendation recommendation, Set<SoloMood> moods) {
        if (recommendation == null) {
            return 0.0;
        }

        // A mood selection is an explicit instruction for this session. Give
        // its per-mood coverage a full point of headroom so generic quality,
        // freshness, or profile affinity cannot quietly dominate it. The
        // original weighted score still orders films with similar coverage.
        return recommendation.score()
                + (recommendationScorer.hasMoodIntent(moods) ? recommendation.moodMatch() : 0.0);
    }

    List<CatalogRecommendation> applyRuntimeIntentGate(
            List<CatalogRecommendation> recommendations,
            RuntimePreference runtimePreference,
            int limit
    ) {
        if (runtimePreference == null
                || runtimePreference == RuntimePreference.ANY
                || recommendations == null
                || recommendations.isEmpty()) {
            return recommendations == null ? List.of() : recommendations;
        }

        List<CatalogRecommendation> exactRuntimeMatches = recommendations.stream()
                .filter(recommendation -> runtimePreference.score(recommendation.movie().getRuntime()) >= 0.95)
                .toList();

        return exactRuntimeMatches.size() >= limit ? exactRuntimeMatches : recommendations;
    }

    List<CatalogRecommendation> applyReleaseEraGate(
            List<CatalogRecommendation> recommendations,
            Set<ReleaseEra> releaseEras
    ) {
        if (!ReleaseEra.hasIntent(releaseEras)
                || recommendations == null
                || recommendations.isEmpty()) {
            return recommendations == null ? List.of() : recommendations;
        }

        return recommendations.stream()
                .filter(recommendation -> releaseEras.stream()
                        .anyMatch(era -> era.matches(recommendation.movie().getReleaseDate())))
                .toList();
    }

    private Set<Long> resolveIgnoredMovieIds(
            Long userId,
            Set<Long> interactedMovieIds,
            int lookbackDays,
            long minimumCount
    ) {
        if (userId == null) {
            return Set.of();
        }

        Set<Long> ignoredMovieIds = recommendationImpressionRepository.findMovieIdsWithAtLeastImpressionsSince(
                userId,
                LocalDateTime.now().minusDays(lookbackDays),
                minimumCount
        ).stream()
                .filter(movieId -> interactedMovieIds == null || !interactedMovieIds.contains(movieId))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return ignoredMovieIds.isEmpty() ? Set.of() : ignoredMovieIds;
    }

    private List<Movie> resolveContentSimilaritySeeds(
            List<Review> reviews,
            Collection<WatchList> activeWatchlistEntries,
            UserTasteProfile tasteProfile,
            Map<Long, MovieSignalFeatures> signalFeaturesByMovieId
    ) {
        LinkedHashMap<Long, Movie> seedMovies = new LinkedHashMap<>();

        if (reviews != null) {
            reviews.stream()
                    .filter(review -> review.getRating() != null && review.getRating() >= POSITIVE_REVIEW_SEED_THRESHOLD)
                    .sorted(Comparator
                            .comparing(Review::getRating, Comparator.reverseOrder())
                            .thenComparing(Review::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .map(Review::getMovie)
                    .filter(recommendationScorer::hasUsableOverview)
                    .forEach(movie -> {
                        if (seedMovies.size() < CONTENT_SIMILARITY_SEED_LIMIT) {
                            seedMovies.putIfAbsent(movie.getId(), movie);
                        }
                    });
        }

        if (seedMovies.size() >= CONTENT_SIMILARITY_SEED_LIMIT || activeWatchlistEntries == null) {
            return List.copyOf(seedMovies.values());
        }

        activeWatchlistEntries.stream()
                .filter(Objects::nonNull)
                .filter(entry -> entry.getMovie() != null)
                .filter(entry -> recommendationScorer.hasUsableOverview(entry.getMovie()))
                .sorted(Comparator
                        .comparingDouble((WatchList entry) -> recommendationScorer.watchlistSeedScore(
                                entry.getMovie(),
                                tasteProfile,
                                signalFeaturesByMovieId
                        ))
                        .reversed()
                        .thenComparing((WatchList entry) -> recommendationScorer.qualityScore(entry.getMovie()), Comparator.reverseOrder())
                        .thenComparing(WatchList::getAddedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(entry -> {
                    if (seedMovies.size() < CONTENT_SIMILARITY_SEED_LIMIT) {
                        seedMovies.putIfAbsent(entry.getMovie().getId(), entry.getMovie());
                    }
                });

        return List.copyOf(seedMovies.values());
    }

    private Map<Integer, Double> resolveCollaborativeSeeds(List<Review> reviews, Collection<WatchList> activeWatchlistEntries) {
        LinkedHashMap<Integer, Double> seeds = new LinkedHashMap<>();
        if (reviews != null) {
            reviews.stream()
                    .filter(review -> review.getRating() != null && review.getRating() >= POSITIVE_REVIEW_SEED_THRESHOLD)
                    .sorted(Comparator.comparing(Review::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .forEach(review -> {
                        if (seeds.size() < COLLABORATIVE_SEED_LIMIT) {
                            Movie movie = review.getMovie();
                            if (movie != null && movie.getTmdbId() != null) {
                                double weight = Math.min(1.0, Math.max(0.5, (review.getRating() - 6.0) / 4.0));
                                seeds.putIfAbsent(movie.getTmdbId(), weight);
                            }
                        }
                    });
        }
        if (activeWatchlistEntries != null && seeds.size() < COLLABORATIVE_SEED_LIMIT) {
            activeWatchlistEntries.stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(WatchList::getAddedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .map(WatchList::getMovie)
                    .filter(Objects::nonNull)
                    .filter(movie -> movie.getTmdbId() != null)
                    .forEach(movie -> {
                        if (seeds.size() < COLLABORATIVE_SEED_LIMIT) {
                            seeds.putIfAbsent(movie.getTmdbId(), 0.5);
                        }
                    });
        }
        return seeds;
    }

    private void recordRecommendationImpressions(User user, List<CatalogRecommendation> recommendations) {
        if (user == null || recommendations == null || recommendations.isEmpty()) {
            return;
        }

        saveNewSessionImpressions(user, recommendations.stream()
                .map(CatalogRecommendation::movie)
                .toList());
    }

    private void recordRecommendationImpressionsFromResponses(User user, List<RecommendationResponseDto> recommendations) {
        if (user == null || recommendations == null || recommendations.isEmpty()) {
            return;
        }

        List<Integer> tmdbIds = recommendations.stream()
                .map(RecommendationResponseDto::getTmdbId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (tmdbIds.isEmpty()) {
            return;
        }

        Map<Integer, Movie> moviesByTmdbId = movieRepository.findByTmdbIdIn(tmdbIds).stream()
                .collect(Collectors.toMap(Movie::getTmdbId, movie -> movie));
        saveNewSessionImpressions(user, recommendations.stream()
                .map(RecommendationResponseDto::getTmdbId)
                .map(moviesByTmdbId::get)
                .filter(Objects::nonNull)
                .toList());
    }

    private void saveNewSessionImpressions(User user, Collection<Movie> movies) {
        if (user == null || movies == null || movies.isEmpty()) {
            return;
        }
        Map<Long, Movie> uniqueMovies = movies.stream()
                .filter(Objects::nonNull)
                .filter(movie -> movie.getId() != null)
                .collect(Collectors.toMap(
                        Movie::getId,
                        movie -> movie,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        if (uniqueMovies.isEmpty()) {
            return;
        }
        Set<Long> alreadyRecorded = new HashSet<>(recommendationImpressionRepository.findDistinctMovieIdsSinceAmong(
                user.getId(),
                LocalDateTime.now().minusHours(SESSION_IMPRESSION_WINDOW_HOURS),
                new ArrayList<>(uniqueMovies.keySet())
        ));
        List<RecommendationImpression> newImpressions = uniqueMovies.entrySet().stream()
                .filter(entry -> !alreadyRecorded.contains(entry.getKey()))
                .map(entry -> new RecommendationImpression(null, user, entry.getValue(), null))
                .toList();
        if (!newImpressions.isEmpty()) {
            recommendationImpressionRepository.saveAll(newImpressions);
        }
    }

    private WatchlistRecommendation scoreWatchlistEntry(
            WatchList entry,
            Set<SoloMood> moods,
            RuntimePreference runtimePreference,
            UserTasteProfile tasteProfile,
            Map<Long, MovieSignalFeatures> signalFeaturesByMovieId
    ) {
        Movie movie = entry.getMovie();
        MovieSignalFeatures signalFeatures = signalFeaturesByMovieId.getOrDefault(movie.getId(), MovieSignalFeatures.EMPTY);
        List<String> genres = signalFeatures.genres();
        List<String> keywords = signalFeatures.keywords();
        RecommendationScorer.RankingFeatures features = recommendationScorer.buildWatchlistFeatures(
                movie,
                entry,
                moods,
                runtimePreference,
                tasteProfile,
                genres,
                keywords
        );
        double score = recommendationScorer.computeWeightedScore(
                features,
                tasteProfile.hasSignals(),
                recommendationScorer.hasMoodIntent(moods),
                runtimePreference != RuntimePreference.ANY,
                false,
                true,
                false,
                false
        );
        List<String> reasons = recommendationReasonBuilder.buildWatchlistReasons(
                entry,
                movie,
                moods,
                runtimePreference,
                tasteProfile,
                genres,
                keywords,
                features
        );

        return new WatchlistRecommendation(entry, genres, score, reasons);
    }

    private CatalogRecommendation scoreCatalogMovie(
            CatalogCandidate candidate,
            Set<SoloMood> moods,
            RuntimePreference runtimePreference,
            Set<ReleaseEra> releaseEras,
            RecommendationContext context,
            Map<Long, MovieSignalFeatures> signalFeaturesByMovieId
    ) {
        Movie movie = candidate.movie();
        MovieSignalFeatures signalFeatures = signalFeaturesByMovieId.getOrDefault(movie.getId(), MovieSignalFeatures.EMPTY);
        List<String> genres = signalFeatures.genres();
        List<String> keywords = signalFeatures.keywords();
        WatchList watchlistEntry = context.watchlistByMovieId().get(movie.getId());
        RecommendationScorer.RankingFeatures features = recommendationScorer.buildCatalogFeatures(
                movie,
                watchlistEntry,
                moods,
                runtimePreference,
                context.tasteProfile(),
                genres,
                keywords,
                candidate.sourceCount(),
                context.collaborativeScoresByTmdbId().getOrDefault(movie.getTmdbId(), 0.0)
        );
        double score = recommendationScorer.computeWeightedScore(
                features,
                context.tasteProfile().hasSignals(),
                recommendationScorer.hasMoodIntent(moods),
                runtimePreference != RuntimePreference.ANY,
                true,
                false,
                true,
                !context.collaborativeScoresByTmdbId().isEmpty()
        );
        if (context.penalizedMovieIds().contains(movie.getId())) {
            score = recommendationScorer.clamp01(score - IGNORED_RECOMMENDATION_PENALTY);
        }
        List<String> reasons = recommendationReasonBuilder.buildCatalogReasons(
                movie,
                moods,
                runtimePreference,
                releaseEras,
                context.tasteProfile(),
                genres,
                keywords,
                features,
                candidate.sourceCount(),
                candidate.contentSimilarityScore(),
                context.contentSimilaritySeeds(),
                context.coldStart()
        );

        return new CatalogRecommendation(
                movie,
                genres,
                score,
                features.moodMatch(),
                watchlistEntry != null,
                normalizeWatchlistStatus(watchlistEntry),
                reasons
        );
    }

    private Map<Long, List<String>> buildGenresByMovieId(Collection<Long> movieIds) {
        if (movieIds.isEmpty()) {
            return Collections.emptyMap();
        }

        return movieGenreRepository.findByMovieIdInWithGenre(movieIds)
                .stream()
                .collect(Collectors.groupingBy(
                        mg -> mg.getMovie().getId(),
                        Collectors.mapping(mg -> mg.getGenre().getName(), Collectors.toList())
                ));
    }

    private Map<Long, MovieSignalFeatures> buildSignalFeaturesByMovieId(Collection<Long> movieIds) {
        if (movieIds == null || movieIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, List<String>> genresByMovieId = buildGenresByMovieId(movieIds);
        Map<Long, List<String>> keywordsByMovieId = buildKeywordsByMovieId(movieIds);

        Map<Long, MovieSignalFeatures> signalFeaturesByMovieId = new LinkedHashMap<>();
        for (Long movieId : movieIds) {
            if (movieId == null) {
                continue;
            }
            signalFeaturesByMovieId.put(
                    movieId,
                    new MovieSignalFeatures(
                            List.copyOf(genresByMovieId.getOrDefault(movieId, List.of())),
                            List.copyOf(keywordsByMovieId.getOrDefault(movieId, List.of()))
                    )
            );
        }
        return signalFeaturesByMovieId;
    }

    private UserTasteProfile buildStarterProfile(
            RecommendationRequestDto request,
            Map<Long, MovieSignalFeatures> seedSignalFeaturesByMovieId
    ) {
        return userTasteProfileService.buildBootstrapProfile(
                request != null ? request.getStarterGenres() : List.of(),
                request != null ? request.getStarterKeywords() : List.of(),
                seedSignalFeaturesByMovieId.values()
        );
    }

    private List<Movie> resolveSeedMovies(RecommendationRequestDto request) {
        if (request == null || request.getSeedTmdbIds() == null || request.getSeedTmdbIds().isEmpty()) {
            return List.of();
        }

        List<Integer> tmdbIds = request.getSeedTmdbIds().stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(CONTENT_SIMILARITY_SEED_LIMIT + 3L)
                .toList();
        if (tmdbIds.isEmpty()) {
            return List.of();
        }

        Map<Integer, Movie> moviesByTmdbId = movieRepository.findByTmdbIdIn(tmdbIds).stream()
                .collect(Collectors.toMap(Movie::getTmdbId, movie -> movie, (left, right) -> left, LinkedHashMap::new));
        return tmdbIds.stream()
                .map(moviesByTmdbId::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private Set<Long> resolveSessionSeenMovieIds(Long userId, RecommendationRequestDto request) {
        Set<Long> movieIds = new LinkedHashSet<>(resolveExplicitSessionSeenMovieIds(request));
        if (userId != null) {
            List<Long> recentMovieIds = recommendationImpressionRepository.findDistinctMovieIdsSince(
                    userId,
                    LocalDateTime.now().minusHours(SESSION_IMPRESSION_WINDOW_HOURS)
            );
            if (recentMovieIds != null) {
                recentMovieIds.stream().filter(Objects::nonNull).forEach(movieIds::add);
            }
        }
        return Set.copyOf(movieIds);
    }

    private Set<Long> resolveExplicitSessionSeenMovieIds(RecommendationRequestDto request) {
        if (request == null || request.getSeenTmdbIds() == null || request.getSeenTmdbIds().isEmpty()) {
            return Set.of();
        }
        List<Integer> tmdbIds = request.getSeenTmdbIds().stream()
                .filter(Objects::nonNull)
                .filter(tmdbId -> tmdbId > 0)
                .distinct()
                .limit(50)
                .toList();
        if (tmdbIds.isEmpty()) {
            return Set.of();
        }
        return movieRepository.findByTmdbIdIn(tmdbIds).stream()
                .map(Movie::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Map<Long, List<String>> buildKeywordsByMovieId(Collection<Long> movieIds) {
        if (movieIds == null || movieIds.isEmpty()) {
            return Collections.emptyMap();
        }

        return movieKeywordRepository.findByMovieIdInWithKeyword(movieIds)
                .stream()
                .collect(Collectors.groupingBy(
                        movieKeyword -> movieKeyword.getMovie().getId(),
                        Collectors.mapping(movieKeyword -> movieKeyword.getKeyword().getName(), Collectors.toList())
                ));
    }

    private SoloRecommendationResponseDto toSoloResponse(WatchlistRecommendation recommendation) {
        WatchList entry = recommendation.entry();
        Movie movie = entry.getMovie();

        return SoloRecommendationResponseDto.builder()
                .tmdbId(movie.getTmdbId())
                .movieTitle(movie.getMovieTitle())
                .movieOverview(movie.getOverview())
                .posterPath(movie.getPosterPath())
                .backdropPath(movie.getBackdropPath())
                .releaseDate(movie.getReleaseDate())
                .rating(movie.getMovieRating())
                .voteCount(movie.getVoteCount())
                .runtime(movie.getRuntime())
                .popularity(movie.getPopularity())
                .genres(copyStrings(recommendation.genres()))
                .watchlistStatus(normalizeWatchlistStatus(entry))
                .addedAt(entry.getAddedAt())
                .score(toDisplayScore(recommendation.score()))
                .reasons(copyStrings(recommendation.reasons()))
                .build();
    }

    private RecommendationResponseDto toRecommendationResponse(CatalogRecommendation recommendation) {
        Movie movie = recommendation.movie();

        return RecommendationResponseDto.builder()
                .tmdbId(movie.getTmdbId())
                .movieTitle(movie.getMovieTitle())
                .movieOverview(movie.getOverview())
                .posterPath(movie.getPosterPath())
                .backdropPath(movie.getBackdropPath())
                .releaseDate(movie.getReleaseDate())
                .rating(movie.getMovieRating())
                .voteCount(movie.getVoteCount())
                .runtime(movie.getRuntime())
                .popularity(movie.getPopularity())
                .genres(copyStrings(recommendation.genres()))
                .onWatchlist(recommendation.onWatchlist())
                .watchlistStatus(recommendation.watchlistStatus())
                .reasons(copyStrings(recommendation.reasons()))
                .build();
    }

    private List<RecommendationResponseDto> toRecommendationResponses(List<CatalogRecommendation> recommendations) {
        return recommendations.stream()
                .map(this::toRecommendationResponse)
                .toList();
    }

    private boolean isActiveWatchlistEntry(WatchList entry) {
        return entry.getStatus() != null && entry.getStatus() != WatchListStatus.WATCHED;
    }

    private String normalizeWatchlistStatus(WatchList entry) {
        if (entry == null || entry.getStatus() == null) {
            return null;
        }

        if (entry.getStatus() == WatchListStatus.WATCHING) {
            return WatchListStatus.PLAN_TO_WATCH.name();
        }

        return entry.getStatus().name();
    }

    private void seedCatalogIfNeeded() {
        try {
            catalogIngestionService.ensureCatalogSeeded();
        } catch (RuntimeException ignored) {
            // If TMDB seeding fails, we still rank whatever is already cached locally.
        }
    }

    private int normalizeLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }
        if (requestedLimit < 1) {
            throw new IllegalArgumentException("Recommendation limit must be at least 1.");
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private int toDisplayScore(double weightedScore) {
        return (int) Math.round(recommendationScorer.clamp01(weightedScore) * 100.0);
    }

    private UserTasteProfile getOrBuildProfile(
            Long userId,
            List<Review> reviews,
            Collection<WatchList> watchlistEntries,
            Map<Long, MovieSignalFeatures> signalFeaturesByMovieId
    ) {
        if (userId != null) {
            String cacheKey = "user:" + userId;
            try {
                Cache cache = cacheManager.getCache(USER_PROFILES_CACHE);
                if (cache != null) {
                    CachedUserTasteProfile cached = cache.get(cacheKey, CachedUserTasteProfile.class);
                    if (cached != null) {
                        meterRegistry.counter("recommendation.cache", "result", "hit", "cache", USER_PROFILES_CACHE).increment();
                        return cached.toProfile();
                    }
                }
                meterRegistry.counter("recommendation.cache", "result", "miss", "cache", USER_PROFILES_CACHE).increment();
            } catch (Exception ex) {
                log.warn("Failed to read from cache '{}': {}", USER_PROFILES_CACHE, ex.getMessage());
                meterRegistry.counter("recommendation.cache", "result", "error", "cache", USER_PROFILES_CACHE).increment();
            }

            UserTasteProfile profile = userTasteProfileService.buildProfile(reviews, watchlistEntries, signalFeaturesByMovieId);

            try {
                Cache cache = cacheManager.getCache(USER_PROFILES_CACHE);
                if (cache != null) {
                    cache.put(cacheKey, new CachedUserTasteProfile(profile));
                }
            } catch (Exception ex) {
                log.warn("Failed to write to cache '{}': {}", USER_PROFILES_CACHE, ex.getMessage());
            }

            return profile;
        }

        return userTasteProfileService.buildProfile(reviews, watchlistEntries, signalFeaturesByMovieId);
    }

    private CachedRecommendationResponses getCachedRecommendations(String cacheName, String cacheKey) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                meterRegistry.counter("recommendation.cache", "result", "miss", "cache", cacheName).increment();
                return null;
            }
            CachedRecommendationResponses result = cache.get(cacheKey, CachedRecommendationResponses.class);
            String outcome = result != null ? "hit" : "miss";
            meterRegistry.counter("recommendation.cache", "result", outcome, "cache", cacheName).increment();
            return result;
        } catch (Exception ex) {
            log.warn("Failed to read from cache '{}': {}", cacheName, ex.getMessage());
            meterRegistry.counter("recommendation.cache", "result", "error", "cache", cacheName).increment();
            return null;
        }
    }

    private void putCachedRecommendations(String cacheName, String cacheKey, List<RecommendationResponseDto> responses) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.put(cacheKey, new CachedRecommendationResponses(responses));
            }
        } catch (Exception ex) {
            log.warn("Failed to write to cache '{}': {}", cacheName, ex.getMessage());
        }
    }

    private void evictCachedRecommendations(String cacheName, String cacheKey) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.evict(cacheKey);
            }
        } catch (Exception ex) {
            log.warn("Failed to evict from cache '{}': {}", cacheName, ex.getMessage());
        }
    }

    private String buildPersonalizedRecommendationCacheKey(User user, RecommendationRequestDto request) {
        Long userId = user != null ? user.getId() : null;
        return "user:" + Objects.requireNonNullElse(userId, -1L) + "|" + buildNormalizedRecommendationCacheSuffix(request);
    }

    private String buildColdStartRecommendationCacheKey(RecommendationRequestDto request) {
        return "cold-start|" + buildNormalizedRecommendationCacheSuffix(request);
    }

    private String buildNormalizedRecommendationCacheSuffix(RecommendationRequestDto request) {
        Set<SoloMood> moods = SoloMood.from(request != null ? request.getMoods() : null, null);
        RuntimePreference runtimePreference = RuntimePreference.from(
                request != null ? request.getRuntimePreference() : null
        );
        Set<ReleaseEra> releaseEras = ReleaseEra.from(request != null ? request.getReleaseEras() : null);
        int limit = normalizeLimit(request != null ? request.getLimit() : null);

        String moodKey = moods.stream()
                .map(Enum::name)
                .sorted()
                .collect(Collectors.joining(","));
        String eraKey = releaseEras.stream()
                .map(ReleaseEra::value)
                .sorted()
                .collect(Collectors.joining(","));
        String starterGenreKey = normalizeTokens(request != null ? request.getStarterGenres() : null);
        String starterKeywordKey = normalizeTokens(request != null ? request.getStarterKeywords() : null);
        String seedTmdbKey = normalizeIntegers(request != null ? request.getSeedTmdbIds() : null);
        String seenTmdbKey = normalizeIntegers(request != null ? request.getSeenTmdbIds() : null);
        String refreshKey = normalizeText(request != null ? request.getRefreshToken() : null);
        return "algorithm:" + RECOMMENDATION_ALGORITHM_VERSION
                + "|moods:" + moodKey
                + "|runtime:" + runtimePreference.name()
                + "|eras:" + eraKey
                + "|limit:" + limit
                + "|refresh:" + refreshKey
                + "|starterGenres:" + starterGenreKey
                + "|starterKeywords:" + starterKeywordKey
                + "|seedTmdbIds:" + seedTmdbKey
                + "|seenTmdbIds:" + seenTmdbKey;
    }

    /**
     * A request carrying rotation state describes one moment in one viewing
     * session. Caching it can only ever produce a single-use entry, and reusing
     * one would replay movies the session has already shown.
     */
    private boolean isSessionSpecific(RecommendationRequestDto request) {
        if (request == null) {
            return false;
        }
        return !normalizeText(request.getRefreshToken()).isEmpty()
                || (request.getSeenTmdbIds() != null && !request.getSeenTmdbIds().isEmpty());
    }

    private String resolveRotationKey(RecommendationRequestDto request) {
        String refreshKey = normalizeText(request != null ? request.getRefreshToken() : null);
        return refreshKey.isEmpty() ? null : refreshKey;
    }

    private String normalizeTokens(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }

        return values.stream()
                .filter(Objects::nonNull)
                .map(this::normalizeText)
                .filter(value -> !value.isEmpty())
                .sorted()
                .collect(Collectors.joining(","));
    }

    private String normalizeIntegers(Collection<Integer> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }

        return values.stream()
                .filter(Objects::nonNull)
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> copyStrings(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private void logPipelineTiming(String pipelineName, StopWatch timer) {
        long totalMs = timer.getTotalTimeMillis();

        String type = resolvePipelineType(pipelineName);
        meterRegistry.timer(PIPELINE_DURATION_METRIC, "type", type)
                .record(totalMs, TimeUnit.MILLISECONDS);
        for (StopWatch.TaskInfo task : timer.getTaskInfo()) {
            meterRegistry.timer(
                            PIPELINE_STAGE_DURATION_METRIC,
                            "type",
                            type,
                            "stage",
                            task.getTaskName()
                    )
                    .record(task.getTimeMillis(), TimeUnit.MILLISECONDS);
        }

        if (!log.isInfoEnabled()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (StopWatch.TaskInfo task : timer.getTaskInfo()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(task.getTaskName()).append('=').append(task.getTimeMillis()).append("ms");
        }
        sb.append(", total=").append(totalMs).append("ms");
        log.info("{} -> {}", pipelineName, sb);
    }

    private String resolvePipelineType(String pipelineName) {
        if (pipelineName == null) {
            return "unknown";
        }
        String lower = pipelineName.toLowerCase();
        if (lower.contains("cold-start") || lower.contains("cold start")) {
            return "cold-start";
        }
        if (lower.contains("solo")) {
            return "solo";
        }
        if (lower.contains("personalized")) {
            return "personalized";
        }
        return "unknown";
    }
}
