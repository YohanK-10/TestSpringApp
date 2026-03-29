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
import com.atlasmind.ai_travel_recommendation.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MovieServiceTest {

    @Mock
    private MovieRepository movieRepository;
    @Mock
    private GenreRepository genreRepository;
    @Mock
    private MovieGenreRepository movieGenreRepository;
    @Mock
    private TmdbApiService tmdbApiService;

    @InjectMocks
    private MovieService movieService;

    @Test
    void getMovieByTmdbIdReturnsFreshCachedMovieWithoutCallingTmdb() {
        Movie cached = TestFixtures.movie(10L, 27205, "Inception");
        cached.setCachedAt(LocalDateTime.now());
        when(movieRepository.findByTmdbId(27205)).thenReturn(Optional.of(cached));

        Movie result = movieService.getMovieByTmdbId(27205);

        assertSame(cached, result);
        verifyNoInteractions(tmdbApiService);
    }

    @Test
    void getMovieDetailsDtoRefreshesMovieAndBuildsResponseDto() {
        MovieDetailDto tmdbData = TestFixtures.movieDetailDto(27205L, "Inception",
                List.of(new MovieDetailDto.Genre(28, "Action")));
        Genre action = TestFixtures.genre(2L, 28, "Action");
        when(movieRepository.findByTmdbId(27205)).thenReturn(Optional.empty());
        when(tmdbApiService.getMovieDetails(27205L)).thenReturn(tmdbData);
        when(movieRepository.save(any(Movie.class))).thenAnswer(invocation -> {
            Movie movie = invocation.getArgument(0);
            movie.setId(10L);
            return movie;
        });
        when(genreRepository.findByTmdbIdIn(List.of(28))).thenReturn(List.of(action));
        when(movieGenreRepository.findByMovieId(10L))
                .thenReturn(List.of(TestFixtures.movieGenre(TestFixtures.movie(10L, 27205, "Inception"), action)));

        MovieResponseDto result = movieService.getMovieDetailsDto(27205);

        assertNotNull(result);
        assertEquals(27205, result.getTmdbId());
        assertEquals("Inception", result.getMovieTitle());
        assertEquals(List.of("Action"), result.getGenres());
        verify(movieGenreRepository).flush();
    }

    @Test
    void searchMoviesPersistsReturnedResults() {
        MovieDto dto = TestFixtures.movieDto(100L, "Test Movie", List.of(12));
        SearchResponseDto response = new SearchResponseDto(1, List.of(dto), 1, 1);
        Genre genre = TestFixtures.genre(1L, 12, "Adventure");

        when(tmdbApiService.searchMovies("test", 1)).thenReturn(response);
        when(movieRepository.existsByTmdbId(100)).thenReturn(false);
        when(movieRepository.save(any(Movie.class))).thenAnswer(invocation -> {
            Movie movie = invocation.getArgument(0);
            movie.setId(5L);
            return movie;
        });
        when(genreRepository.findByTmdbIdIn(List.of(12))).thenReturn(List.of(genre));

        SearchResponseDto result = movieService.searchMovies("test", 1);

        assertEquals(1, result.getResults().size());
        verify(movieRepository).save(any(Movie.class));
        verify(movieGenreRepository).save(any(MovieGenre.class));
    }
}
