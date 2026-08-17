package com.atlasmind.atlaswatch.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What the client sends when creating or updating a review.
 *
 * Why no userId field? Because the user is identified from the JWT token
 * in the request cookie — the client can't choose who the review belongs to.
 * This prevents a user from creating reviews on behalf of someone else.
 *
 * Why tmdbId and not our internal movie id? Because the frontend only
 * knows about TMDB IDs (that's what we expose in MovieResponseDto).
 * The service layer resolves tmdbId → internal Movie entity.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateReviewDto {
    @NotNull(message = "tmdbId is required.")
    @Positive(message = "tmdbId must be positive.")
    private Integer tmdbId;

    @NotNull(message = "Rating is required.")
    @Min(value = 1, message = "Rating must be at least 1.")
    @Max(value = 10, message = "Rating cannot be greater than 10.")
    private Integer rating;

    @Size(max = 2000, message = "Review text cannot be longer than 2000 characters.")
    private String reviewText;
    private Boolean containsSpoilers;
}
