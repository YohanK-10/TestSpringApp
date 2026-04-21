package com.atlasmind.ai_travel_recommendation.service;

import com.atlasmind.ai_travel_recommendation.config.RecommendationScoringProperties;
import com.atlasmind.ai_travel_recommendation.dto.request.RecommendationRequestDto;
import com.atlasmind.ai_travel_recommendation.dto.request.SoloRecommendationRequestDto;
import com.atlasmind.ai_travel_recommendation.dto.response.RecommendationResponseDto;
import com.atlasmind.ai_travel_recommendation.dto.response.SoloRecommendationResponseDto;
import com.atlasmind.ai_travel_recommendation.models.*;
import com.atlasmind.ai_travel_recommendation.repository.MovieGenreRepository;
import com.atlasmind.ai_travel_recommendation.repository.MovieRepository;
import com.atlasmind.ai_travel_recommendation.repository.RecommendationImpressionRepository;
import com.atlasmind.ai_travel_recommendation.repository.ReviewRepository;
import com.atlasmind.ai_travel_recommendation.repository.WatchlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final WatchlistRepository watchlistRepository;
    private final ReviewRepository reviewRepository;
    private final MovieGenreRepository movieGenreRepository;
    private final MovieRepository movieRepository;
    private final RecommendationImpressionRepository recommendationImpressionRepository;
    private final CatalogIngestionService catalogIngestionService;
    private final UserTasteProfileService userTasteProfileService;
    private final RecommendationScoringProperties scoringProperties;

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 10;
    private static final int MIN_CANDIDATE_POOL = 80;
    private static final int MAX_CANDIDATE_POOL = 250;
    private static final int MAX_TOP_GENRES = 4;
    private static final double MIN_RECOMMENDATION_RATING = 5.5;
    private static final int MIN_RECOMMENDATION_RUNTIME = 70;
    private static final int MIN_CHANNEL_POOL_SIZE = 50;
    private static final int MAX_CHANNEL_POOL_SIZE = 80;
    private static final int CHANNEL_POOL_MULTIPLIER = 10;
    private static final int OVERSAMPLE_MULTIPLIER = 3;
    private static final int MAX_OVERSAMPLE_SIZE = 180;
    private static final int MIN_WATCHLIST_CHANNEL_LIMIT = 8;
    private static final int MAX_WATCHLIST_CHANNEL_LIMIT = 20;
    private static final int WATCHLIST_CHANNEL_LIMIT_MULTIPLIER = 3;
    private static final int MIN_CATALOG_CHANNEL_LIMIT = 12;
    private static final int MAX_CATALOG_CHANNEL_LIMIT = 30;
    private static final int CATALOG_CHANNEL_LIMIT_MULTIPLIER = 4;
    private static final double QUALITY_SAMPLING_WEIGHT = 0.60;
    private static final double POPULARITY_SAMPLING_WEIGHT = 0.25;
    private static final double FRESHNESS_SAMPLING_WEIGHT = 0.15;
    private static final double MIN_SAMPLING_WEIGHT = 0.05;
    private static final String CONTENT_SIMILARITY_CHANNEL = "content-similarity";
    private static final int CONTENT_SIMILARITY_SEED_LIMIT = 2;
    private static final int POSITIVE_REVIEW_SEED_THRESHOLD = 8;
    private static final double MIN_CONTENT_SIMILARITY_SCORE = 0.08;
    private static final double IGNORED_RECOMMENDATION_PENALTY = 0.15;
    private static final int IGNORED_RECOMMENDATION_LOOKBACK_DAYS = 14;
    private static final long IGNORED_RECOMMENDATION_THRESHOLD = 2L;
    private static final int SUPPRESSED_RECOMMENDATION_LOOKBACK_DAYS = 30;
    private static final long SUPPRESSED_RECOMMENDATION_THRESHOLD = 3L;
    private static final Set<String> CONTENT_STOPWORDS = Set.of(
            "about", "after", "all", "also", "and", "are", "because", "before", "been", "being",
            "between", "but", "can", "during", "each", "for", "from", "into", "its", "more",
            "over", "that", "their", "them", "then", "there", "they", "this", "through", "when",
            "where", "which", "while", "with", "your"
    );

    @Transactional(readOnly = true)
    public List<SoloRecommendationResponseDto> getSoloRecommendations(
            User user,
            SoloRecommendationRequestDto request
    ) {
        Set<SoloMood> moods = SoloMood.from(
                request != null ? request.getMoods() : null,
                request != null ? request.getMood() : null
        );
        RuntimePreference runtimePreference = RuntimePreference.from(
                request != null ? request.getRuntimePreference() : null
        );
        int limit = normalizeLimit(request != null ? request.getLimit() : null);

        List<WatchList> candidates = watchlistRepository.findByUserIdWithDetails(user.getId())
                .stream()
                .filter(this::isActiveWatchlistEntry)
                .toList();

        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Review> userReviews = reviewRepository.findByUserIdWithDetails(user.getId());

        Set<Long> movieIds = candidates.stream()
                .map(entry -> entry.getMovie().getId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        userReviews.stream()
                .map(review -> review.getMovie().getId())
                .forEach(movieIds::add);

        Map<Long, List<String>> genresByMovieId = buildGenresByMovieId(movieIds);
        UserTasteProfile tasteProfile = userTasteProfileService.buildProfile(userReviews, candidates, genresByMovieId);

        List<WatchlistRecommendation> rankedRecommendations = candidates.stream()
                .map(entry -> scoreWatchlistEntry(entry, moods, runtimePreference, tasteProfile, genresByMovieId))
                .sorted(Comparator
                        .comparingDouble(WatchlistRecommendation::score).reversed()
                        .thenComparing((WatchlistRecommendation rec) -> rec.movie().getMovieRating(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(rec -> rec.entry().getAddedAt(),
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        return rerankForDiversity(
                        rankedRecommendations,
                        limit,
                        WatchlistRecommendation::score,
                        WatchlistRecommendation::genres
                ).stream()
                .map(this::toSoloResponse)
                .toList();
    }

    @Transactional
    public List<RecommendationResponseDto> getRecommendations(User user, RecommendationRequestDto request) {
        seedCatalogIfNeeded();
        RecommendationContext context = buildRecommendationContext(user);
        List<CatalogRecommendation> recommendations = buildCatalogRecommendations(request, context);
        recordRecommendationImpressions(user, recommendations);
        return recommendations.stream()
                .map(this::toRecommendationResponse)
                .toList();
    }

    @Transactional
    public List<RecommendationResponseDto> getColdStartRecommendations(RecommendationRequestDto request) {
        seedCatalogIfNeeded();
        return buildCatalogRecommendations(request, RecommendationContext.createColdStart()).stream()
                .map(this::toRecommendationResponse)
                .toList();
    }

    private RecommendationContext buildRecommendationContext(User user) {
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

        Map<Long, List<String>> genresByMovieId = buildGenresByMovieId(profileMovieIds);
        UserTasteProfile tasteProfile = userTasteProfileService.buildProfile(
                reviews,
                activeWatchlistByMovieId.values(),
                genresByMovieId
        );
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
        List<Movie> contentSimilaritySeeds = resolveContentSimilaritySeeds(
                reviews,
                activeWatchlistByMovieId.values(),
                tasteProfile,
                genresByMovieId
        );
        boolean coldStart = tasteProfile.isColdStart();

        return new RecommendationContext(
                user.getId(),
                activeWatchlistByMovieId,
                watchedMovieIds,
                penalizedMovieIds,
                suppressedMovieIds,
                tasteProfile,
                contentSimilaritySeeds,
                coldStart,
                true
        );
    }

    private List<CatalogRecommendation> buildCatalogRecommendations(
            RecommendationRequestDto request,
            RecommendationContext context
    ) {
        Set<SoloMood> moods = SoloMood.from(request != null ? request.getMoods() : null, null);
        RuntimePreference runtimePreference = RuntimePreference.from(
                request != null ? request.getRuntimePreference() : null
        );
        int limit = normalizeLimit(request != null ? request.getLimit() : null);

        List<CatalogCandidate> candidates = retrieveCandidates(context, moods, limit);
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<Long, List<String>> genresByMovieId = buildGenresByMovieId(
                candidates.stream().map(candidate -> candidate.movie().getId()).toList()
        );

        List<CatalogRecommendation> rankedRecommendations = candidates.stream()
                .filter(candidate -> isRecommendationReady(candidate.movie()))
                .filter(candidate -> passesScoringHardFilters(candidate.movie(), runtimePreference))
                .map(candidate -> scoreCatalogMovie(candidate, moods, runtimePreference, context, genresByMovieId))
                .sorted(Comparator
                        .comparingDouble(CatalogRecommendation::score).reversed()
                        .thenComparing((CatalogRecommendation rec) -> rec.movie().getMovieRating(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing((CatalogRecommendation rec) -> rec.movie().getPopularity(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(rec -> rec.movie().getMovieTitle(), Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();

        return rerankForDiversity(
                        rankedRecommendations,
                        limit,
                        CatalogRecommendation::score,
                        CatalogRecommendation::genres
                ).stream()
                .toList();
    }

    private List<CatalogCandidate> retrieveCandidates(RecommendationContext context, Set<SoloMood> moods, int limit) {
        int channelPoolSize = Math.min(
                MAX_CHANNEL_POOL_SIZE,
                Math.max(MIN_CHANNEL_POOL_SIZE, limit * CHANNEL_POOL_MULTIPLIER)
        );
        int oversampleSize = Math.min(channelPoolSize * OVERSAMPLE_MULTIPLIER, MAX_OVERSAMPLE_SIZE);
        int totalCandidateLimit = Math.min(MAX_CANDIDATE_POOL, Math.max(MIN_CANDIDATE_POOL, limit * 20));
        int watchlistChannelLimit = Math.min(
                MAX_WATCHLIST_CHANNEL_LIMIT,
                Math.max(MIN_WATCHLIST_CHANNEL_LIMIT, limit * WATCHLIST_CHANNEL_LIMIT_MULTIPLIER)
        );
        int catalogChannelLimit = Math.min(
                MAX_CATALOG_CHANNEL_LIMIT,
                Math.max(MIN_CATALOG_CHANNEL_LIMIT, limit * CATALOG_CHANNEL_LIMIT_MULTIPLIER)
        );

        Set<Long> baseExcludedMovieIds = baseExcludedMovieIds(context);
        LinkedHashMap<Long, CandidateAccumulator> mergedCandidates = new LinkedHashMap<>();

        ChannelRetrievalStats watchlistStats = appendChannel(
                createStaticChannelBatch(
                        "watchlist",
                        context.watchlistByMovieId().values().stream()
                                .map(WatchList::getMovie)
                                .toList()
                ),
                mergedCandidates,
                baseExcludedMovieIds,
                watchlistChannelLimit,
                totalCandidateLimit
        );

        List<String> topGenres = context.tasteProfile().topPositiveGenres(MAX_TOP_GENRES);
        ChannelRetrievalStats genreAffinityStats = appendChannel(
                createSampledChannelBatch(
                        "genre-affinity",
                        fetchGenreCandidates(topGenres, combineExcludedMovieIds(baseExcludedMovieIds, mergedCandidates.keySet()), oversampleSize),
                        channelPoolSize,
                        context
                ),
                mergedCandidates,
                baseExcludedMovieIds,
                catalogChannelLimit,
                totalCandidateLimit
        );

        List<String> moodGenres = resolveMoodGenres(moods);
        ChannelRetrievalStats moodStats = appendChannel(
                createSampledChannelBatch(
                        "mood-aligned",
                        fetchGenreCandidates(moodGenres, combineExcludedMovieIds(baseExcludedMovieIds, mergedCandidates.keySet()), oversampleSize),
                        channelPoolSize,
                        context
                ),
                mergedCandidates,
                baseExcludedMovieIds,
                catalogChannelLimit,
                totalCandidateLimit
        );

        ChannelRetrievalStats popularStats = appendChannel(
                createSampledChannelBatch(
                        "popular",
                        fetchPopularCandidates(combineExcludedMovieIds(baseExcludedMovieIds, mergedCandidates.keySet()), oversampleSize),
                        channelPoolSize,
                        context
                ),
                mergedCandidates,
                baseExcludedMovieIds,
                catalogChannelLimit,
                totalCandidateLimit
        );

        ChannelRetrievalStats highRatedStats = appendChannel(
                createSampledChannelBatch(
                        "high-rated",
                        fetchTopRatedCandidates(combineExcludedMovieIds(baseExcludedMovieIds, mergedCandidates.keySet()), oversampleSize),
                        channelPoolSize,
                        context
                ),
                mergedCandidates,
                baseExcludedMovieIds,
                catalogChannelLimit,
                totalCandidateLimit
        );

        ChannelRetrievalStats contentSimilarityStats = appendChannel(
                buildContentSimilarityBatch(
                        context,
                        baseExcludedMovieIds,
                        oversampleSize,
                        channelPoolSize
                ),
                mergedCandidates,
                baseExcludedMovieIds,
                catalogChannelLimit,
                totalCandidateLimit
        );

        log.info(
                "Recommendation candidate retrieval -> watchlist[fetched={}, sampled={}, eligible={}, added={}, overlapDropped={}], genreAffinity[fetched={}, sampled={}, eligible={}, added={}, overlapDropped={}], moodAligned[fetched={}, sampled={}, eligible={}, added={}, overlapDropped={}], popular[fetched={}, sampled={}, eligible={}, added={}, overlapDropped={}], highRated[fetched={}, sampled={}, eligible={}, added={}, overlapDropped={}], contentSimilarity[fetched={}, sampled={}, eligible={}, added={}, overlapDropped={}], merged={}, penalized={}, suppressed={}, anonymous={}, coldStart={}",
                watchlistStats.fetchedCount(), watchlistStats.sampledCount(), watchlistStats.eligibleCount(), watchlistStats.uniqueAddedCount(), watchlistStats.overlapDroppedCount(),
                genreAffinityStats.fetchedCount(), genreAffinityStats.sampledCount(), genreAffinityStats.eligibleCount(), genreAffinityStats.uniqueAddedCount(), genreAffinityStats.overlapDroppedCount(),
                moodStats.fetchedCount(), moodStats.sampledCount(), moodStats.eligibleCount(), moodStats.uniqueAddedCount(), moodStats.overlapDroppedCount(),
                popularStats.fetchedCount(), popularStats.sampledCount(), popularStats.eligibleCount(), popularStats.uniqueAddedCount(), popularStats.overlapDroppedCount(),
                highRatedStats.fetchedCount(), highRatedStats.sampledCount(), highRatedStats.eligibleCount(), highRatedStats.uniqueAddedCount(), highRatedStats.overlapDroppedCount(),
                contentSimilarityStats.fetchedCount(), contentSimilarityStats.sampledCount(), contentSimilarityStats.eligibleCount(), contentSimilarityStats.uniqueAddedCount(), contentSimilarityStats.overlapDroppedCount(),
                mergedCandidates.size(),
                context.penalizedMovieIds().size(),
                context.suppressedMovieIds().size(),
                !context.authenticated(),
                context.coldStart()
        );

        return mergedCandidates.values().stream()
                .limit(totalCandidateLimit)
                .map(CandidateAccumulator::toCatalogCandidate)
                .toList();
    }

    private ChannelRetrievalStats appendChannel(
            ChannelCandidateBatch batch,
            LinkedHashMap<Long, CandidateAccumulator> mergedCandidates,
            Set<Long> hardExcludedMovieIds,
            int channelLimit,
            int totalCandidateLimit
    ) {
        if (batch == null || batch.sampledCandidates().isEmpty()) {
            return new ChannelRetrievalStats(
                    batch == null ? "unknown" : batch.channelName(),
                    batch == null ? 0 : batch.fetchedCandidates().size(),
                    batch == null ? 0 : batch.sampledCandidates().size(),
                    0,
                    0,
                    0
            );
        }

        int eligibleCount = 0;
        int uniqueAddedCount = 0;
        int overlapDroppedCount = 0;
        for (PreparedCandidate candidate : batch.sampledCandidates()) {
            Movie movie = candidate.movie();
            if (!isRetrievableCandidate(movie, hardExcludedMovieIds)) {
                continue;
            }

            eligibleCount++;
            CandidateAccumulator existingCandidate = mergedCandidates.get(movie.getId());
            if (existingCandidate != null) {
                existingCandidate.addSourceChannel(batch.channelName(), candidate.contentSimilarityScore());
                overlapDroppedCount++;
                continue;
            }

            if (uniqueAddedCount >= channelLimit
                    || mergedCandidates.size() >= totalCandidateLimit) {
                continue;
            }

            mergedCandidates.put(movie.getId(), CandidateAccumulator.from(batch.channelName(), candidate));
            uniqueAddedCount++;
        }

        return new ChannelRetrievalStats(
                batch.channelName(),
                batch.fetchedCandidates().size(),
                batch.sampledCandidates().size(),
                eligibleCount,
                uniqueAddedCount,
                overlapDroppedCount
        );
    }

    private ChannelCandidateBatch createStaticChannelBatch(String channelName, List<Movie> sourceMovies) {
        List<PreparedCandidate> preparedCandidates = toPreparedCandidates(sourceMovies);
        return new ChannelCandidateBatch(channelName, preparedCandidates, preparedCandidates);
    }

    private ChannelCandidateBatch createSampledChannelBatch(
            String channelName,
            List<Movie> fetchedMovies,
            int sampleSize,
            RecommendationContext context
    ) {
        List<PreparedCandidate> preparedCandidates = toPreparedCandidates(fetchedMovies);
        return new ChannelCandidateBatch(
                channelName,
                preparedCandidates,
                sampleWeightedCandidates(channelName, preparedCandidates, sampleSize, context)
        );
    }

    private Set<Long> baseExcludedMovieIds(RecommendationContext context) {
        Set<Long> excludedMovieIds = new LinkedHashSet<>(context.watchedMovieIds());
        excludedMovieIds.addAll(context.suppressedMovieIds());
        return excludedMovieIds;
    }

    private Set<Long> combineExcludedMovieIds(Set<Long> baseExcludedMovieIds, Collection<Long> additionalMovieIds) {
        Set<Long> excludedMovieIds = new LinkedHashSet<>(baseExcludedMovieIds);
        if (additionalMovieIds != null) {
            excludedMovieIds.addAll(additionalMovieIds);
        }
        return excludedMovieIds;
    }

    private List<Movie> fetchGenreCandidates(Collection<String> genreNames, Collection<Long> excludedMovieIds, int fetchSize) {
        if (genreNames == null || genreNames.isEmpty()) {
            return List.of();
        }

        PageRequest pageRequest = PageRequest.of(0, fetchSize);
        if (excludedMovieIds == null || excludedMovieIds.isEmpty()) {
            return movieGenreRepository.findDistinctRecommendationReadyMoviesByGenreNames(
                    genreNames,
                    MIN_RECOMMENDATION_RATING,
                    pageRequest
            );
        }

        return movieGenreRepository.findDistinctRecommendationReadyMoviesByGenreNamesExcluding(
                genreNames,
                MIN_RECOMMENDATION_RATING,
                new LinkedHashSet<>(excludedMovieIds),
                pageRequest
        );
    }

    private List<Movie> fetchPopularCandidates(Collection<Long> excludedMovieIds, int fetchSize) {
        PageRequest pageRequest = PageRequest.of(0, fetchSize);
        if (excludedMovieIds == null || excludedMovieIds.isEmpty()) {
            return movieRepository.findRecommendationReadyPopularMovies(MIN_RECOMMENDATION_RATING, pageRequest);
        }

        return movieRepository.findRecommendationReadyPopularMoviesExcluding(
                MIN_RECOMMENDATION_RATING,
                new LinkedHashSet<>(excludedMovieIds),
                pageRequest
        );
    }

    private List<Movie> fetchTopRatedCandidates(Collection<Long> excludedMovieIds, int fetchSize) {
        PageRequest pageRequest = PageRequest.of(0, fetchSize);
        if (excludedMovieIds == null || excludedMovieIds.isEmpty()) {
            return movieRepository.findRecommendationReadyTopRatedMovies(MIN_RECOMMENDATION_RATING, pageRequest);
        }

        return movieRepository.findRecommendationReadyTopRatedMoviesExcluding(
                MIN_RECOMMENDATION_RATING,
                new LinkedHashSet<>(excludedMovieIds),
                pageRequest
        );
    }

    private List<Movie> fetchContentSimilarityCandidates(Collection<Long> excludedMovieIds) {
        if (excludedMovieIds == null || excludedMovieIds.isEmpty()) {
            return movieRepository.findRecommendationReadyMovies(MIN_RECOMMENDATION_RATING);
        }

        return movieRepository.findRecommendationReadyMoviesExcluding(
                MIN_RECOMMENDATION_RATING,
                new LinkedHashSet<>(excludedMovieIds)
        );
    }

    private ChannelCandidateBatch buildContentSimilarityBatch(
            RecommendationContext context,
            Set<Long> excludedMovieIds,
            int oversampleSize,
            int sampleSize
    ) {
        if (context.contentSimilaritySeeds().isEmpty()) {
            return new ChannelCandidateBatch(CONTENT_SIMILARITY_CHANNEL, List.of(), List.of());
        }

        List<PreparedCandidate> rankedContentCandidates = rankContentSimilarityCandidates(
                context.contentSimilaritySeeds(),
                fetchContentSimilarityCandidates(excludedMovieIds)
        );

        if (rankedContentCandidates.isEmpty()) {
            return new ChannelCandidateBatch(CONTENT_SIMILARITY_CHANNEL, List.of(), List.of());
        }

        List<PreparedCandidate> fetchedCandidates = rankedContentCandidates.stream()
                .limit(oversampleSize)
                .toList();

        return new ChannelCandidateBatch(
                CONTENT_SIMILARITY_CHANNEL,
                fetchedCandidates,
                sampleWeightedCandidates(CONTENT_SIMILARITY_CHANNEL, fetchedCandidates, sampleSize, context)
        );
    }

    private List<PreparedCandidate> sampleWeightedCandidates(
            String channelName,
            List<PreparedCandidate> sourceCandidates,
            int sampleSize,
            RecommendationContext context
    ) {
        if (sourceCandidates == null || sourceCandidates.isEmpty()) {
            return List.of();
        }

        if (sourceCandidates.size() <= sampleSize) {
            return List.copyOf(sourceCandidates);
        }

        List<PreparedCandidate> remaining = new ArrayList<>(sourceCandidates);
        List<PreparedCandidate> sampled = new ArrayList<>(sampleSize);
        Random random = new Random(buildChannelSeed(channelName, context.userId()));

        while (!remaining.isEmpty() && sampled.size() < sampleSize) {
            double totalWeight = 0.0;
            double[] weights = new double[remaining.size()];

            for (int index = 0; index < remaining.size(); index++) {
                double weight = samplingWeight(remaining.get(index));
                weights[index] = weight;
                totalWeight += weight;
            }

            double threshold = random.nextDouble() * totalWeight;
            double cumulativeWeight = 0.0;
            int selectedIndex = remaining.size() - 1;

            for (int index = 0; index < weights.length; index++) {
                cumulativeWeight += weights[index];
                if (threshold <= cumulativeWeight) {
                    selectedIndex = index;
                    break;
                }
            }

            sampled.add(remaining.remove(selectedIndex));
        }

        return sampled;
    }

    private long buildChannelSeed(String channelName, Long userId) {
        long seed = 17L;
        seed = (31L * seed) + LocalDate.now().toEpochDay();
        seed = (31L * seed) + Objects.requireNonNullElse(userId, -1L);
        seed = (31L * seed) + channelName.hashCode();
        return seed;
    }

    private double samplingWeight(PreparedCandidate candidate) {
        Movie movie = candidate.movie();
        double weightedScore = (QUALITY_SAMPLING_WEIGHT * qualityScore(movie.getMovieRating()))
                + (POPULARITY_SAMPLING_WEIGHT * popularityScore(movie.getPopularity()))
                + (FRESHNESS_SAMPLING_WEIGHT * freshnessScore(movie.getReleaseDate()))
                + candidate.contentSimilarityScore();
        return Math.max(MIN_SAMPLING_WEIGHT, weightedScore);
    }

    private List<PreparedCandidate> toPreparedCandidates(List<Movie> sourceMovies) {
        if (sourceMovies == null || sourceMovies.isEmpty()) {
            return List.of();
        }

        return sourceMovies.stream()
                .filter(Objects::nonNull)
                .map(movie -> new PreparedCandidate(movie, 0.0))
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
            Map<Long, List<String>> genresByMovieId
    ) {
        LinkedHashMap<Long, Movie> seedMovies = new LinkedHashMap<>();

        if (reviews != null) {
            reviews.stream()
                    .filter(review -> review.getRating() != null && review.getRating() >= POSITIVE_REVIEW_SEED_THRESHOLD)
                    .sorted(Comparator
                            .comparing(Review::getRating, Comparator.reverseOrder())
                            .thenComparing(Review::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .map(Review::getMovie)
                    .filter(this::hasUsableOverview)
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
                .filter(entry -> hasUsableOverview(entry.getMovie()))
                .sorted(Comparator
                        .comparingDouble((WatchList entry) -> watchlistSeedScore(entry.getMovie(), tasteProfile, genresByMovieId))
                        .reversed()
                        .thenComparing((WatchList entry) -> qualityScore(entry.getMovie().getMovieRating()), Comparator.reverseOrder())
                        .thenComparing(WatchList::getAddedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(entry -> {
                    if (seedMovies.size() < CONTENT_SIMILARITY_SEED_LIMIT) {
                        seedMovies.putIfAbsent(entry.getMovie().getId(), entry.getMovie());
                    }
                });

        return List.copyOf(seedMovies.values());
    }

    private double watchlistSeedScore(
            Movie movie,
            UserTasteProfile tasteProfile,
            Map<Long, List<String>> genresByMovieId
    ) {
        if (movie == null) {
            return 0.0;
        }

        List<String> genres = genresByMovieId.getOrDefault(movie.getId(), List.of());
        return genreAffinityScore(tasteProfile, genres) + qualityScore(movie.getMovieRating());
    }

    private List<PreparedCandidate> rankContentSimilarityCandidates(List<Movie> seedMovies, List<Movie> candidateMovies) {
        if (seedMovies == null || seedMovies.isEmpty() || candidateMovies == null || candidateMovies.isEmpty()) {
            return List.of();
        }

        Set<Long> seedMovieIds = seedMovies.stream()
                .map(Movie::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<Long, List<String>> tokensByMovieId = new LinkedHashMap<>();
        for (Movie seedMovie : seedMovies) {
            tokensByMovieId.put(seedMovie.getId(), tokenizeContent(seedMovie.getOverview()));
        }
        for (Movie candidateMovie : candidateMovies) {
            if (candidateMovie == null || candidateMovie.getId() == null || seedMovieIds.contains(candidateMovie.getId())) {
                continue;
            }

            List<String> tokens = tokenizeContent(candidateMovie.getOverview());
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
                .filter(candidate -> candidate.contentSimilarityScore() >= MIN_CONTENT_SIMILARITY_SCORE)
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

        return clamp01(dotProduct / (leftMagnitude * rightMagnitude));
    }

    private List<String> tokenizeContent(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(token -> token.length() >= 3)
                .filter(token -> !CONTENT_STOPWORDS.contains(token))
                .toList();
    }

    private boolean hasUsableOverview(Movie movie) {
        return movie != null
                && movie.getId() != null
                && movie.getOverview() != null
                && !movie.getOverview().isBlank()
                && isRecommendationReady(movie);
    }

    private void recordRecommendationImpressions(User user, List<CatalogRecommendation> recommendations) {
        if (user == null || recommendations == null || recommendations.isEmpty()) {
            return;
        }

        recommendationImpressionRepository.saveAll(recommendations.stream()
                .map(recommendation -> new RecommendationImpression(null, user, recommendation.movie(), null))
                .toList());
    }

    private List<String> resolveMoodGenres(Set<SoloMood> moods) {
        if (moods == null || moods.isEmpty() || (moods.size() == 1 && moods.contains(SoloMood.ANY))) {
            return List.of();
        }

        return moods.stream()
                .filter(mood -> mood != SoloMood.ANY)
                .map(SoloMood::preferredGenres)
                .flatMap(Set::stream)
                .distinct()
                .toList();
    }

    private <T> List<T> rerankForDiversity(
            List<T> rankedRecommendations,
            int limit,
            ToDoubleFunction<T> scoreExtractor,
            Function<T, List<String>> genresExtractor
    ) {
        if (rankedRecommendations == null || rankedRecommendations.isEmpty()) {
            return List.of();
        }

        if (rankedRecommendations.size() <= 1) {
            return rankedRecommendations.stream().limit(limit).toList();
        }

        List<T> remaining = new ArrayList<>(rankedRecommendations);
        List<T> selected = new ArrayList<>();

        while (!remaining.isEmpty() && selected.size() < limit) {
            T bestCandidate = null;
            double bestAdjustedScore = Double.NEGATIVE_INFINITY;

            for (T candidate : remaining) {
                double diversityPenalty = selected.stream()
                        .mapToDouble(existing -> genreSimilarity(genresExtractor.apply(existing), genresExtractor.apply(candidate)))
                        .max()
                        .orElse(0.0);

                double adjustedScore = scoreExtractor.applyAsDouble(candidate)
                        - (scoringProperties.getDiversityPenaltyWeight() * diversityPenalty);

                if (adjustedScore > bestAdjustedScore) {
                    bestAdjustedScore = adjustedScore;
                    bestCandidate = candidate;
                }
            }

            if (bestCandidate == null) {
                break;
            }

            selected.add(bestCandidate);
            remaining.remove(bestCandidate);
        }

        return selected;
    }

    private double genreSimilarity(List<String> leftGenres, List<String> rightGenres) {
        if (leftGenres == null || leftGenres.isEmpty() || rightGenres == null || rightGenres.isEmpty()) {
            return 0.0;
        }

        Set<String> left = leftGenres.stream()
                .map(this::normalize)
                .filter(genre -> !genre.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> right = rightGenres.stream()
                .map(this::normalize)
                .filter(genre -> !genre.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }

        Set<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);

        if (intersection.isEmpty()) {
            return 0.0;
        }

        return clamp01((double) intersection.size() / Math.min(left.size(), right.size()));
    }

    private WatchlistRecommendation scoreWatchlistEntry(
            WatchList entry,
            Set<SoloMood> moods,
            RuntimePreference runtimePreference,
            UserTasteProfile tasteProfile,
            Map<Long, List<String>> genresByMovieId
    ) {
        Movie movie = entry.getMovie();
        List<String> genres = genresByMovieId.getOrDefault(movie.getId(), List.of());
        RankingFeatures features = buildWatchlistFeatures(movie, entry, moods, runtimePreference, tasteProfile, genres);
        double score = computeWeightedScore(
                features,
                tasteProfile.hasSignals(),
                hasMoodIntent(moods),
                runtimePreference != RuntimePreference.ANY,
                false,
                true,
                false
        );
        List<String> reasons = buildWatchlistReasons(entry, movie, moods, runtimePreference, tasteProfile, genres, features);

        if (reasons.isEmpty()) {
            reasons.add("It is still one of the strongest unfinished options in your current watchlist.");
        }

        return new WatchlistRecommendation(entry, genres, score, dedupeReasons(reasons));
    }

    private CatalogRecommendation scoreCatalogMovie(
            CatalogCandidate candidate,
            Set<SoloMood> moods,
            RuntimePreference runtimePreference,
            RecommendationContext context,
            Map<Long, List<String>> genresByMovieId
    ) {
        Movie movie = candidate.movie();
        List<String> genres = genresByMovieId.getOrDefault(movie.getId(), List.of());
        WatchList watchlistEntry = context.watchlistByMovieId().get(movie.getId());
        RankingFeatures features = buildCatalogFeatures(
                movie,
                watchlistEntry,
                moods,
                runtimePreference,
                context.tasteProfile(),
                genres,
                candidate.sourceCount()
        );
        double score = computeWeightedScore(
                features,
                context.tasteProfile().hasSignals(),
                hasMoodIntent(moods),
                runtimePreference != RuntimePreference.ANY,
                true,
                false,
                true
        );
        if (context.penalizedMovieIds().contains(movie.getId())) {
            score = clamp01(score - IGNORED_RECOMMENDATION_PENALTY);
        }
        List<String> reasons = buildCatalogReasons(movie, moods, runtimePreference, context, genres, features, candidate);

        if (reasons.isEmpty()) {
            if (context.coldStart()) {
                reasons.add("It is a strong wider-catalog pick while AtlasWatch learns your taste.");
            } else {
                reasons.add("It fits the strongest combination of mood, runtime, and taste signals available right now.");
            }
        }

        return new CatalogRecommendation(
                movie,
                genres,
                score,
                watchlistEntry != null,
                normalizeWatchlistStatus(watchlistEntry),
                dedupeReasons(reasons)
        );
    }

    private RankingFeatures buildWatchlistFeatures(
            Movie movie,
            WatchList entry,
            Set<SoloMood> moods,
            RuntimePreference runtimePreference,
            UserTasteProfile tasteProfile,
            List<String> genres
    ) {
        return new RankingFeatures(
                genreAffinityScore(tasteProfile, genres),
                dislikedGenrePenaltyScore(tasteProfile, genres),
                moodMatchScore(moods, genres),
                runtimeMatchScore(runtimePreference, movie.getRuntime()),
                qualityScore(movie.getMovieRating()),
                popularityScore(movie.getPopularity()),
                0.0,
                freshnessScore(movie.getReleaseDate()),
                watchlistAgeScore(entry),
                0.0
        );
    }

    private RankingFeatures buildCatalogFeatures(
            Movie movie,
            WatchList watchlistEntry,
            Set<SoloMood> moods,
            RuntimePreference runtimePreference,
            UserTasteProfile tasteProfile,
            List<String> genres,
            int sourceCount
    ) {
        return new RankingFeatures(
                genreAffinityScore(tasteProfile, genres),
                dislikedGenrePenaltyScore(tasteProfile, genres),
                moodMatchScore(moods, genres),
                runtimeMatchScore(runtimePreference, movie.getRuntime()),
                qualityScore(movie.getMovieRating()),
                popularityScore(movie.getPopularity()),
                watchlistEntry != null ? 1.0 : 0.0,
                freshnessScore(movie.getReleaseDate()),
                0.0,
                sourceCountScore(sourceCount)
        );
    }

    private double computeWeightedScore(
            RankingFeatures features,
            boolean includeTaste,
            boolean includeMood,
            boolean includeRuntime,
            boolean includeWatchlistBoost,
            boolean includeWatchlistAge,
            boolean includeSourceCount
    ) {
        double weightedPositive = 0.0;
        double positiveWeightTotal = 0.0;

        if (includeTaste) {
            weightedPositive += scoringProperties.getGenreAffinityWeight() * features.genreAffinity();
            positiveWeightTotal += scoringProperties.getGenreAffinityWeight();
        }
        if (includeMood) {
            weightedPositive += scoringProperties.getMoodMatchWeight() * features.moodMatch();
            positiveWeightTotal += scoringProperties.getMoodMatchWeight();
        }
        if (includeRuntime) {
            weightedPositive += scoringProperties.getRuntimeMatchWeight() * features.runtimeMatch();
            positiveWeightTotal += scoringProperties.getRuntimeMatchWeight();
        }
        if (includeWatchlistBoost) {
            weightedPositive += scoringProperties.getWatchlistBoostWeight() * features.watchlistBoost();
            positiveWeightTotal += scoringProperties.getWatchlistBoostWeight();
        }
        if (includeWatchlistAge) {
            weightedPositive += scoringProperties.getWatchlistAgeWeight() * features.watchlistAge();
            positiveWeightTotal += scoringProperties.getWatchlistAgeWeight();
        }
        if (includeSourceCount) {
            weightedPositive += scoringProperties.getSourceCountWeight() * features.sourceCount();
            positiveWeightTotal += scoringProperties.getSourceCountWeight();
        }

        weightedPositive += scoringProperties.getQualityWeight() * features.quality();
        weightedPositive += scoringProperties.getPopularityWeight() * features.popularity();
        weightedPositive += scoringProperties.getFreshnessWeight() * features.freshness();

        positiveWeightTotal += scoringProperties.getQualityWeight();
        positiveWeightTotal += scoringProperties.getPopularityWeight();
        positiveWeightTotal += scoringProperties.getFreshnessWeight();

        if (positiveWeightTotal <= 0) {
            return 0.0;
        }

        double normalizedPositive = weightedPositive / positiveWeightTotal;
        double penalizedScore = normalizedPositive - (scoringProperties.getDislikedGenrePenaltyWeight() * features.dislikedGenrePenalty());
        return clamp01(penalizedScore);
    }

    private List<String> buildWatchlistReasons(
            WatchList entry,
            Movie movie,
            Set<SoloMood> moods,
            RuntimePreference runtimePreference,
            UserTasteProfile tasteProfile,
            List<String> genres,
            RankingFeatures features
    ) {
        List<String> reasons = new ArrayList<>();

        addWatchlistAgeReason(entry, features.watchlistAge(), reasons);
        addMoodReason(moods, genres, features.moodMatch(), reasons);
        addRuntimeReason(runtimePreference, features.runtimeMatch(), reasons);
        addTasteReason(tasteProfile, genres, features, reasons);
        addQualityReason(movie, false, features.quality(), reasons);
        addPopularityReason(movie, false, features.popularity(), reasons);
        addFreshnessReason(movie, features.freshness(), reasons);

        return dedupeReasons(reasons);
    }

    private List<String> buildCatalogReasons(
            Movie movie,
            Set<SoloMood> moods,
            RuntimePreference runtimePreference,
            RecommendationContext context,
            List<String> genres,
            RankingFeatures features,
            CatalogCandidate candidate
    ) {
        List<String> reasons = new ArrayList<>();

        if (features.watchlistBoost() > 0) {
            reasons.add("It is already on your watchlist, so this lines up with something you were already curious about.");
        }
        addMoodReason(moods, genres, features.moodMatch(), reasons);
        addRuntimeReason(runtimePreference, features.runtimeMatch(), reasons);
        addTasteReason(context.tasteProfile(), genres, features, reasons);
        addSourceCountReason(candidate.sourceCount(), reasons);
        addContentSimilarityReason(candidate, context, reasons);
        addQualityReason(movie, context.coldStart(), features.quality(), reasons);
        addPopularityReason(movie, context.coldStart(), features.popularity(), reasons);
        addFreshnessReason(movie, features.freshness(), reasons);

        return dedupeReasons(reasons);
    }

    private double sourceCountScore(int sourceCount) {
        if (sourceCount <= 1) {
            return 0.0;
        }

        return clamp01((sourceCount - 1.0) / 4.0);
    }

    private double genreAffinityScore(UserTasteProfile tasteProfile, List<String> genres) {
        if (tasteProfile == null || !tasteProfile.hasSignals() || genres.isEmpty()) {
            return 0.0;
        }

        List<Double> positiveSignals = genres.stream()
                .map(this::normalize)
                .distinct()
                .map(genre -> Math.max(0.0, tasteProfile.netWeight(genre)))
                .filter(weight -> weight > 0.0)
                .toList();

        if (positiveSignals.isEmpty()) {
            return 0.0;
        }

        return clamp01(positiveSignals.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
    }

    private double dislikedGenrePenaltyScore(UserTasteProfile tasteProfile, List<String> genres) {
        if (tasteProfile == null || !tasteProfile.hasSignals() || genres.isEmpty()) {
            return 0.0;
        }

        List<Double> penaltySignals = genres.stream()
                .map(this::normalize)
                .distinct()
                .map(genre -> Math.max(
                        tasteProfile.negativeWeight(genre),
                        Math.abs(Math.min(0.0, tasteProfile.netWeight(genre)))
                ))
                .filter(weight -> weight >= scoringProperties.getStrongDislikeThreshold())
                .toList();

        if (penaltySignals.isEmpty()) {
            return 0.0;
        }

        return clamp01(penaltySignals.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
    }

    private double moodMatchScore(Set<SoloMood> moods, List<String> genres) {
        if (!hasMoodIntent(moods) || genres.isEmpty()) {
            return 0.0;
        }

        Set<String> preferredGenres = moods.stream()
                .filter(mood -> mood != SoloMood.ANY)
                .map(SoloMood::preferredGenres)
                .flatMap(Set::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (preferredGenres.isEmpty()) {
            return 0.0;
        }

        long matches = genres.stream()
                .map(this::normalize)
                .distinct()
                .filter(preferredGenres::contains)
                .count();

        return clamp01((double) matches / preferredGenres.size());
    }

    private double runtimeMatchScore(RuntimePreference runtimePreference, Integer runtime) {
        return runtimePreference.score(runtime);
    }

    private double qualityScore(Double movieRating) {
        if (movieRating == null) {
            return 0.0;
        }

        return clamp01((movieRating - MIN_RECOMMENDATION_RATING) / (10.0 - MIN_RECOMMENDATION_RATING));
    }

    private double popularityScore(Double popularity) {
        if (popularity == null || popularity <= 0) {
            return 0.0;
        }

        return clamp01(popularity / scoringProperties.getPopularitySaturation());
    }

    private double freshnessScore(LocalDate releaseDate) {
        if (releaseDate == null) {
            return 0.0;
        }

        long daysOld = Math.max(0, ChronoUnit.DAYS.between(releaseDate, LocalDate.now()));
        double yearsOld = daysOld / 365.25;
        return clamp01(1.0 - (yearsOld / scoringProperties.getFreshnessWindowYears()));
    }

    private double watchlistAgeScore(WatchList entry) {
        if (entry == null || entry.getAddedAt() == null) {
            return 0.0;
        }

        long daysOnWatchlist = Math.max(0, Duration.between(entry.getAddedAt(), LocalDateTime.now()).toDays());
        return clamp01((double) daysOnWatchlist / scoringProperties.getWatchlistAgeSaturationDays());
    }

    private void addWatchlistAgeReason(WatchList entry, double watchlistAgeScore, List<String> reasons) {
        if (entry == null || entry.getAddedAt() == null || watchlistAgeScore <= 0.0) {
            return;
        }

        long days = Math.max(0, Duration.between(entry.getAddedAt(), LocalDateTime.now()).toDays());
        if (days >= 180) {
            reasons.add("It has been sitting in your watchlist for a while, so this is a good time to finally watch it.");
        } else if (days >= 60) {
            reasons.add("It has been on your watchlist long enough to deserve a bump.");
        }
    }

    private void addMoodReason(Set<SoloMood> moods, List<String> genres, double moodMatchScore, List<String> reasons) {
        if (moodMatchScore <= 0.0 || !hasMoodIntent(moods)) {
            return;
        }

        List<String> matches = matchingMoodGenres(moods, genres);
        if (matches.isEmpty()) {
            return;
        }

        List<String> moodLabels = moods.stream()
                .filter(mood -> mood != SoloMood.ANY)
                .map(SoloMood::displayLabel)
                .toList();

        reasons.add("It matches your " + humanizeLabels(moodLabels) + " vibe mix through " + humanizeGenres(matches) + ".");
    }

    private void addRuntimeReason(RuntimePreference runtimePreference, double runtimeMatchScore, List<String> reasons) {
        if (runtimePreference == RuntimePreference.ANY || runtimeMatchScore < 0.95) {
            return;
        }

        reasons.add("Its runtime fits your " + runtimePreference.label + " preference.");
    }

    private void addTasteReason(
            UserTasteProfile tasteProfile,
            List<String> genres,
            RankingFeatures features,
            List<String> reasons
    ) {
        if (tasteProfile == null
                || !tasteProfile.hasSignals()
                || features.genreAffinity() < scoringProperties.getPositiveGenreReasonThreshold()
                || features.genreAffinity() < features.dislikedGenrePenalty()) {
            return;
        }

        List<String> positiveMatches = genres.stream()
                .map(this::normalize)
                .distinct()
                .filter(genre -> tasteProfile.netWeight(genre) >= scoringProperties.getPositiveGenreReasonThreshold())
                .toList();

        if (!positiveMatches.isEmpty()) {
            reasons.add("It lines up with genres you tend to rate highly, like " + humanizeGenres(positiveMatches) + ".");
        }
    }

    private void addQualityReason(Movie movie, boolean coldStart, double qualityScore, List<String> reasons) {
        if (qualityScore < 0.55 || movie.getMovieRating() == null) {
            return;
        }

        reasons.add(coldStart
                ? "It has one of the stronger audience ratings in the wider catalog."
                : "It also stands out as one of the stronger-rated matches here.");
    }

    private void addPopularityReason(Movie movie, boolean coldStart, double popularityScore, List<String> reasons) {
        if (popularityScore < 0.55 || movie.getPopularity() == null) {
            return;
        }

        reasons.add(coldStart
                ? "It is also one of the more popular catalog options right now."
                : "It has enough popularity to make it a safer all-around pick.");
    }

    private void addFreshnessReason(Movie movie, double freshnessScore, List<String> reasons) {
        if (freshnessScore < 0.60 || movie.getReleaseDate() == null) {
            return;
        }

        reasons.add("It is also a relatively recent release, which can help when you want something fresher.");
    }

    private void addSourceCountReason(int sourceCount, List<String> reasons) {
        if (sourceCount < 2) {
            return;
        }

        reasons.add(sourceCount >= 3
                ? "Multiple recommendation signals all surfaced this, so it kept winning across the pool."
                : "More than one recommendation signal pointed to this, which makes it a sturdier pick.");
    }

    private void addContentSimilarityReason(
            CatalogCandidate candidate,
            RecommendationContext context,
            List<String> reasons
    ) {
        if (candidate == null
                || candidate.contentSimilarityScore() < MIN_CONTENT_SIMILARITY_SCORE
                || context == null
                || context.contentSimilaritySeeds().isEmpty()) {
            return;
        }

        List<String> seedTitles = context.contentSimilaritySeeds().stream()
                .map(Movie::getMovieTitle)
                .filter(Objects::nonNull)
                .filter(title -> !title.isBlank())
                .limit(2)
                .toList();

        if (seedTitles.isEmpty()) {
            reasons.add("Its plot and overall premise are close to movies you have already responded well to.");
            return;
        }

        reasons.add("Its plot and overall premise are close to " + humanizeLabels(seedTitles) + ".");
    }

    private List<String> matchingMoodGenres(Set<SoloMood> moods, List<String> genres) {
        if (!hasMoodIntent(moods) || genres.isEmpty()) {
            return List.of();
        }

        Set<String> preferredGenres = moods.stream()
                .filter(mood -> mood != SoloMood.ANY)
                .map(SoloMood::preferredGenres)
                .flatMap(Set::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return genres.stream()
                .map(this::normalize)
                .filter(preferredGenres::contains)
                .distinct()
                .toList();
    }

    private boolean hasMoodIntent(Set<SoloMood> moods) {
        return moods != null && !(moods.size() == 1 && moods.contains(SoloMood.ANY)) && !moods.isEmpty();
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
                .runtime(movie.getRuntime())
                .popularity(movie.getPopularity())
                .genres(recommendation.genres())
                .watchlistStatus(normalizeWatchlistStatus(entry))
                .addedAt(entry.getAddedAt())
                .score(toDisplayScore(recommendation.score()))
                .reasons(recommendation.reasons())
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
                .runtime(movie.getRuntime())
                .popularity(movie.getPopularity())
                .genres(recommendation.genres())
                .onWatchlist(recommendation.onWatchlist())
                .watchlistStatus(recommendation.watchlistStatus())
                .reasons(recommendation.reasons())
                .build();
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

    private List<String> dedupeReasons(List<String> reasons) {
        return new ArrayList<>(new LinkedHashSet<>(reasons)).stream()
                .limit(3)
                .toList();
    }

    private boolean isRecommendationReady(Movie movie) {
        return movie != null
                && movie.getId() != null
                && movie.getMovieRating() != null
                && movie.getMovieRating() >= MIN_RECOMMENDATION_RATING
                && movie.getRuntime() != null
                && movie.getRuntime() >= MIN_RECOMMENDATION_RUNTIME
                && movie.getReleaseDate() != null
                && movie.getPosterPath() != null
                && !movie.getPosterPath().isBlank()
                && movie.getOverview() != null
                && !movie.getOverview().isBlank();
    }

    private boolean passesScoringHardFilters(Movie movie, RuntimePreference runtimePreference) {
        if (movie == null || movie.getId() == null) {
            return false;
        }

        if (movie.getMovieRating() != null && movie.getMovieRating() < MIN_RECOMMENDATION_RATING) {
            return false;
        }

        if (runtimePreference != RuntimePreference.ANY && !runtimePreference.passesHardFilter(movie.getRuntime())) {
            return false;
        }

        return true;
    }

    private boolean isRetrievableCandidate(Movie movie, Set<Long> watchedMovieIds) {
        return isRecommendationReady(movie)
                && (watchedMovieIds == null || !watchedMovieIds.contains(movie.getId()));
    }

    private void seedCatalogIfNeeded() {
        try {
            catalogIngestionService.ensureCatalogSeeded();
        } catch (RuntimeException ignored) {
            // If TMDB seeding fails, we still rank whatever is already cached locally.
        }
    }

    private String humanizeGenres(List<String> genres) {
        return genres.stream()
                .map(genre -> genre.substring(0, 1).toUpperCase(Locale.ROOT) + genre.substring(1))
                .collect(Collectors.joining(", "));
    }

    private String humanizeLabels(List<String> labels) {
        return String.join(", ", labels);
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

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private int toDisplayScore(double weightedScore) {
        return (int) Math.round(clamp01(weightedScore) * 100.0);
    }

    private double clamp01(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private record WatchlistRecommendation(
            WatchList entry,
            List<String> genres,
            double score,
            List<String> reasons
    ) {
        private Movie movie() {
            return entry.getMovie();
        }
    }

    private record CatalogRecommendation(
            Movie movie,
            List<String> genres,
            double score,
            boolean onWatchlist,
            String watchlistStatus,
            List<String> reasons
    ) {
    }

    private record RecommendationContext(
            Long userId,
            Map<Long, WatchList> watchlistByMovieId,
            Set<Long> watchedMovieIds,
            Set<Long> penalizedMovieIds,
            Set<Long> suppressedMovieIds,
            UserTasteProfile tasteProfile,
            List<Movie> contentSimilaritySeeds,
            boolean coldStart,
            boolean authenticated
    ) {
        private static RecommendationContext createColdStart() {
            return new RecommendationContext(
                    null,
                    Map.of(),
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    UserTasteProfile.empty(),
                    List.of(),
                    true,
                    false
            );
        }
    }

    private record ChannelRetrievalStats(
            String channelName,
            int fetchedCount,
            int sampledCount,
            int eligibleCount,
            int uniqueAddedCount,
            int overlapDroppedCount
    ) {
    }

    private record RankingFeatures(
            double genreAffinity,
            double dislikedGenrePenalty,
            double moodMatch,
            double runtimeMatch,
            double quality,
            double popularity,
            double watchlistBoost,
            double freshness,
            double watchlistAge,
            double sourceCount
    ) {
    }

    private record CatalogCandidate(
            Movie movie,
            List<String> sourceChannels,
            double contentSimilarityScore
    ) {
        private int sourceCount() {
            return sourceChannels == null ? 0 : sourceChannels.size();
        }
    }

    private record PreparedCandidate(
            Movie movie,
            double contentSimilarityScore
    ) {
    }

    private record ChannelCandidateBatch(
            String channelName,
            List<PreparedCandidate> fetchedCandidates,
            List<PreparedCandidate> sampledCandidates
    ) {
    }

    private static final class CandidateAccumulator {
        private final Movie movie;
        private final LinkedHashSet<String> sourceChannels;
        private double contentSimilarityScore;

        private CandidateAccumulator(Movie movie, LinkedHashSet<String> sourceChannels, double contentSimilarityScore) {
            this.movie = movie;
            this.sourceChannels = sourceChannels;
            this.contentSimilarityScore = contentSimilarityScore;
        }

        private static CandidateAccumulator from(String channelName, PreparedCandidate candidate) {
            LinkedHashSet<String> sourceChannels = new LinkedHashSet<>();
            sourceChannels.add(channelName);
            return new CandidateAccumulator(candidate.movie(), sourceChannels, candidate.contentSimilarityScore());
        }

        private void addSourceChannel(String channelName, double additionalContentSimilarityScore) {
            sourceChannels.add(channelName);
            contentSimilarityScore = Math.max(contentSimilarityScore, additionalContentSimilarityScore);
        }

        private CatalogCandidate toCatalogCandidate() {
            return new CatalogCandidate(movie, List.copyOf(sourceChannels), contentSimilarityScore);
        }
    }

    private enum SoloMood {
        ANY("any", Set.of()),
        COMFORTING("comforting", Set.of("comedy", "family", "romance", "animation")),
        FUNNY("funny", Set.of("comedy", "animation")),
        TENSE("tense", Set.of("thriller", "mystery", "crime", "action")),
        DARK("dark", Set.of("thriller", "horror", "crime", "drama")),
        EMOTIONAL("emotional", Set.of("drama", "romance")),
        THOUGHTFUL("thoughtful", Set.of("drama", "science fiction", "history")),
        ADVENTUROUS("adventurous", Set.of("adventure", "fantasy", "action", "science fiction")),
        COZY("cozy", Set.of("family", "romance", "comedy", "animation")),
        ROMANTIC("romantic", Set.of("romance", "drama", "comedy")),
        EERIE("eerie", Set.of("horror", "mystery", "thriller")),
        HOPEFUL("hopeful", Set.of("family", "adventure", "drama", "animation")),
        BITTERSWEET("bittersweet", Set.of("drama", "romance", "music")),
        MIND_BENDING("mind bending", Set.of("science fiction", "mystery", "thriller")),
        INSPIRING("inspiring", Set.of("history", "drama", "music", "adventure"));

        private static final Map<String, SoloMood> LOOKUP = new HashMap<>();

        static {
            for (SoloMood mood : values()) {
                LOOKUP.put(mood.label, mood);
            }
        }

        private final String label;
        private final Set<String> preferredGenres;

        SoloMood(String label, Set<String> preferredGenres) {
            this.label = label;
            this.preferredGenres = preferredGenres;
        }

        private String displayLabel() {
            return label.substring(0, 1).toUpperCase(Locale.ROOT) + label.substring(1);
        }

        private Set<String> preferredGenres() {
            return preferredGenres;
        }

        private static Set<SoloMood> from(List<String> values, String fallbackValue) {
            List<String> rawValues = values == null || values.isEmpty()
                    ? (fallbackValue == null || fallbackValue.isBlank() ? List.of("any") : List.of(fallbackValue))
                    : values;

            Set<SoloMood> resolved = rawValues.stream()
                    .map(SoloMood::fromSingle)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            if (resolved.isEmpty()) {
                return Set.of(ANY);
            }

            if (resolved.size() > 1) {
                resolved.remove(ANY);
            }

            return resolved.isEmpty() ? Set.of(ANY) : resolved;
        }

        private static SoloMood fromSingle(String value) {
            if (value == null || value.isBlank()) {
                return ANY;
            }

            String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
            SoloMood mood = LOOKUP.get(normalized);
            if (mood == null) {
                throw new IllegalArgumentException("Invalid mood: '" + value + "'.");
            }
            return mood;
        }
    }

    private enum RuntimePreference {
        ANY("any", runtime -> true),
        SHORT("short", runtime -> runtime <= 105),
        MEDIUM("medium", runtime -> runtime > 105 && runtime <= 135),
        LONG("long", runtime -> runtime > 135);

        private static final Map<String, RuntimePreference> LOOKUP = new HashMap<>();

        static {
            for (RuntimePreference preference : values()) {
                LOOKUP.put(preference.label, preference);
            }
        }

        private final String label;
        private final Predicate<Integer> matcher;

        RuntimePreference(String label, Predicate<Integer> matcher) {
            this.label = label;
            this.matcher = matcher;
        }

        private boolean matches(int runtime) {
            return matcher.test(runtime);
        }

        private boolean passesHardFilter(Integer runtime) {
            if (runtime == null) {
                return false;
            }

            return switch (this) {
                case ANY -> true;
                case SHORT -> runtime <= 125;
                case MEDIUM -> runtime >= 90 && runtime <= 150;
                case LONG -> runtime >= 120;
            };
        }

        private double score(Integer runtime) {
            if (runtime == null || this == ANY) {
                return 0.0;
            }

            if (matches(runtime)) {
                return 1.0;
            }

            if (!passesHardFilter(runtime)) {
                return 0.0;
            }

            return 0.4;
        }

        private static RuntimePreference from(String value) {
            if (value == null || value.isBlank()) {
                return ANY;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
            RuntimePreference preference = LOOKUP.get(normalized);
            if (preference == null) {
                throw new IllegalArgumentException(
                        "Invalid runtimePreference: '" + value + "'. Must be any, short, medium, or long."
                );
            }
            return preference;
        }
    }
}
