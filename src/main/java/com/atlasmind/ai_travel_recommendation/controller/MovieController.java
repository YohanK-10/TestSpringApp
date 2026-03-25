package com.atlasmind.ai_travel_recommendation.controller;

import com.atlasmind.ai_travel_recommendation.dto.response.GenreResponseDto;
import com.atlasmind.ai_travel_recommendation.dto.response.MovieResponseDto;
import com.atlasmind.ai_travel_recommendation.dto.tmdb.SearchResponseDto;
import com.atlasmind.ai_travel_recommendation.exceptions.ResourceNotFoundException;
import com.atlasmind.ai_travel_recommendation.repository.GenreRepository;
import com.atlasmind.ai_travel_recommendation.repository.MovieGenreRepository;
import com.atlasmind.ai_travel_recommendation.service.MovieService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Public REST endpoints for movie discovery.
 * No authentication required — anyone can browse movies.
 *
 * These are the core product endpoints:
 * - Search: find movies by title
 * - Trending: what's popular right now
 * - Details: full info about a specific movie
 * - Genres: list of available genres (for filters/dropdowns)
 */
@RestController
@RequestMapping("/api/movies")
@RequiredArgsConstructor
public class MovieController {

    private final MovieService movieService;
    private final GenreRepository genreRepository;
    private final MovieGenreRepository movieGenreRepository;

    /**
     * GET /api/movies/search?query=inception&page=1
     *
     * Search movies by title. Results come from TMDB and are
     * persisted to our DB (so users can later review/watchlist them).
     *
     * Returns TMDB's paginated format directly. Why not convert to
     * our own DTO? Because search results already have the fields
     * the frontend needs, and converting 20 results would require
     * 20 genre lookups — not worth the cost for a search list.
     * The detail endpoint is where we return our rich DTO.
     */
    @GetMapping("/search")
    public ResponseEntity<SearchResponseDto> searchMovies(
            @RequestParam String query,
            @RequestParam(defaultValue = "1") int page) {

        SearchResponseDto response = movieService.searchMovies(query, page);

        if (response == null) {
            throw new ResourceNotFoundException("Movie", "query", query);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/movies/trending
     *
     * Get today's trending movies from TMDB.
     * In Issue #9, this will be cached in Redis with a 10-min TTL
     * since trending data doesn't change every second.
     */
    @GetMapping("/trending")
    public ResponseEntity<SearchResponseDto> getTrendingMovies() {
        SearchResponseDto response = movieService.getTrendingMovies();

        if (response == null) {
            throw new ResourceNotFoundException("Trending movies not available");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/movies/{tmdbId}
     *
     * Get detailed information about a specific movie.
     * This is where the DB-first cache pattern shines:
     * fast reads from Postgres, fallback to TMDB, stale data as last resort.
     *
     * Returns our MovieResponseDto (not the raw entity) with genre names
     * resolved from the join table.
     */
    @GetMapping("/{tmdbId}")
    public ResponseEntity<MovieResponseDto> getMovieDetails(@PathVariable Integer tmdbId) {
        MovieResponseDto movie = movieService.getMovieDetailsDto(tmdbId);

        if (movie == null) {
            throw new ResourceNotFoundException("Movie", "tmdbId", tmdbId.toString());
        }
        return ResponseEntity.ok(movie);
    }

    /**
     * GET /api/movies/genres
     *
     * Returns all available genres. This is a static list (19 TMDB genres)
     * that rarely changes. Useful for the frontend to build filter dropdowns
     * or genre chips on movie cards.
     */
    @GetMapping("/genres")
    public ResponseEntity<List<GenreResponseDto>> getGenres() {
        List<GenreResponseDto> genres = genreRepository.findAll()
                .stream()
                .map(GenreResponseDto::fromGenre)
                .toList();

        return ResponseEntity.ok(genres);
    }

    /**
     * GET /api/movies/local-search?query=dark&page=1&size=10
     *
     * Search movies in our LOCAL PostgreSQL database using full-text search.
     * Unlike /search (which calls TMDB), this searches only movies we've
     * already cached — but it's faster and works when TMDB is down.
     *
     * Results are ranked by relevance using ts_rank().
     */
    @GetMapping("/local-search")
    public ResponseEntity<List<MovieResponseDto>> searchLocal(
            @RequestParam String query,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {

        List<MovieResponseDto> results = movieService.searchLocal(query, page, size);
        return ResponseEntity.ok(results);
    }
}