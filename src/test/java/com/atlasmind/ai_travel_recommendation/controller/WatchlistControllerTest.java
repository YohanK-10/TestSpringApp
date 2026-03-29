package com.atlasmind.ai_travel_recommendation.controller;

import com.atlasmind.ai_travel_recommendation.dto.request.AddToWatchlistDto;
import com.atlasmind.ai_travel_recommendation.dto.response.WatchlistResponseDto;
import com.atlasmind.ai_travel_recommendation.models.User;
import com.atlasmind.ai_travel_recommendation.service.WatchlistService;
import com.atlasmind.ai_travel_recommendation.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WatchlistControllerTest {

    @Mock
    private WatchlistService watchlistService;

    @InjectMocks
    private WatchlistController watchlistController;

    @Test
    void addToWatchlistReturnsCreatedResponse() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");
        AddToWatchlistDto dto = new AddToWatchlistDto(27205, null);
        WatchlistResponseDto responseDto = WatchlistResponseDto.builder()
                .id(10L)
                .tmdbId(27205)
                .movieTitle("Inception")
                .posterPath("/poster.jpg")
                .status("PLAN_TO_WATCH")
                .addedAt(LocalDateTime.now())
                .build();
        when(watchlistService.addToWatchlist(user, dto)).thenReturn(responseDto);

        ResponseEntity<WatchlistResponseDto> response = watchlistController.addToWatchlist(user, dto);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("PLAN_TO_WATCH", response.getBody().getStatus());
    }

    @Test
    void getWatchlistReturnsEntries() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");
        WatchlistResponseDto responseDto = WatchlistResponseDto.builder()
                .id(10L)
                .tmdbId(27205)
                .movieTitle("Inception")
                .posterPath("/poster.jpg")
                .status("PLAN_TO_WATCH")
                .addedAt(LocalDateTime.now())
                .build();
        when(watchlistService.getWatchlist(user)).thenReturn(List.of(responseDto));

        ResponseEntity<List<WatchlistResponseDto>> response = watchlistController.getWatchlist(user);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void updateStatusReturnsUpdatedEntry() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");
        WatchlistResponseDto responseDto = WatchlistResponseDto.builder()
                .id(10L)
                .tmdbId(27205)
                .movieTitle("Inception")
                .posterPath("/poster.jpg")
                .status("WATCHED")
                .addedAt(LocalDateTime.now())
                .build();
        when(watchlistService.updateStatus(user, 10L, "WATCHED")).thenReturn(responseDto);

        ResponseEntity<WatchlistResponseDto> response =
                watchlistController.updateStatus(user, 10L, Map.of("status", "WATCHED"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("WATCHED", response.getBody().getStatus());
    }
}
