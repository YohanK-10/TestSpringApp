package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.dto.tmdb.MovieDetailDto;
import com.atlasmind.atlaswatch.dto.tmdb.SearchResponseDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

@Slf4j
@Service
@RequiredArgsConstructor
public class TmdbApiService {

    private static final String TMDB_BACKEND = "tmdb";

    private final RestClient tmdbRestClient;

    /**
     * Search for movies by title.
     * TMDB endpoint: GET /search/movie?query={query}&page={page}
     */
    @CircuitBreaker(name = TMDB_BACKEND)
    @Retry(name = TMDB_BACKEND, fallbackMethod = "searchMoviesFallback")
    public SearchResponseDto searchMovies(String query, int page) {
        return tmdbRestClient.get().uri("/search/movie?query={query}&page={page}", query, page)
                .retrieve().body(SearchResponseDto.class);
    }

    /**
     * Get detailed information about a specific movie.
     * TMDB endpoint: GET /movie/{tmdbId}
     */
    @CircuitBreaker(name = TMDB_BACKEND)
    @Retry(name = TMDB_BACKEND, fallbackMethod = "getMovieDetailsFallback")
    public MovieDetailDto getMovieDetails(Long tmdbId) {
        return tmdbRestClient.get().uri("/movie/{tmdbId}?append_to_response=keywords", tmdbId).retrieve()
                .body(MovieDetailDto.class);
    }

    /**
     * Get currently trending movies (updated daily by TMDB).
     * TMDB endpoint: GET /trending/movie/day
     */
    @CircuitBreaker(name = TMDB_BACKEND)
    @Retry(name = TMDB_BACKEND, fallbackMethod = "getDailyTrendingMoviesFallback")
    public SearchResponseDto getTrendingMovies() {
        return tmdbRestClient.get().uri("/trending/movie/day?page=1").retrieve()
                .body(SearchResponseDto.class);
    }

    /**
     * Get trending movies for a specific TMDB window and page.
     * TMDB endpoint: GET /trending/movie/{window}?page={page}
     */
    @CircuitBreaker(name = TMDB_BACKEND)
    @Retry(name = TMDB_BACKEND, fallbackMethod = "getTrendingMoviesFallback")
    public SearchResponseDto getTrendingMovies(String window, int page) {
        return tmdbRestClient.get().uri("/trending/movie/{window}?page={page}", window, page).retrieve()
                .body(SearchResponseDto.class);
    }

    /**
     * Get popular movies.
     * TMDB endpoint: GET /movie/popular?page={page}
     */
    @CircuitBreaker(name = TMDB_BACKEND)
    @Retry(name = TMDB_BACKEND, fallbackMethod = "getPopularMoviesFallback")
    public SearchResponseDto getPopularMovies(int page) {
        return tmdbRestClient.get().uri("/movie/popular?page={page}", page)
                .retrieve().body(SearchResponseDto.class);
    }

    /**
     * Get top rated movies.
     * TMDB endpoint: GET /movie/top_rated?page={page}
     */
    @CircuitBreaker(name = TMDB_BACKEND)
    @Retry(name = TMDB_BACKEND, fallbackMethod = "getTopRatedMoviesFallback")
    public SearchResponseDto getTopRatedMovies(int page) {
        return tmdbRestClient.get().uri("/movie/top_rated?page={page}", page)
                .retrieve().body(SearchResponseDto.class);
    }

    /**
     * Discover movies for a specific genre.
     * TMDB endpoint: GET /discover/movie?with_genres={genreId}&page={page}
     */
    @CircuitBreaker(name = TMDB_BACKEND)
    @Retry(name = TMDB_BACKEND, fallbackMethod = "discoverMoviesByGenreFallback")
    public SearchResponseDto discoverMoviesByGenre(int genreId, int page) {
        return tmdbRestClient.get()
                .uri((UriBuilder builder) -> builder.path("/discover/movie")
                        .queryParam("with_genres", genreId)
                        .queryParam("page", page)
                        .queryParam("include_adult", false)
                        .queryParam("include_video", false)
                        .queryParam("sort_by", "popularity.desc")
                        .build())
                .retrieve()
                .body(SearchResponseDto.class);
    }

    // ── Fallback methods ──────────────────────────────────────

    @SuppressWarnings("unused")
    private SearchResponseDto searchMoviesFallback(String query, int page, Throwable t) {
        log.warn("TMDB search failed for query '{}' page {}: {}", query, page, t.getMessage());
        return null;
    }

    @SuppressWarnings("unused")
    private MovieDetailDto getMovieDetailsFallback(Long tmdbId, Throwable t) {
        log.warn("TMDB movie details failed for ID {}: {}", tmdbId, t.getMessage());
        return null;
    }

    @SuppressWarnings("unused")
    private SearchResponseDto getTrendingMoviesFallback(String window, int page, Throwable t) {
        log.warn("TMDB trending movies failed for window '{}' page {}: {}", window, page, t.getMessage());
        return null;
    }

    @SuppressWarnings("unused")
    private SearchResponseDto getDailyTrendingMoviesFallback(Throwable t) {
        log.warn("TMDB daily trending movies failed: {}", t.getMessage());
        return null;
    }

    @SuppressWarnings("unused")
    private SearchResponseDto getPopularMoviesFallback(int page, Throwable t) {
        log.warn("TMDB popular movies failed for page {}: {}", page, t.getMessage());
        return null;
    }

    @SuppressWarnings("unused")
    private SearchResponseDto getTopRatedMoviesFallback(int page, Throwable t) {
        log.warn("TMDB top rated movies failed for page {}: {}", page, t.getMessage());
        return null;
    }

    @SuppressWarnings("unused")
    private SearchResponseDto discoverMoviesByGenreFallback(int genreId, int page, Throwable t) {
        log.warn("TMDB discover failed for genre {} page {}: {}", genreId, page, t.getMessage());
        return null;
    }
}

