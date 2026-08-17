package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.config.CatalogIngestionProperties;
import com.atlasmind.atlaswatch.dto.tmdb.SearchResponseDto;
import com.atlasmind.atlaswatch.models.Movie;
import com.atlasmind.atlaswatch.repository.MovieRepository;
import com.atlasmind.atlaswatch.support.TestFixtures;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogIngestionServiceTest {

    @Mock
    private CatalogIngestionProperties properties;
    @Mock
    private TmdbApiService tmdbApiService;
    @Mock
    private MovieRepository movieRepository;
    @Mock
    private MovieService movieService;
    @Mock
    private RecommendationCacheInvalidationService recommendationCacheInvalidationService;

    @InjectMocks
    private CatalogIngestionService catalogIngestionService;

    @BeforeEach
    void setUp() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getPopularPages()).thenReturn(1);
        when(properties.getTopRatedPages()).thenReturn(1);
        when(properties.getTrendingPages()).thenReturn(1);
        when(properties.getRateLimitMs()).thenReturn(0L);
    }

    @Test
    void ingestCatalogRefreshesNewStaleAndIncompleteMoviesAcrossSources() {
        when(properties.getStaleAfterHours()).thenReturn(168L);
        when(properties.getDiscoverPagesPerGenre()).thenReturn(1);
        when(properties.getDiscoverGenreIds()).thenReturn(List.of(28));

        Movie newMovie = TestFixtures.movie(10L, 100, "New Arrival");
        newMovie.setRuntime(null);

        Movie staleMovie = TestFixtures.movie(11L, 101, "Needs Refresh");
        staleMovie.setCachedAt(LocalDateTime.now().minusDays(12));

        Movie freshMovie = TestFixtures.movie(12L, 102, "Fresh Already");
        freshMovie.setCachedAt(LocalDateTime.now().minusHours(3));
        freshMovie.setSemanticMetadataSyncedAt(LocalDateTime.now().minusHours(3));

        Movie incompleteMovie = TestFixtures.movie(13L, 103, "Missing Runtime");
        incompleteMovie.setRuntime(null);
        incompleteMovie.setCachedAt(LocalDateTime.now().minusHours(2));

        when(tmdbApiService.getPopularMovies(1)).thenReturn(new SearchResponseDto(
                1,
                List.of(
                        TestFixtures.movieDto(100L, "New Arrival", List.of(28)),
                        TestFixtures.movieDto(101L, "Needs Refresh", List.of(53))
                ),
                1,
                2
        ));
        when(tmdbApiService.getTopRatedMovies(1)).thenReturn(new SearchResponseDto(
                1,
                List.of(
                        TestFixtures.movieDto(101L, "Needs Refresh", List.of(53)),
                        TestFixtures.movieDto(102L, "Fresh Already", List.of(18))
                ),
                1,
                2
        ));
        when(tmdbApiService.getTrendingMovies("week", 1)).thenReturn(new SearchResponseDto(
                1,
                List.of(TestFixtures.movieDto(102L, "Fresh Already", List.of(18))),
                1,
                1
        ));
        when(tmdbApiService.discoverMoviesByGenre(28, 1)).thenReturn(new SearchResponseDto(
                1,
                List.of(TestFixtures.movieDto(103L, "Missing Runtime", List.of(28))),
                1,
                1
        ));

        when(movieRepository.findByTmdbId(100)).thenReturn(Optional.empty());
        when(movieRepository.findByTmdbId(101)).thenReturn(Optional.of(staleMovie));
        when(movieRepository.findByTmdbId(102)).thenReturn(Optional.of(freshMovie));
        when(movieRepository.findByTmdbId(103)).thenReturn(Optional.of(incompleteMovie));

        when(movieService.upsertMovieSummary(any(), any())).thenAnswer(invocation -> {
            var dto = invocation.getArgument(0, com.atlasmind.atlaswatch.dto.tmdb.MovieDto.class);
            Movie existing = invocation.getArgument(1, Movie.class);
            if (existing != null) {
                return existing;
            }

            Movie saved = TestFixtures.movie((long) dto.getId(), dto.getId().intValue(), dto.getTitle());
            saved.setRuntime(null);
            return saved;
        });

        when(tmdbApiService.getMovieDetails(100L)).thenReturn(TestFixtures.movieDetailDto(
                100L, "New Arrival", List.of(new com.atlasmind.atlaswatch.dto.tmdb.MovieDetailDto.Genre(28, "Action"))
        ));
        when(tmdbApiService.getMovieDetails(101L)).thenReturn(TestFixtures.movieDetailDto(
                101L, "Needs Refresh", List.of(new com.atlasmind.atlaswatch.dto.tmdb.MovieDetailDto.Genre(53, "Thriller"))
        ));
        when(tmdbApiService.getMovieDetails(103L)).thenReturn(TestFixtures.movieDetailDto(
                103L, "Missing Runtime", List.of(new com.atlasmind.atlaswatch.dto.tmdb.MovieDetailDto.Genre(28, "Action"))
        ));

        catalogIngestionService.ingestCatalogNow();

        verify(movieService, times(4)).upsertMovieSummary(any(), any());
        verify(movieService, times(3)).saveOrUpdateMovieDetails(any(), any());
        verify(tmdbApiService).getMovieDetails(100L);
        verify(tmdbApiService).getMovieDetails(101L);
        verify(tmdbApiService).getMovieDetails(103L);
        verify(tmdbApiService, never()).getMovieDetails(102L);
        verify(recommendationCacheInvalidationService).evictAll();
    }

    @Test
    void ingestCatalogContinuesWhenOneSourceFails() {
        when(properties.getTrendingPages()).thenReturn(0);
        when(properties.getDiscoverGenreIds()).thenReturn(List.of());

        when(tmdbApiService.getPopularMovies(1)).thenThrow(new RuntimeException("TMDB unavailable"));
        when(tmdbApiService.getTopRatedMovies(1)).thenReturn(new SearchResponseDto(
                1,
                List.of(TestFixtures.movieDto(201L, "Recovered From Fallback", List.of(18))),
                1,
                1
        ));
        when(movieRepository.findByTmdbId(201)).thenReturn(Optional.empty());
        when(movieService.upsertMovieSummary(any(), any())).thenReturn(TestFixtures.movie(21L, 201, "Recovered From Fallback"));
        when(tmdbApiService.getMovieDetails(201L)).thenReturn(TestFixtures.movieDetailDto(
                201L, "Recovered From Fallback",
                List.of(new com.atlasmind.atlaswatch.dto.tmdb.MovieDetailDto.Genre(18, "Drama"))
        ));

        catalogIngestionService.ingestCatalogNow();

        verify(movieService).upsertMovieSummary(any(), any());
        verify(movieService).saveOrUpdateMovieDetails(any(), any());
        verify(tmdbApiService, times(1)).getTopRatedMovies(1);
        verify(recommendationCacheInvalidationService).evictAll();
    }
}

