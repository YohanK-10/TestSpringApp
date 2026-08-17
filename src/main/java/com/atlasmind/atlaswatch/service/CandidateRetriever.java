package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.models.Movie;
import com.atlasmind.atlaswatch.models.WatchList;
import com.atlasmind.atlaswatch.repository.MovieGenreRepository;
import com.atlasmind.atlaswatch.repository.MovieKeywordRepository;
import com.atlasmind.atlaswatch.repository.MovieRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
class CandidateRetriever {

    private final MovieGenreRepository movieGenreRepository;
    private final MovieKeywordRepository movieKeywordRepository;
    private final MovieRepository movieRepository;
    private final RecommendationScorer recommendationScorer;
    private final ContentSimilarityService contentSimilarityService;

    private static final int MIN_CANDIDATE_POOL = 80;
    private static final int MAX_CANDIDATE_POOL = 250;
    private static final int MAX_TOP_GENRES = 4;
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
    private static final String CONTENT_SIMILARITY_CHANNEL = "content-similarity";
    private static final String COLLABORATIVE_CHANNEL = "collaborative";

    List<CatalogCandidate> retrieveCandidates(RecommendationContext context, Set<SoloMood> moods, int limit) {
        return retrieveCandidatesWithStats(context, moods, limit).candidates();
    }

