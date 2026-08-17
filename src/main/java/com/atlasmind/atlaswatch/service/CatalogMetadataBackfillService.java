package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.dto.tmdb.MovieDetailDto;
import com.atlasmind.atlaswatch.models.Movie;
import com.atlasmind.atlaswatch.repository.MovieRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** Resumable enrichment of recommendation-ready movies through TMDB details. */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogMetadataBackfillService {

    private final MovieRepository movieRepository;
    private final TmdbApiService tmdbApiService;
    private final MovieService movieService;
    private final RecommendationCacheInvalidationService cacheInvalidationService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public BackfillReport backfill(int batchSize, int maxItems, long rateLimitMs) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("A catalog metadata backfill is already running.");
        }

        int normalizedBatchSize = Math.max(1, Math.min(batchSize, 500));
        int normalizedMaxItems = maxItems <= 0 ? Integer.MAX_VALUE : maxItems;
        long total = movieRepository.countRecommendationReadyMovies(
                RecommendationScorer.MIN_RECOMMENDATION_RATING
        );
        long syncedBefore = movieRepository.countRecommendationReadyMoviesWithSemanticMetadata(
                RecommendationScorer.MIN_RECOMMENDATION_RATING
        );
        int attempted = 0;
        int succeeded = 0;
        int failed = 0;
        Set<Long> deferredFailureIds = new LinkedHashSet<>();

        try {
            log.info(
                    "Starting semantic metadata backfill: recommendationReady={}, syncedBefore={}, batchSize={}, maxItems={}",
                    total, syncedBefore, normalizedBatchSize,
                    normalizedMaxItems == Integer.MAX_VALUE ? "all" : normalizedMaxItems
            );
            while (attempted < normalizedMaxItems) {
                int fetchSize = Math.min(normalizedBatchSize, normalizedMaxItems - attempted);
                PageRequest firstPage = PageRequest.of(0, fetchSize);
                List<Movie> movies = deferredFailureIds.isEmpty()
                        ? movieRepository.findRecommendationReadyMoviesMissingSemanticMetadata(
                                RecommendationScorer.MIN_RECOMMENDATION_RATING,
                                firstPage
                        )
                        : movieRepository.findRecommendationReadyMoviesMissingSemanticMetadataExcluding(
                                RecommendationScorer.MIN_RECOMMENDATION_RATING,
                                deferredFailureIds,
                                firstPage
                        );
                if (movies.isEmpty()) {
                    break;
                }

                for (Movie movie : movies) {
                    attempted++;
                    try {
                        MovieDetailDto detail = tmdbApiService.getMovieDetails(movie.getTmdbId().longValue());
                        if (detail == null) {
                            failed++;
                            deferredFailureIds.add(movie.getId());
                            log.warn("Metadata backfill returned no detail for tmdbId={}", movie.getTmdbId());
                        } else {
                            movieService.saveOrUpdateMovieDetails(detail, movie);
                            succeeded++;
                        }
                    } catch (RuntimeException exception) {
                        failed++;
                        deferredFailureIds.add(movie.getId());
                        log.warn("Metadata backfill failed for tmdbId={}: {}", movie.getTmdbId(), exception.getMessage());
                    }
                    if (attempted % 100 == 0) {
                        log.info("Metadata backfill progress: attempted={}, succeeded={}, failed={}", attempted, succeeded, failed);
                    }
                    pause(rateLimitMs);
                }
            }

            if (succeeded > 0) {
                cacheInvalidationService.evictAll();
            }
            long syncedAfter = movieRepository.countRecommendationReadyMoviesWithSemanticMetadata(
                    RecommendationScorer.MIN_RECOMMENDATION_RATING
            );
            BackfillReport report = new BackfillReport(total, syncedBefore, syncedAfter, attempted, succeeded, failed);
            log.info("Finished semantic metadata backfill: {}", report);
            return report;
        } finally {
            running.set(false);
        }
    }

    private void pause(long rateLimitMs) {
        if (rateLimitMs <= 0) {
            return;
        }
        try {
            Thread.sleep(rateLimitMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Metadata backfill interrupted.", exception);
        }
    }

    public record BackfillReport(
            long recommendationReady,
            long syncedBefore,
            long syncedAfter,
            int attempted,
            int succeeded,
            int failed
    ) {
        public double completionRate() {
            return recommendationReady == 0 ? 1.0 : (double) syncedAfter / recommendationReady;
        }
    }
}
