package com.atlasmind.atlaswatch.controller;

import com.atlasmind.atlaswatch.dto.request.AddToWatchlistDto;
import com.atlasmind.atlaswatch.dto.response.WatchlistResponseDto;
import com.atlasmind.atlaswatch.models.User;
import com.atlasmind.atlaswatch.service.WatchlistService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for watchlist management.
 * ALL endpoints require authentication — a watchlist is private.
 */
@RestController
@RequestMapping("/api/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService watchlistService;

    /**
     * POST /api/watchlist
     * Add a movie to the current user's watchlist.
     */
    @PostMapping
    public ResponseEntity<WatchlistResponseDto> addToWatchlist(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody AddToWatchlistDto dto) {

        WatchlistResponseDto entry = watchlistService.addToWatchlist(user, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(entry);
    }

    /**
     * GET /api/watchlist
     * Get the current user's full watchlist.
     */
    @GetMapping
    public ResponseEntity<List<WatchlistResponseDto>> getWatchlist(
            @AuthenticationPrincipal User user) {

        return ResponseEntity.ok(watchlistService.getWatchlist(user));
    }

    /**
     * PUT /api/watchlist/{id}/status
     * Update the status of a watchlist entry.
     * Body: { "status": "PLAN_TO_WATCH" }
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<WatchlistResponseDto> updateStatus(
            @AuthenticationPrincipal User user,
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        String newStatus = body.get("status");
        WatchlistResponseDto updated = watchlistService.updateStatus(user, id, newStatus);
        return ResponseEntity.ok(updated);
    }

    /**
     * DELETE /api/watchlist/{id}
     * Remove a movie from the watchlist.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> removeFromWatchlist(
            @AuthenticationPrincipal User user,
            @PathVariable Long id) {

        watchlistService.removeFromWatchlist(user, id);
        return ResponseEntity.noContent().build();
    }
}

