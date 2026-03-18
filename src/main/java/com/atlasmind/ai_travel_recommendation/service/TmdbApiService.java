package com.atlasmind.ai_travel_recommendation.service;

import com.atlasmind.ai_travel_recommendation.dto.tmdb.MovieDetailDto;
import com.atlasmind.ai_travel_recommendation.dto.tmdb.SearchResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Service
@RequiredArgsConstructor
public class TmdbApiService {

    private final RestClient tmdbRestClient;

    /**
     * Search for movies by title.
     * TMDB endpoint: GET /search/movie?query={query}&page={page}
     */
    public SearchResponseDto searchMovies(String query, int page) {
        try {
            return tmdbRestClient.get().uri("/search/movie?query={query}&page={page}", query, page)
                    .retrieve().body(SearchResponseDto.class);
        } catch (RestClientException e) {
            log.error("TMDB search failed for query '{}': {}", query, e.getMessage());
            return null;
        }
    }

    /**
     * Get detailed information about a specific movie.
     * TMDB endpoint: GET /movie/{tmdbId}
     */
    public MovieDetailDto getMovieDetails(Long tmdbId) {
        try {
            return tmdbRestClient.get().uri("/movie/{tmdbId}", tmdbId).retrieve()
                    .body(MovieDetailDto.class);
        } catch (RestClientException e) {
            log.error("TMDB movie details failed for ID {}: {}", tmdbId, e.getMessage());
            return null;
        }
    }

    /**
     * Get currently trending movies (updated daily by TMDB).
     * TMDB endpoint: GET /trending/movie/day
     */
    public SearchResponseDto getTrendingMovies() {
        try {
            return tmdbRestClient.get().uri("/trending/movie/day").retrieve()
                    .body(SearchResponseDto.class);
        } catch (RestClientException e) {
            log.error("TMDB trending movies failed: {}", e.getMessage());
            return null;
        }
    }
}