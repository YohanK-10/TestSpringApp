package com.atlasmind.atlaswatch.controller;

import com.atlasmind.atlaswatch.dto.response.RecommendationResponseDto;
import com.atlasmind.atlaswatch.exceptions.GlobalExceptionHandler;
import com.atlasmind.atlaswatch.service.RecommendationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RecommendationControllerWebTest {

    @Mock
    private RecommendationService recommendationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        RecommendationController controller = new RecommendationController(recommendationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(GlobalExceptionHandler.builder().build())
                .setValidator(validator)
                .build();
    }

    @Test
    void coldStartEndpointBindsQueryParamsAndReturnsRecommendationPayload() throws Exception {
        RecommendationResponseDto recommendation = RecommendationResponseDto.builder()
                .tmdbId(27205)
                .movieTitle("Inception")
                .genres(List.of("Thriller", "Science Fiction"))
                .onWatchlist(false)
                .reasons(List.of("It matches your tense vibe mix through Thriller."))
                .build();

        when(recommendationService.getColdStartRecommendations(argThat(request ->
                request.getLimit() != null
                        && request.getLimit() == 3
                        && "short".equals(request.getRuntimePreference())
                        && request.getReleaseEras().equals(List.of("1990s", "2000s"))
                        && request.getMoods() != null
                        && request.getMoods().equals(List.of("tense", "dark"))
                        && "rotation-a".equals(request.getRefreshToken())
                        && request.getStarterGenres() != null
                        && request.getStarterGenres().equals(List.of("thriller", "mystery"))
                        && request.getSeedTmdbIds() != null
                        && request.getSeedTmdbIds().equals(List.of(100, 200))
                        && request.getSeenTmdbIds() != null
                        && request.getSeenTmdbIds().equals(List.of(300, 400))
        ))).thenReturn(List.of(recommendation));

        mockMvc.perform(get("/api/recommendations/cold-start")
                        .param("moods", "tense", "dark")
                        .param("runtimePreference", "short")
                        .param("releaseEras", "1990s", "2000s")
                        .param("limit", "3")
                        .param("refreshToken", "rotation-a")
                        .param("starterGenres", "thriller", "mystery")
                        .param("seedTmdbIds", "100", "200")
                        .param("seenTmdbIds", "300", "400")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].tmdbId").value(27205))
                .andExpect(jsonPath("$[0].movieTitle").value("Inception"))
                .andExpect(jsonPath("$[0].onWatchlist").value(false))
                .andExpect(jsonPath("$[0].reasons", hasSize(1)));

        verify(recommendationService).getColdStartRecommendations(argThat(request ->
                request.getLimit() != null
                        && request.getLimit() == 3
                        && "short".equals(request.getRuntimePreference())
                        && request.getReleaseEras().equals(List.of("1990s", "2000s"))
                        && request.getMoods() != null
                        && request.getMoods().equals(List.of("tense", "dark"))
                        && "rotation-a".equals(request.getRefreshToken())
                        && request.getStarterGenres() != null
                        && request.getStarterGenres().equals(List.of("thriller", "mystery"))
                        && request.getSeedTmdbIds() != null
                        && request.getSeedTmdbIds().equals(List.of(100, 200))
                        && request.getSeenTmdbIds() != null
                        && request.getSeenTmdbIds().equals(List.of(300, 400))
        ));
    }

    @Test
    void coldStartEndpointReturnsBadRequestForInvalidLimit() throws Exception {
        mockMvc.perform(get("/api/recommendations/cold-start")
                        .param("limit", "11")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Recommendation limit cannot be more than 10."));

        verifyNoInteractions(recommendationService);
    }

    @Test
    void coldStartEndpointReturnsBadRequestForInvalidRuntimePreference() throws Exception {
        mockMvc.perform(get("/api/recommendations/cold-start")
                        .param("runtimePreference", "marathon")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("runtimePreference must be any, short, medium, or long."));

        verifyNoInteractions(recommendationService);
    }

    @Test
    void coldStartEndpointReturnsBadRequestForInvalidReleaseEra() throws Exception {
        mockMvc.perform(get("/api/recommendations/cold-start")
                        .param("releaseEras", "future")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "releaseEras entries must be any, pre-1980, 1980s, 1990s, 2000s, 2010s, or 2020s."
                ));

        verifyNoInteractions(recommendationService);
    }

    @Test
    void coldStartEndpointReturnsBadRequestForTooManyStarterGenres() throws Exception {
        mockMvc.perform(get("/api/recommendations/cold-start")
                        .param("starterGenres", "a", "b", "c", "d", "e", "f")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("starterGenres cannot contain more than 5 entries."));

        verifyNoInteractions(recommendationService);
    }

    @Test
    void coldStartEndpointReturnsBadRequestForTooManySeenMovies() throws Exception {
        String[] ids = java.util.stream.IntStream.rangeClosed(1, 51)
                .mapToObj(String::valueOf)
                .toArray(String[]::new);

        mockMvc.perform(get("/api/recommendations/cold-start")
                        .param("seenTmdbIds", ids)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("seenTmdbIds cannot contain more than 50 entries."));

        verifyNoInteractions(recommendationService);
    }

    @Test
    void soloEndpointReturnsBadRequestWhenLimitExceedsMaximum() throws Exception {
        mockMvc.perform(post("/api/recommendations/solo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moods\":[\"any\"],\"runtimePreference\":\"any\",\"limit\":99}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Recommendation limit cannot be more than 10."));

        verifyNoInteractions(recommendationService);
    }

    @Test
    void soloEndpointReturnsBadRequestForInvalidRuntimePreference() throws Exception {
        mockMvc.perform(post("/api/recommendations/solo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moods\":[\"any\"],\"runtimePreference\":\"epic\",\"limit\":5}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("runtimePreference must be any, short, medium, or long."));

        verifyNoInteractions(recommendationService);
    }
}

