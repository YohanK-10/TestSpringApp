package com.atlasmind.ai_travel_recommendation.integration;

import com.atlasmind.ai_travel_recommendation.config.JwtAuthFilter;
import com.atlasmind.ai_travel_recommendation.config.SecurityConfiguration;
import com.atlasmind.ai_travel_recommendation.controller.MovieController;
import com.atlasmind.ai_travel_recommendation.controller.ReviewController;
import com.atlasmind.ai_travel_recommendation.dto.response.MovieResponseDto;
import com.atlasmind.ai_travel_recommendation.dto.response.ReviewResponseDto;
import com.atlasmind.ai_travel_recommendation.dto.tmdb.MovieDto;
import com.atlasmind.ai_travel_recommendation.dto.tmdb.SearchResponseDto;
import com.atlasmind.ai_travel_recommendation.repository.GenreRepository;
import com.atlasmind.ai_travel_recommendation.repository.MovieGenreRepository;
import com.atlasmind.ai_travel_recommendation.service.MovieService;
import com.atlasmind.ai_travel_recommendation.service.ReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {MovieController.class, ReviewController.class},
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class},
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthFilter.class, SecurityConfiguration.class}
        )
)
class MovieFlowWebIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MovieService movieService;
    @MockitoBean
    private ReviewService reviewService;
    @MockitoBean
    private GenreRepository genreRepository;
    @MockitoBean
    private MovieGenreRepository movieGenreRepository;

    @Test
    void coreMovieFlowSearchDetailsAndReviewsWorksOverHttp() throws Exception {
        SearchResponseDto searchResponse = new SearchResponseDto(
                1,
                List.of(new MovieDto(27205L, "Inception", "Dreams within dreams", 99.0,
                        "/poster.jpg", "/backdrop.jpg", "2010-07-16", 8.8, List.of(28, 878))),
                1,
                1
        );
        MovieResponseDto detailsResponse = MovieResponseDto.builder()
                .tmdbId(27205)
                .movieTitle("Inception")
                .movieOverview("Dreams within dreams")
                .releaseDate(LocalDate.of(2010, 7, 16))
                .posterPath("/poster.jpg")
                .backdropPath("/backdrop.jpg")
                .rating(8.8)
                .runtime(148)
                .popularity(99.0)
                .genres(List.of("Action", "Sci-Fi"))
                .build();
        ReviewResponseDto reviewResponse = ReviewResponseDto.builder()
                .id(1L)
                .tmdbId(27205)
                .movieTitle("Inception")
                .username("alice")
                .rating(9)
                .reviewText("Excellent")
                .containsSpoilers(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(movieService.searchMovies("inception", 1)).thenReturn(searchResponse);
        when(movieService.getMovieDetailsDto(27205)).thenReturn(detailsResponse);
        when(reviewService.getReviewsByMovie(27205)).thenReturn(List.of(reviewResponse));

        mockMvc.perform(get("/api/movies/search")
                        .param("query", "inception")
                        .param("page", "1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].id").value(27205))
                .andExpect(jsonPath("$.results[0].title").value("Inception"));

        mockMvc.perform(get("/api/movies/27205").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tmdbId").value(27205))
                .andExpect(jsonPath("$.movieTitle").value("Inception"))
                .andExpect(jsonPath("$.genres[0]").value("Action"));

        mockMvc.perform(get("/api/reviews/movie/27205").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].movieTitle").value("Inception"))
                .andExpect(jsonPath("$[0].username").value("alice"));
    }
}
