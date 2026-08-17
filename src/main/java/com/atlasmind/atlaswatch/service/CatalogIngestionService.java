package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.config.CatalogIngestionProperties;
import com.atlasmind.atlaswatch.dto.tmdb.MovieDetailDto;
import com.atlasmind.atlaswatch.dto.tmdb.MovieDto;
import com.atlasmind.atlaswatch.dto.tmdb.SearchResponseDto;
import com.atlasmind.atlaswatch.models.Movie;
import com.atlasmind.atlaswatch.repository.MovieRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogIngestionService {

    private final CatalogIngestionProperties properties;
    private final TmdbApiService tmdbApiService;
    private final MovieRepository movieRepository;
    private final MovieService movieService;
    private final RecommendationCacheInvalidationService recommendationCacheInvalidationService;

    private final AtomicBoolean ingestionInProgress = new AtomicBoolean(false);

    @Scheduled(
            fixedDelayString = "${atlaswatch.catalog.ingestion.fixed-delay-ms:43200000}",
            initialDelayString = "${atlaswatch.catalog.ingestion.initial-delay-ms:300000}"
    )
    public void runScheduledIngestion() {
        if (!properties.isEnabled()) {
            return;
        }
        ingestCatalog("scheduled refresh");
    }

    @Async
    public void ensureCatalogSeeded() {
        if (!properties.isEnabled()) {
            return;
        }
        if (movieRepository.count() >= properties.getMinimumCatalogSize()) {
            return;
        }

        ingestCatalog("catalog bootstrap");
    }

    public void ingestCatalogNow() {
        if (!properties.isEnabled()) {
            log.info("Skipping manual catalog ingestion because it is disabled.");
            return;
        }
        ingestCatalog("manual trigger");
    }

    private void ingestCatalog(String reason) {
        if (!ingestionInProgress.compareAndSet(false, true)) {
            log.info("Skipping TMDB catalog ingestion because another run is already in progress.");
            return;
        }

        IngestionStats stats = new IngestionStats();
        Set<Integer> processedTmdbIds = new LinkedHashSet<>();

        try {
            log.info("Starting TMDB catalog ingestion ({})", reason);

            ingestSource("popular", properties.getPopularPages(), tmdbApiService::getPopularMovies,
                    processedTmdbIds, stats);
            ingestSource("top-rated", properties.getTopRatedPages(), tmdbApiService::getTopRatedMovies,
                    processedTmdbIds, stats);
            ingestSource("trending", properties.getTrendingPages(),
                    page -> tmdbApiService.getTrendingMovies("week", page), processedTmdbIds, stats);

            for (Integer genreId : properties.getDiscoverGenreIds()) {
                ingestSource("discover-genre-" + genreId, properties.getDiscoverPagesPerGenre(),
                        page -> tmdbApiService.discoverMoviesByGenre(genreId, page),
                        processedTmdbIds, stats);
            }

            if (stats.hasCatalogChanges()) {
                recommendationCacheInvalidationService.evictAll();
            }

            log.info(
                    "Finished TMDB catalog ingestion: {} summaries processed, {} details refreshed, {} source failures, {} detail failures.",
                    stats.summaryUpserts,
                    stats.detailRefreshes,
                    stats.sourceFailures,
                    stats.detailFailures
            );
        } finally {
            ingestionInProgress.set(false);
        }
    }

    private void ingestSource(
            String sourceName,
            int totalPages,
            IntFunction<SearchResponseDto> pageFetcher,
            Set<Integer> processedTmdbIds,
            IngestionStats stats
    ) {
        if (totalPages <= 0) {
            return;
        }

        for (int page = 1; page <= totalPages; page++) {
            SearchResponseDto response;
            try {
                response = pageFetcher.apply(page);
            } catch (RuntimeException e) {
                stats.sourceFailures++;
                log.warn("Catalog ingestion source '{}' failed on page {}: {}", sourceName, page, e.getMessage());
                return;
            }

            if (response == null) {
                stats.sourceFailures++;
                log.warn("Catalog ingestion source '{}' returned no response on page {}.", sourceName, page);
                return;
            }

            if (response.getResults() == null || response.getResults().isEmpty()) {
                return;
            }

            processMoviesFromSource(sourceName, response.getResults(), processedTmdbIds, stats);

            if (response.getTotalPages() != null && page >= response.getTotalPages()) {
                return;
            }

            rateLimit();
        }
    }

    private void processMoviesFromSource(
            String sourceName,
            List<MovieDto> results,
            Set<Integer> processedTmdbIds,
            IngestionStats stats
    ) {
        for (MovieDto summary : results) {
            if (summary == null || summary.getId() == null) {
                continue;
            }

            int tmdbId = summary.getId().intValue();
            if (!processedTmdbIds.add(tmdbId)) {
                continue;
            }

            Optional<Movie> existing = movieRepository.findByTmdbId(tmdbId);
            boolean needsDetailRefresh = existing.map(this::shouldRefreshDetails).orElse(true);
            Movie savedMovie = movieService.upsertMovieSummary(summary, existing.orElse(null));
            stats.summaryUpserts++;

            if (!needsDetailRefresh) {
                continue;
            }

            refreshMovieDetails(sourceName, tmdbId, savedMovie, stats);
        }
    }

    private void refreshMovieDetails(String sourceName, int tmdbId, Movie currentMovie, IngestionStats stats) {
        try {
            MovieDetailDto detailDto = tmdbApiService.getMovieDetails((long) tmdbId);
            if (detailDto == null) {
                stats.detailFailures++;
                log.warn("Catalog detail refresh returned no data for tmdbId={} from source '{}'.", tmdbId, sourceName);
                return;
            }

            movieService.saveOrUpdateMovieDetails(detailDto, currentMovie);
            stats.detailRefreshes++;
            rateLimit();
        } catch (RuntimeException e) {
            stats.detailFailures++;
            log.warn("Catalog detail refresh failed for tmdbId={} from source '{}': {}", tmdbId, sourceName,
                    e.getMessage());
        }
    }

    private boolean shouldRefreshDetails(Movie movie) {
        if (movie == null) {
            return true;
        }
        if (movie.getRuntime() == null || movie.getOverview() == null || movie.getOverview().isBlank()) {
            return true;
        }
        if (movie.getSemanticMetadataSyncedAt() == null) {
            return true;
        }
        if (movie.getCachedAt() == null) {
            return true;
        }

        return movie.getCachedAt().isBefore(LocalDateTime.now().minusHours(properties.getStaleAfterHours()));
    }

    private void rateLimit() {
        if (properties.getRateLimitMs() <= 0) {
            return;
        }

        try {
            Thread.sleep(properties.getRateLimitMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Catalog ingestion was interrupted.", e);
        }
    }

    private static final class IngestionStats {
        private int summaryUpserts;
        private int detailRefreshes;
        private int sourceFailures;
        private int detailFailures;

        private boolean hasCatalogChanges() {
            return summaryUpserts > 0 || detailRefreshes > 0;
        }
    }
}

