package com.atlasmind.ai_travel_recommendation.service;

import com.atlasmind.ai_travel_recommendation.dto.request.AddToWatchlistDto;
import com.atlasmind.ai_travel_recommendation.dto.response.WatchlistResponseDto;
import com.atlasmind.ai_travel_recommendation.exceptions.DuplicateResourceException;
import com.atlasmind.ai_travel_recommendation.exceptions.ResourceNotFoundException;
import com.atlasmind.ai_travel_recommendation.models.Movie;
import com.atlasmind.ai_travel_recommendation.models.User;
import com.atlasmind.ai_travel_recommendation.models.WatchList;
import com.atlasmind.ai_travel_recommendation.models.WatchListStatus;
import com.atlasmind.ai_travel_recommendation.repository.MovieRepository;
import com.atlasmind.ai_travel_recommendation.repository.WatchlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WatchlistService {

    private final WatchlistRepository watchlistRepository;
    private final MovieRepository movieRepository;

    /**
     * Add a movie to the user's watchlist.
     * Defaults to PLAN_TO_WATCH if no status is provided.
     */
    @Transactional
    public WatchlistResponseDto addToWatchlist(User user, AddToWatchlistDto dto) {
        Movie movie = movieRepository.findByTmdbId(dto.getTmdbId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Movie", "tmdbId", dto.getTmdbId().toString()));

        if (watchlistRepository.existsByUserIdAndMovieId(user.getId(), movie.getId())) {
            throw new DuplicateResourceException(
                    "This movie is already in your watchlist.");
        }

        // Parse status with a safe default
        WatchListStatus status = parseStatus(dto.getStatus());

        WatchList entry = new WatchList();
        entry.setUser(user);
        entry.setMovie(movie);
        entry.setStatus(status);

        WatchList saved = watchlistRepository.save(entry);
        log.info("User {} added tmdbId={} to watchlist as {}",
                user.getUsername(), dto.getTmdbId(), status);

        return WatchlistResponseDto.fromWatchlist(saved);
    }

    /**
     * Get the current user's watchlist.
     */
    @Transactional(readOnly = true)
    public List<WatchlistResponseDto> getWatchlist(User user) {
        return watchlistRepository.findByUserIdWithDetails(user.getId())
                .stream()
                .map(WatchlistResponseDto::fromWatchlist)
                .toList();
    }

    /**
     * Update the status of a watchlist entry.
     * Only the owner can update their own watchlist entries.
     *
     * This is the state machine: PLAN_TO_WATCH → WATCHING → WATCHED
     * We don't enforce strict ordering (user can go directly from
     * PLAN_TO_WATCH to WATCHED). Why? Because users rewatch movies,
     * change their minds, etc. Strict ordering would frustrate users
     * without adding real value. The states are for user organization,
     * not business process enforcement.
     */
    @Transactional
    public WatchlistResponseDto updateStatus(User user, Long watchlistId, String newStatus) {
        WatchList entry = watchlistRepository.findByIdWithDetails(watchlistId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Watchlist entry", "id", watchlistId.toString()));

        if (!entry.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("You can only update your own watchlist");
        }

        WatchListStatus status = parseStatus(newStatus);
        entry.setStatus(status);

        WatchList saved = watchlistRepository.save(entry);
        return WatchlistResponseDto.fromWatchlist(saved);
    }

    /**
     * Remove a movie from the user's watchlist.
     */
    @Transactional
    public void removeFromWatchlist(User user, Long watchlistId) {
        WatchList entry = watchlistRepository.findByIdWithDetails(watchlistId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Watchlist entry", "id", watchlistId.toString()));

        if (!entry.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("You can only remove from your own watchlist");
        }

        watchlistRepository.delete(entry);
        log.info("User {} removed watchlist entry {}", user.getUsername(), watchlistId);
    }

    /**
     * Parse a status string into the enum, defaulting to PLAN_TO_WATCH.
     *
     * Why parse manually instead of accepting the enum directly in the DTO?
     * Because if the client sends "BINGE_WATCHING", Jackson deserialization
     * fails with an ugly 400 error before our code runs. By accepting a
     * String and parsing here, we control the error message.
     */
    private WatchListStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return WatchListStatus.PLAN_TO_WATCH;
        }
        try {
            return WatchListStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid status: '" + status + "'. Must be PLAN_TO_WATCH, WATCHING, or WATCHED");
        }
    }
}