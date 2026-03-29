package com.atlasmind.ai_travel_recommendation.controller;

import com.atlasmind.ai_travel_recommendation.dto.response.MovieResponseDto;
import com.atlasmind.ai_travel_recommendation.exceptions.ResourceNotFoundException;
import com.atlasmind.ai_travel_recommendation.repository.GenreRepository;
import com.atlasmind.ai_travel_recommendation.repository.MovieGenreRepository;
import com.atlasmind.ai_travel_recommendation.service.MovieService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MovieControllerTest {

    @Mock
    private MovieService movieService;
    @Mock
    private GenreRepository genreRepository;
    @Mock
    private MovieGenreRepository movieGenreRepository;

    @InjectMocks
    private MovieController movieController;

    @Test
    void getMovieDetailsReturnsMovieResponse() {
        MovieResponseDto dto = MovieResponseDto.builder()
                .tmdbId(27205)
                .movieTitle("Inception")
                .movieOverview("Dreams within dreams")
                .releaseDate(LocalDate.of(2010, 7, 16))
                .rating(8.8)
                .runtime(148)
                .popularity(100.0)
                .genres(List.of("Action", "Sci-Fi"))
                .build();
        when(movieService.getMovieDetailsDto(27205)).thenReturn(dto);

        ResponseEntity<MovieResponseDto> response = movieController.getMovieDetails(27205);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Inception", response.getBody().getMovieTitle());
    }

    @Test
    void getMovieDetailsThrowsNotFoundWhenServiceReturnsNull() {
        when(movieService.getMovieDetailsDto(27205)).thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> movieController.getMovieDetails(27205));
    }
}