    CandidateRetrievalResult retrieveCandidatesWithStats(
            RecommendationContext context,
            Set<SoloMood> moods,
            int limit
    ) {
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

        ChannelRetrievalStats collaborativeStats = appendChannel(
                buildCollaborativeBatch(context),
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
                "Recommendation candidate retrieval -> watchlist[fetched={}, sampled={}, eligible={}, added={}, overlapDropped={}], genreAffinity[fetched={}, sampled={}, eligible={}, added={}, overlapDropped={}], moodAligned[fetched={}, sampled={}, eligible={}, added={}, overlapDropped={}], collaborative[fetched={}, sampled={}, eligible={}, added={}, overlapDropped={}], popular[fetched={}, sampled={}, eligible={}, added={}, overlapDropped={}], highRated[fetched={}, sampled={}, eligible={}, added={}, overlapDropped={}], contentSimilarity[fetched={}, sampled={}, eligible={}, added={}, overlapDropped={}], merged={}, penalized={}, suppressed={}, anonymous={}, coldStart={}",
                watchlistStats.fetchedCount(), watchlistStats.sampledCount(), watchlistStats.eligibleCount(), watchlistStats.uniqueAddedCount(), watchlistStats.overlapDroppedCount(),
                genreAffinityStats.fetchedCount(), genreAffinityStats.sampledCount(), genreAffinityStats.eligibleCount(), genreAffinityStats.uniqueAddedCount(), genreAffinityStats.overlapDroppedCount(),
                moodStats.fetchedCount(), moodStats.sampledCount(), moodStats.eligibleCount(), moodStats.uniqueAddedCount(), moodStats.overlapDroppedCount(),
                collaborativeStats.fetchedCount(), collaborativeStats.sampledCount(), collaborativeStats.eligibleCount(), collaborativeStats.uniqueAddedCount(), collaborativeStats.overlapDroppedCount(),
                popularStats.fetchedCount(), popularStats.sampledCount(), popularStats.eligibleCount(), popularStats.uniqueAddedCount(), popularStats.overlapDroppedCount(),
                highRatedStats.fetchedCount(), highRatedStats.sampledCount(), highRatedStats.eligibleCount(), highRatedStats.uniqueAddedCount(), highRatedStats.overlapDroppedCount(),
                contentSimilarityStats.fetchedCount(), contentSimilarityStats.sampledCount(), contentSimilarityStats.eligibleCount(), contentSimilarityStats.uniqueAddedCount(), contentSimilarityStats.overlapDroppedCount(),
                mergedCandidates.size(),
                context.penalizedMovieIds().size(),
                context.suppressedMovieIds().size(),
                !context.authenticated(),
                context.coldStart()
        );

        List<CatalogCandidate> candidates = mergedCandidates.values().stream()
                .limit(totalCandidateLimit)
                .map(CandidateAccumulator::toCatalogCandidate)
                .toList();
        Map<String, ChannelRetrievalStats> channelStats = List.of(
                        watchlistStats,
                        genreAffinityStats,
                        moodStats,
                        collaborativeStats,
                        popularStats,
                        highRatedStats,
                        contentSimilarityStats
                ).stream()
                .collect(Collectors.toMap(
                        ChannelRetrievalStats::channelName,
                        stats -> stats,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        return new CandidateRetrievalResult(candidates, channelStats, mergedCandidates.size());
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
            if (!recommendationScorer.isRetrievableCandidate(movie, hardExcludedMovieIds)) {
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
                    RecommendationScorer.MIN_RECOMMENDATION_RATING,
                    pageRequest
            );
        }

        return movieGenreRepository.findDistinctRecommendationReadyMoviesByGenreNamesExcluding(
                genreNames,
                RecommendationScorer.MIN_RECOMMENDATION_RATING,
                new LinkedHashSet<>(excludedMovieIds),
                pageRequest
        );
    }

    private List<Movie> fetchPopularCandidates(Collection<Long> excludedMovieIds, int fetchSize) {
        PageRequest pageRequest = PageRequest.of(0, fetchSize);
        if (excludedMovieIds == null || excludedMovieIds.isEmpty()) {
            return movieRepository.findRecommendationReadyPopularMovies(RecommendationScorer.MIN_RECOMMENDATION_RATING, pageRequest);
        }

        return movieRepository.findRecommendationReadyPopularMoviesExcluding(
                RecommendationScorer.MIN_RECOMMENDATION_RATING,
                new LinkedHashSet<>(excludedMovieIds),
                pageRequest
        );
    }

    private List<Movie> fetchTopRatedCandidates(Collection<Long> excludedMovieIds, int fetchSize) {
        PageRequest pageRequest = PageRequest.of(0, fetchSize);
        if (excludedMovieIds == null || excludedMovieIds.isEmpty()) {
            return movieRepository.findRecommendationReadyTopRatedMovies(
                    RecommendationScorer.MIN_RECOMMENDATION_RATING,
                    recommendationScorer.qualityPriorMean(),
                    recommendationScorer.qualityPriorWeight(),
                    pageRequest
            );
        }

        return movieRepository.findRecommendationReadyTopRatedMoviesExcluding(
                RecommendationScorer.MIN_RECOMMENDATION_RATING,
                recommendationScorer.qualityPriorMean(),
                recommendationScorer.qualityPriorWeight(),
                new LinkedHashSet<>(excludedMovieIds),
                pageRequest
        );
    }

    private List<Movie> fetchContentSimilarityCandidates(Collection<Long> excludedMovieIds) {
        if (excludedMovieIds == null || excludedMovieIds.isEmpty()) {
            return movieRepository.findRecommendationReadyMovies(RecommendationScorer.MIN_RECOMMENDATION_RATING);
        }

        return movieRepository.findRecommendationReadyMoviesExcluding(
                RecommendationScorer.MIN_RECOMMENDATION_RATING,
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

        List<Movie> sourceMovies = fetchContentSimilarityCandidates(excludedMovieIds);
        Map<Long, List<String>> keywordsByMovieId = buildKeywordsByMovieId(sourceMovies, context.contentSimilaritySeeds());
        List<PreparedCandidate> rankedContentCandidates = contentSimilarityService.rankCandidates(
                context.contentSimilaritySeeds(),
                sourceMovies,
                keywordsByMovieId
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

    private ChannelCandidateBatch buildCollaborativeBatch(RecommendationContext context) {
        Map<Integer, Double> scores = context.collaborativeScoresByTmdbId();
        if (scores == null || scores.isEmpty()) {
            return new ChannelCandidateBatch(COLLABORATIVE_CHANNEL, List.of(), List.of());
        }
        List<Integer> rankedIds = scores.keySet().stream().limit(MAX_CHANNEL_POOL_SIZE).toList();
        Map<Integer, Movie> moviesByTmdbId = movieRepository.findByTmdbIdIn(rankedIds).stream()
                .filter(movie -> movie.getTmdbId() != null)
                .collect(Collectors.toMap(Movie::getTmdbId, movie -> movie, (left, right) -> left));
        List<PreparedCandidate> candidates = rankedIds.stream()
                .map(moviesByTmdbId::get)
                .filter(Objects::nonNull)
                .map(movie -> new PreparedCandidate(movie, 0.0))
                .toList();
        return new ChannelCandidateBatch(COLLABORATIVE_CHANNEL, candidates, candidates);
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
        Random random = new Random(buildChannelSeed(channelName, context.userId(), context.rotationKey()));

        while (!remaining.isEmpty() && sampled.size() < sampleSize) {
            double totalWeight = 0.0;
            double[] weights = new double[remaining.size()];

            for (int index = 0; index < remaining.size(); index++) {
                double weight = recommendationScorer.samplingWeight(remaining.get(index));
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

    private Map<Long, List<String>> buildKeywordsByMovieId(Collection<Movie> candidateMovies, Collection<Movie> seedMovies) {
        Set<Long> movieIds = new LinkedHashSet<>();
        if (candidateMovies != null) {
            candidateMovies.stream()
                    .map(Movie::getId)
                    .filter(Objects::nonNull)
                    .forEach(movieIds::add);
        }
        if (seedMovies != null) {
            seedMovies.stream()
                    .map(Movie::getId)
                    .filter(Objects::nonNull)
                    .forEach(movieIds::add);
        }
        if (movieIds.isEmpty()) {
            return Map.of();
        }

        return movieKeywordRepository.findByMovieIdInWithKeyword(movieIds).stream()
                .collect(Collectors.groupingBy(
                        movieKeyword -> movieKeyword.getMovie().getId(),
                        Collectors.mapping(movieKeyword -> movieKeyword.getKeyword().getName(), Collectors.toList())
                ));
    }

    private long buildChannelSeed(String channelName, Long userId, String rotationKey) {
        long seed = 17L;
        seed = (31L * seed) + (hasText(rotationKey) ? rotationKey.hashCode() : LocalDate.now().toEpochDay());
        seed = (31L * seed) + Objects.requireNonNullElse(userId, -1L);
        seed = (31L * seed) + channelName.hashCode();
        return seed;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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
}
