package com.atlasmind.ai_travel_recommendation.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What the client sends when adding a movie to their watchlist.
 * Status is optional — defaults to PLAN_TO_WATCH if not provided.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AddToWatchlistDto {
    private Integer tmdbId;
    private String status; // Optional: "PLAN_TO_WATCH", "WATCHING", "WATCHED"
}