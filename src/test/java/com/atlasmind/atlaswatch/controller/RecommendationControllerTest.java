package com.atlasmind.atlaswatch.controller;

import com.atlasmind.atlaswatch.dto.request.RecommendationRequestDto;
import com.atlasmind.atlaswatch.dto.request.SoloRecommendationRequestDto;
import com.atlasmind.atlaswatch.dto.response.RecommendationResponseDto;
import com.atlasmind.atlaswatch.dto.response.SoloRecommendationResponseDto;
import com.atlasmind.atlaswatch.models.User;
import com.atlasmind.atlaswatch.service.RecommendationService;
import com.atlasmind.atlaswatch.support.TestFixtures;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("deprecation")
class RecommendationControllerTest {

    @Mock
    private RecommendationService recommendationService;

    @InjectMocks
    private RecommendationController recommendationController;

    @Test
    void getRecommendationsReturnsOkResponse() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");
        RecommendationRequestDto request = new RecommendationRequestDto(List.of("tense"), "short", 3);
        RecommendationResponseDto recommendation = RecommendationResponseDto.builder()
                .tmdbId(27205)
                .movieTitle("Inception")
                .onWatchlist(true)
                .watchlistStatus("PLAN_TO_WATCH")
                .reasons(List.of("It matches your tense vibe mix through Thriller."))
                .build();

        when(recommendationService.getRecommendations(user, request)).thenReturn(List.of(recommendation));

        ResponseEntity<List<RecommendationResponseDto>> response =
                recommendationController.getRecommendations(user, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals(27205, response.getBody().get(0).getTmdbId());
    }

    @Test
    void getColdStartRecommendationsReturnsOkResponse() {
        RecommendationRequestDto request = new RecommendationRequestDto(List.of("any"), "any", 5);
        request.setRefreshToken("rotation-a");
        request.setStarterGenres(List.of("thriller"));
        RecommendationResponseDto recommendation = RecommendationResponseDto.builder()
                .tmdbId(155)
                .movieTitle("The Dark Knight")
                .onWatchlist(false)
                .reasons(List.of("It has one of the stronger audience ratings in the wider catalog."))
                .build();

        when(recommendationService.getColdStartRecommendations(request)).thenReturn(List.of(recommendation));

        ResponseEntity<List<RecommendationResponseDto>> response =
                recommendationController.getColdStartRecommendations(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals(155, response.getBody().get(0).getTmdbId());
    }

    @Test
    void getSoloRecommendationsReturnsOkResponse() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");
        SoloRecommendationRequestDto request = new SoloRecommendationRequestDto();
        request.setMoods(List.of("tense"));
        request.setRuntimePreference("short");
        request.setLimit(3);
        SoloRecommendationResponseDto recommendation = SoloRecommendationResponseDto.builder()
                .tmdbId(27205)
                .movieTitle("Inception")
                .score(84)
                .reasons(List.of("It matches your tense vibe mix through Thriller."))
                .build();

        when(recommendationService.getSoloRecommendations(user, request))
                .thenReturn(List.of(recommendation));

        ResponseEntity<List<SoloRecommendationResponseDto>> response =
                recommendationController.getSoloRecommendations(user, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals(27205, response.getBody().get(0).getTmdbId());
    }
}

