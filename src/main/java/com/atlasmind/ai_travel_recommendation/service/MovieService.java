package com.atlasmind.ai_travel_recommendation.service;

import com.atlasmind.ai_travel_recommendation.dto.response.MovieResponseDto;
import com.atlasmind.ai_travel_recommendation.dto.tmdb.MovieDetailDto;
import com.atlasmind.ai_travel_recommendation.dto.tmdb.MovieDto;
import com.atlasmind.ai_travel_recommendation.dto.tmdb.SearchResponseDto;
import com.atlasmind.ai_travel_recommendation.models.Genre;
import com.atlasmind.ai_travel_recommendation.models.Movie;
import com.atlasmind.ai_travel_recommendation.models.MovieGenre;
import com.atlasmind.ai_travel_recommendation.repository.GenreRepository;
import com.atlasmind.ai_travel_recommendation.repository.MovieGenreRepository;
import com.atlasmind.ai_travel_recommendation.repository.MovieRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MovieService {

    private final MovieRepository movieRepository;
    private final GenreRepository genreRepository;
    private final MovieGenreRepository movieGenreRepository;
    private final TmdbApiService tmdbApiService;

    // ─── HOW LONG BEFORE CACHED DATA IS CONSIDERED STALE ──────────
    private static final int CACHE_HOURS = 24;

    // ─── SINGLE MOVIE DETAILS ─────────────────────────────────────

    /**
     * Get a movie by its TMDB ID. This implements the DB-FIRST CACHE pattern:
     * 1. Check PostgreSQL: is this movie cached AND fresh?
     *    → YES: return it (no TMDB call needed)
     * 2. Not cached or stale: call TMDB API
     *    → SUCCESS: save/update in DB, return
     *    → FAILURE: return stale DB data if available, null otherwise
     * This is your RELIABILITY STORY for interviews.
     */
    @Transactional
    public Movie getMovieByTmdbId(Integer tmdbId) {
        // Step 1: Check local cache
        Optional<Movie> cached = movieRepository.findByTmdbId(tmdbId);

        if (cached.isPresent() && isFresh(cached.get())) {
            log.debug("Cache HIT for tmdbId={} (fresh)", tmdbId);
            return cached.get();
        }

        // Step 2: Cache miss or stale — call TMDB
        log.debug("Cache {} for tmdbId={} — calling TMDB",
                  cached.isPresent() ? "STALE" : "MISS", tmdbId);

        MovieDetailDto tmdbData = tmdbApiService.getMovieDetails(tmdbId.longValue());

        if (tmdbData != null) {
            // TMDB returned data — save/update and return
            return saveOrUpdateMovie(tmdbData, cached.orElse(null));
        }

        // Step 3: TMDB failed — return stale data if we have it
        if (cached.isPresent()) {
            log.warn("TMDB failed for tmdbId={}, returning STALE data", tmdbId);
            return cached.get();
        }

        // No cached data and TMDB failed — nothing we can do
        log.error("No data available for tmdbId={}", tmdbId);
        return null;
    }

    // ─── SEARCH ───────────────────────────────────────────────────

    /**
     * Search movies by title. For now, this calls TMDB directly.
     * We persist each result so they're available for reviews/watchlists.
     *
     * In Issue #9, we'll add Redis caching around this method.
     */
    @Transactional
    public SearchResponseDto searchMovies(String query, int page) {
        SearchResponseDto response = tmdbApiService.searchMovies(query, page);

        if (response != null && response.getResults() != null) {
            // Persist each movie from results (so users can review/watchlist them)
            for (MovieDto dto : response.getResults()) {
                persistMovieFromSearchResult(dto);
            }
        }

        return response;
    }

    // ─── TRENDING ─────────────────────────────────────────────────

    /**
     * Get trending movies. Calls TMDB and persists results.
     * In Issue #9, this will be cached in Redis with a 10-min TTL.
     */
    @Cacheable(value = "trending", key = "'daily'")
    @Transactional
    public SearchResponseDto getTrendingMovies() {
        SearchResponseDto response = tmdbApiService.getTrendingMovies();

        if (response != null && response.getResults() != null) {
            for (MovieDto dto : response.getResults()) {
                persistMovieFromSearchResult(dto);
            }
        }

        return response;
    }

    // ─── PRIVATE HELPER METHODS ───────────────────────────────────

    /**
     * Is the cached movie still fresh (less than 24 hours old)?
     */
    private boolean isFresh(Movie movie) {
        return movie.getCachedAt() != null
                && movie.getCachedAt().isAfter(LocalDateTime.now().minusHours(CACHE_HOURS));
    }

    /**
     * Save a new movie or update an existing one from TMDB detail response.
     * Also handles genre mapping via the movie_genre join table.
     */
    private Movie saveOrUpdateMovie(MovieDetailDto dto, Movie existing) {
        Movie movie = (existing != null) ? existing : new Movie();

        // Map fields from TMDB DTO to our entity
        movie.setTmdbId(dto.getId().intValue());
        movie.setMovieTitle(dto.getTitle());
        movie.setOverview(dto.getOverview());
        movie.setPosterPath(dto.getPosterPath());
        movie.setBackdropPath(dto.getBackdropPath());
        movie.setReleaseDate(parseDate(dto.getReleaseDate()));
        movie.setMovieRating(dto.getVoteAverage());
        movie.setRuntime(dto.getRuntime());
        movie.setPopularity(dto.getPopularity());
        movie.setCachedAt(LocalDateTime.now());

        Movie savedMovie = movieRepository.save(movie);

        // Update genre mappings
        if (dto.getGenres() != null) {
            updateGenreMappings(savedMovie, dto.getGenres());
        }

        return savedMovie;
    }

    /**
     * Persist a movie from a search/trending result (less data than detail).
     * Only saves if the movie doesn't already exist in our DB.
     * We DON'T update existing movies here because search results have
     * less data than detail responses (no runtime, genre_ids not genre objects).
     */
    private void persistMovieFromSearchResult(MovieDto dto) {
        if (dto.getId() == null || movieRepository.existsByTmdbId(dto.getId().intValue())) {
            return; // Already cached or invalid — skip
        }

        Movie movie = new Movie();
        movie.setTmdbId(dto.getId().intValue());
        movie.setMovieTitle(dto.getTitle());
        movie.setOverview(dto.getOverview());
        movie.setPosterPath(dto.getPosterPath());
        movie.setBackdropPath(dto.getBackdropPath());
        movie.setReleaseDate(parseDate(dto.getReleaseDate()));
        movie.setMovieRating(dto.getVoteAverage());
        movie.setPopularity(dto.getPopularity());
        movie.setCachedAt(LocalDateTime.now());

        Movie savedMovie = movieRepository.save(movie);

        // Map genres from genre_ids (search results have IDs, not objects)
        if (dto.getGenreIds() != null && !dto.getGenreIds().isEmpty()) {
            List<Genre> genres = genreRepository.findByTmdbIdIn(dto.getGenreIds());
            for (Genre genre : genres) {
                movieGenreRepository.save(new MovieGenre(savedMovie, genre));
            }
        }
    }

    /**
     * Replace all genre mappings for a movie.
     * Delete existing → insert new. Simple and correct.
     * WHY delete-and-reinsert instead of a smart diff?
     * A diff algorithm (check which genres were added/removed) is more
     * efficient in terms of database writes, but:
     * 1. More complex code (more bugs)
     * 2. Genres per movie is tiny (3-5 rows) — the efficiency gain is negligible
     * 3. This runs at most once per 24 hours per movie (cache refresh)
     * The simple approach is correct for this scale.
     */
    private void updateGenreMappings(Movie movie, List<MovieDetailDto.Genre> tmdbGenres) {
        // Delete old mappings first and flush so re-inserts don't race stale rows.
        movieGenreRepository.deleteByMovieId(movie.getId());
        movieGenreRepository.flush();

        // Insert new mappings
        List<Integer> tmdbGenreIds = tmdbGenres.stream()
                .map(MovieDetailDto.Genre::getId)
                .toList();

        List<Genre> genres = genreRepository.findByTmdbIdIn(tmdbGenreIds);

        for (Genre genre : genres) {
            movieGenreRepository.save(new MovieGenre(movie, genre));
        }
    }

    /**
     * Safely parse a date string from TMDB.
     * TMDB returns dates as "2010-07-16" but sometimes returns "" or null.
     */
    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr);
        } catch (DateTimeParseException e) {
            log.warn("Could not parse date: '{}'", dateStr);
            return null;
        }
    }

    // ─── CACHED MOVIE DETAILS (returns DTO, used by controller) ───

    /**
     * Get movie details as a response DTO, cached in Redis.
     *
     * WHY cache the DTO and not the Movie entity?
     * 1. The Movie entity has lazy-loaded JPA relationships — they break
     *    when deserialized from Redis (no Hibernate session attached)
     * 2. Caching the DTO means genres are included — one Redis lookup
     *    replaces both the DB query AND the genre join table query
     * 3. The DTO is exactly what the API returns, so Redis stores
     *    the "finished product" ready to serve
     *
     * The key is the tmdbId — each movie gets its own cache entry.
     * Spring builds the Redis key as: "movieDetails::27205"
     */
    @Transactional
    public MovieResponseDto getMovieDetailsDto(Integer tmdbId) {
        Movie movie = getMovieByTmdbId(tmdbId);

        if (movie == null) {
            return null;
        }

        List<String> genreNames = new ArrayList<>(
                movieGenreRepository.findByMovieId(movie.getId())
                        .stream()
                        .map(mg -> mg.getGenre().getName())
                        .toList()
        );

        return MovieResponseDto.fromMovie(movie, genreNames);
    }

    // ─── LOCAL FULL-TEXT SEARCH ───────────────────────────────────

    /**
     * Search movies in our LOCAL database using PostgreSQL full-text search.
     * This searches movies we've already cached from TMDB.
     *
     * Why have both local search AND TMDB search?
     * - TMDB search: complete catalog (millions of movies), but requires network call
     * - Local search: only movies we've cached, but instant (GIN-indexed, no network)
     *
     * The local search is useful for:
     * 1. Autocomplete/suggestions (needs to be fast)
     * 2. Fallback when TMDB is down
     * 3. Searching with ranking by relevance (TMDB doesn't expose its ranking)
     */
    @Transactional(readOnly = true)
    public List<MovieResponseDto> searchLocal(String query, int page, int size) {
        int offset = (page - 1) * size;
        List<Movie> movies = movieRepository.searchByFullText(query, size, offset);

        return movies.stream().map(movie -> {
            List<String> genreNames = movieGenreRepository.findByMovieId(movie.getId())
                    .stream()
                    .map(mg -> mg.getGenre().getName())
                    .toList();
            return MovieResponseDto.fromMovie(movie, genreNames);
        }).toList();
    }
}
