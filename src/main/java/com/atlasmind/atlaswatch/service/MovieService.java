package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.dto.response.MovieResponseDto;
import com.atlasmind.atlaswatch.dto.tmdb.MovieDetailDto;
import com.atlasmind.atlaswatch.dto.tmdb.MovieDto;
import com.atlasmind.atlaswatch.dto.tmdb.SearchResponseDto;
import com.atlasmind.atlaswatch.models.Genre;
import com.atlasmind.atlaswatch.models.Keyword;
import com.atlasmind.atlaswatch.models.Movie;
import com.atlasmind.atlaswatch.models.MovieGenre;
import com.atlasmind.atlaswatch.models.MovieKeyword;
import com.atlasmind.atlaswatch.repository.GenreRepository;
import com.atlasmind.atlaswatch.repository.KeywordRepository;
import com.atlasmind.atlaswatch.repository.MovieGenreRepository;
import com.atlasmind.atlaswatch.repository.MovieKeywordRepository;
import com.atlasmind.atlaswatch.repository.MovieRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MovieService {

    private static final int CACHE_HOURS = 24;

    private final MovieRepository movieRepository;
    private final GenreRepository genreRepository;
    private final MovieGenreRepository movieGenreRepository;
    private final KeywordRepository keywordRepository;
    private final MovieKeywordRepository movieKeywordRepository;
    private final TmdbApiService tmdbApiService;

    @Transactional
    public Movie getMovieByTmdbId(Integer tmdbId) {
        Optional<Movie> cached = movieRepository.findByTmdbId(tmdbId);

        if (cached.isPresent() && isFresh(cached.get())) {
            log.debug("Cache HIT for tmdbId={} (fresh)", tmdbId);
            return cached.get();
        }

        log.debug("Cache {} for tmdbId={} - calling TMDB", cached.isPresent() ? "STALE" : "MISS", tmdbId);

        MovieDetailDto tmdbData = tmdbApiService.getMovieDetails(tmdbId.longValue());
        if (tmdbData != null) {
            return saveOrUpdateMovieDetails(tmdbData, cached.orElse(null));
        }

        if (cached.isPresent()) {
            log.warn("TMDB failed for tmdbId={}, returning stale data", tmdbId);
            return cached.get();
        }

        log.error("No data available for tmdbId={}", tmdbId);
        return null;
    }

    @Transactional
    public SearchResponseDto searchMovies(String query, int page) {
        SearchResponseDto response = tmdbApiService.searchMovies(query, page);

        if (response != null && response.getResults() != null) {
            for (MovieDto dto : response.getResults()) {
                upsertMovieSummary(dto);
            }
        }

        return response;
    }

    @Cacheable(value = "trending", key = "'daily'")
    @Transactional
    public SearchResponseDto getTrendingMovies() {
        SearchResponseDto response = tmdbApiService.getTrendingMovies();

        if (response != null && response.getResults() != null) {
            for (MovieDto dto : response.getResults()) {
                upsertMovieSummary(dto);
            }
        }

        return response;
    }

    @Transactional
    public Movie saveOrUpdateMovieDetails(MovieDetailDto dto) {
        if (dto == null || dto.getId() == null) {
            return null;
        }

        Movie existing = movieRepository.findByTmdbId(dto.getId().intValue()).orElse(null);
        return saveOrUpdateMovieDetails(dto, existing);
    }

    @Transactional
    public Movie saveOrUpdateMovieDetails(MovieDetailDto dto, Movie existing) {
        if (dto == null || dto.getId() == null) {
            return null;
        }

        Movie movie = existing != null ? existing : new Movie();
        applyDetailFields(movie, dto);

        Movie savedMovie = movieRepository.save(movie);
        if (dto.getGenres() != null && !dto.getGenres().isEmpty()) {
            updateGenreMappings(savedMovie, dto.getGenres());
        }
        updateKeywordMappings(savedMovie, dto.getResolvedKeywords());

        return savedMovie;
    }

    @Transactional
    public Movie upsertMovieSummary(MovieDto dto) {
        if (dto == null || dto.getId() == null) {
            return null;
        }

        Movie existing = movieRepository.findByTmdbId(dto.getId().intValue()).orElse(null);
        return upsertMovieSummary(dto, existing);
    }

    @Transactional
    public Movie upsertMovieSummary(MovieDto dto, Movie existing) {
        if (dto == null || dto.getId() == null) {
            return null;
        }

        boolean isNewMovie = existing == null;
        Movie movie = isNewMovie ? new Movie() : existing;
        applySummaryFields(movie, dto);

        Movie savedMovie = movieRepository.save(movie);
        if (isNewMovie && dto.getGenreIds() != null && !dto.getGenreIds().isEmpty()) {
            updateGenreMappingsByIds(savedMovie, dto.getGenreIds());
        }

        return savedMovie;
    }

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

    private boolean isFresh(Movie movie) {
        return movie.getCachedAt() != null
                && movie.getCachedAt().isAfter(LocalDateTime.now().minusHours(CACHE_HOURS));
    }

    private void applySummaryFields(Movie movie, MovieDto dto) {
        movie.setTmdbId(dto.getId().intValue());
        setIfTextPresent(movie::setMovieTitle, movie.getMovieTitle(), dto.getTitle());
        setIfTextPresent(movie::setOverview, movie.getOverview(), dto.getOverview());
        setIfTextPresent(movie::setPosterPath, movie.getPosterPath(), dto.getPosterPath());
        setIfTextPresent(movie::setBackdropPath, movie.getBackdropPath(), dto.getBackdropPath());

        LocalDate parsedReleaseDate = parseDate(dto.getReleaseDate());
        if (parsedReleaseDate != null || movie.getReleaseDate() == null) {
            movie.setReleaseDate(parsedReleaseDate);
        }
        if (dto.getVoteAverage() != null || movie.getMovieRating() == null) {
            movie.setMovieRating(dto.getVoteAverage());
        }
        if (dto.getVoteCount() != null || movie.getVoteCount() == null) {
            movie.setVoteCount(sanitizeVoteCount(dto.getVoteCount()));
        }
        if (dto.getPopularity() != null || movie.getPopularity() == null) {
            movie.setPopularity(dto.getPopularity());
        }

        movie.setCachedAt(LocalDateTime.now());
    }

    private void applyDetailFields(Movie movie, MovieDetailDto dto) {
        movie.setTmdbId(dto.getId().intValue());
        setIfTextPresent(movie::setMovieTitle, movie.getMovieTitle(), dto.getTitle());
        setIfTextPresent(movie::setOverview, movie.getOverview(), dto.getOverview());
        setIfTextPresent(movie::setPosterPath, movie.getPosterPath(), dto.getPosterPath());
        setIfTextPresent(movie::setBackdropPath, movie.getBackdropPath(), dto.getBackdropPath());

        LocalDate parsedReleaseDate = parseDate(dto.getReleaseDate());
        if (parsedReleaseDate != null || movie.getReleaseDate() == null) {
            movie.setReleaseDate(parsedReleaseDate);
        }
        if (dto.getVoteAverage() != null || movie.getMovieRating() == null) {
            movie.setMovieRating(dto.getVoteAverage());
        }
        if (dto.getVoteCount() != null || movie.getVoteCount() == null) {
            movie.setVoteCount(sanitizeVoteCount(dto.getVoteCount()));
        }
        if (dto.getRuntime() != null || movie.getRuntime() == null) {
            movie.setRuntime(dto.getRuntime());
        }
        if (dto.getPopularity() != null || movie.getPopularity() == null) {
            movie.setPopularity(dto.getPopularity());
        }

        movie.setCachedAt(LocalDateTime.now());
        movie.setSemanticMetadataSyncedAt(LocalDateTime.now());
    }

    private Integer sanitizeVoteCount(Integer voteCount) {
        return voteCount == null ? null : Math.max(0, voteCount);
    }

    private void updateGenreMappings(Movie movie, List<MovieDetailDto.Genre> tmdbGenres) {
        movieGenreRepository.deleteByMovieId(movie.getId());
        movieGenreRepository.flush();

        for (Genre genre : resolveOrCreateGenres(tmdbGenres)) {
            movieGenreRepository.save(new MovieGenre(movie, genre));
        }
    }

    private void updateGenreMappingsByIds(Movie movie, List<Integer> tmdbGenreIds) {
        List<Genre> genres = genreRepository.findByTmdbIdIn(tmdbGenreIds);
        if (genres.isEmpty()) {
            return;
        }

        movieGenreRepository.deleteByMovieId(movie.getId());
        movieGenreRepository.flush();

        for (Genre genre : genres) {
            movieGenreRepository.save(new MovieGenre(movie, genre));
        }
    }

    private void updateKeywordMappings(Movie movie, List<MovieDetailDto.Keyword> tmdbKeywords) {
        movieKeywordRepository.deleteByMovieId(movie.getId());
        movieKeywordRepository.flush();

        for (Keyword keyword : resolveOrCreateKeywords(tmdbKeywords)) {
            movieKeywordRepository.save(new MovieKeyword(movie, keyword));
        }
    }

    private List<Genre> resolveOrCreateGenres(List<MovieDetailDto.Genre> tmdbGenres) {
        List<Integer> tmdbGenreIds = tmdbGenres.stream()
                .map(MovieDetailDto.Genre::getId)
                .filter(Objects::nonNull)
                .toList();

        Map<Integer, Genre> existingGenres = new LinkedHashMap<>();
        for (Genre genre : genreRepository.findByTmdbIdIn(tmdbGenreIds)) {
            existingGenres.put(genre.getTmdbId(), genre);
        }

        List<Genre> resolvedGenres = new ArrayList<>();
        for (MovieDetailDto.Genre tmdbGenre : tmdbGenres) {
            if (tmdbGenre.getId() == null) {
                continue;
            }

            Genre genre = existingGenres.get(tmdbGenre.getId());
            if (genre == null) {
                genre = new Genre();
                genre.setTmdbId(tmdbGenre.getId());
                genre.setName(tmdbGenre.getName());
                genre = genreRepository.save(genre);
                existingGenres.put(genre.getTmdbId(), genre);
            } else if (!Objects.equals(genre.getName(), tmdbGenre.getName()) && hasText(tmdbGenre.getName())) {
                genre.setName(tmdbGenre.getName());
                genre = genreRepository.save(genre);
                existingGenres.put(genre.getTmdbId(), genre);
            }

            resolvedGenres.add(genre);
        }

        return resolvedGenres;
    }

    private List<Keyword> resolveOrCreateKeywords(List<MovieDetailDto.Keyword> tmdbKeywords) {
        if (tmdbKeywords == null || tmdbKeywords.isEmpty()) {
            return List.of();
        }

        List<Integer> tmdbKeywordIds = tmdbKeywords.stream()
                .map(MovieDetailDto.Keyword::getId)
                .filter(Objects::nonNull)
                .toList();

        Map<Integer, Keyword> existingKeywords = new LinkedHashMap<>();
        for (Keyword keyword : keywordRepository.findByTmdbIdIn(tmdbKeywordIds)) {
            existingKeywords.put(keyword.getTmdbId(), keyword);
        }

        List<Keyword> resolvedKeywords = new ArrayList<>();
        for (MovieDetailDto.Keyword tmdbKeyword : tmdbKeywords) {
            if (tmdbKeyword.getId() == null || !hasText(tmdbKeyword.getName())) {
                continue;
            }

            Keyword keyword = existingKeywords.get(tmdbKeyword.getId());
            if (keyword == null) {
                keyword = new Keyword();
                keyword.setTmdbId(tmdbKeyword.getId());
                keyword.setName(tmdbKeyword.getName());
                keyword = keywordRepository.save(keyword);
                existingKeywords.put(keyword.getTmdbId(), keyword);
            } else if (!Objects.equals(keyword.getName(), tmdbKeyword.getName())) {
                keyword.setName(tmdbKeyword.getName());
                keyword = keywordRepository.save(keyword);
                existingKeywords.put(keyword.getTmdbId(), keyword);
            }

            resolvedKeywords.add(keyword);
        }

        return resolvedKeywords;
    }

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

    private void setIfTextPresent(Consumer<String> setter, String existingValue, String incomingValue) {
        if (hasText(incomingValue) || !hasText(existingValue)) {
            setter.accept(incomingValue);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

