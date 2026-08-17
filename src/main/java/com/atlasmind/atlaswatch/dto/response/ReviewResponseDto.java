package com.atlasmind.atlaswatch.dto.response;

import com.atlasmind.atlaswatch.models.Review;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * What the API returns for a review.
 *
 * Includes denormalized fields (username, movieTitle, tmdbId) so the
 * frontend doesn't need to make separate API calls to display a review.
 * This is a deliberate tradeoff: slightly larger response payload,
 * but eliminates N+1 API calls from the frontend.
 */
@Getter
@AllArgsConstructor
@Builder
public class ReviewResponseDto {

    private final Long id;
    private final Integer tmdbId;
    private final String movieTitle;
    private final String username;
    private final Integer rating;
    private final String reviewText;
    private final Boolean hasReviewText;
    private final Boolean containsSpoilers;
    private final Boolean edited;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public static ReviewResponseDto fromReview(Review review) {
        boolean hasReviewText = review.getReviewText() != null && !review.getReviewText().trim().isEmpty();
        boolean edited = review.getUpdatedAt() != null && !review.getUpdatedAt().isEqual(review.getCreatedAt());

        return ReviewResponseDto.builder()
                .id(review.getId())
                .tmdbId(review.getMovie().getTmdbId())
                .movieTitle(review.getMovie().getMovieTitle())
                .username(review.getUser().getUsername())
                .rating(review.getRating())
                .reviewText(review.getReviewText())
                .hasReviewText(hasReviewText)
                .containsSpoilers(review.getContainsSpoilers())
                .edited(edited)
                .createdAt(review.getCreatedAt())
                .updatedAt(review.getUpdatedAt())
                .build();
    }
}

