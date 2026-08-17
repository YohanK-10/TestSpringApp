package com.atlasmind.atlaswatch.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
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
    @NotNull(message = "tmdbId is required.")
    @Positive(message = "tmdbId must be positive.")
    private Integer tmdbId;

    @Pattern(regexp = "(?i)^(PLAN_TO_WATCH|WATCHED|WATCHING)$", message = "Status must be PLAN_TO_WATCH or WATCHED.")
    private String status; // Optional: "PLAN_TO_WATCH" or "WATCHED"
}

