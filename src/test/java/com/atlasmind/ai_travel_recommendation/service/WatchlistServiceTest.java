package com.atlasmind.ai_travel_recommendation.service;

import com.atlasmind.ai_travel_recommendation.dto.request.AddToWatchlistDto;
import com.atlasmind.ai_travel_recommendation.dto.response.WatchlistResponseDto;
import com.atlasmind.ai_travel_recommendation.models.Movie;
import com.atlasmind.ai_travel_recommendation.models.User;
import com.atlasmind.ai_travel_recommendation.models.WatchList;
import com.atlasmind.ai_travel_recommendation.models.WatchListStatus;
import com.atlasmind.ai_travel_recommendation.repository.MovieRepository;
import com.atlasmind.ai_travel_recommendation.repository.WatchlistRepository;
import com.atlasmind.ai_travel_recommendation.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WatchlistServiceTest {

    @Mock
    private WatchlistRepository watchlistRepository;
    @Mock
    private MovieRepository movieRepository;

    @InjectMocks
    private WatchlistService watchlistService;

    @Test
    void addToWatchlistDefaultsToPlanToWatch() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");
        Movie movie = TestFixtures.movie(5L, 27205, "Inception");
        AddToWatchlistDto dto = TestFixtures.addToWatchlistDto(27205, null);

        when(movieRepository.findByTmdbId(27205)).thenReturn(Optional.of(movie));
        when(watchlistRepository.existsByUserIdAndMovieId(1L, 5L)).thenReturn(false);
        when(watchlistRepository.save(any(WatchList.class))).thenAnswer(invocation -> {
            WatchList watchList = invocation.getArgument(0);
            watchList.setId(77L);
            watchList.setAddedAt(java.time.LocalDateTime.now());
            return watchList;
        });

        WatchlistResponseDto result = watchlistService.addToWatchlist(user, dto);

        assertEquals("PLAN_TO_WATCH", result.getStatus());
        assertEquals(27205, result.getTmdbId());
    }

    @Test
    void updateStatusRejectsNonOwner() {
        User owner = TestFixtures.user(1L, "alice", "alice@example.com");
        User other = TestFixtures.user(2L, "bob", "bob@example.com");
        Movie movie = TestFixtures.movie(5L, 27205, "Inception");
        WatchList watchList = TestFixtures.watchList(77L, owner, movie, WatchListStatus.PLAN_TO_WATCH);

        when(watchlistRepository.findByIdWithDetails(77L)).thenReturn(Optional.of(watchList));

        assertThrows(AccessDeniedException.class,
                () -> watchlistService.updateStatus(other, 77L, "WATCHING"));
    }

    @Test
    void updateStatusPersistsNewStatus() {
        User owner = TestFixtures.user(1L, "alice", "alice@example.com");
        Movie movie = TestFixtures.movie(5L, 27205, "Inception");
        WatchList watchList = TestFixtures.watchList(77L, owner, movie, WatchListStatus.PLAN_TO_WATCH);

        when(watchlistRepository.findByIdWithDetails(77L)).thenReturn(Optional.of(watchList));
        when(watchlistRepository.save(any(WatchList.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WatchlistResponseDto result = watchlistService.updateStatus(owner, 77L, "WATCHED");

        assertEquals("WATCHED", result.getStatus());
        verify(watchlistRepository).save(watchList);
    }
}
