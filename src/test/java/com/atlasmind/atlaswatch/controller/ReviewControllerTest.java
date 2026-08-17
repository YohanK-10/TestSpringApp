package com.atlasmind.atlaswatch.controller;

import com.atlasmind.atlaswatch.dto.request.CreateReviewDto;
import com.atlasmind.atlaswatch.dto.response.ReviewResponseDto;
import com.atlasmind.atlaswatch.dto.response.ReviewSummaryResponseDto;
import com.atlasmind.atlaswatch.models.User;
import com.atlasmind.atlaswatch.service.ReviewService;
import com.atlasmind.atlaswatch.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewControllerTest {

    @Mock
    private ReviewService reviewService;

    @InjectMocks
    private ReviewController reviewController;

    @Test
    void createReviewReturnsCreatedResponse() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");
        CreateReviewDto dto = new CreateReviewDto(27205, 9, "Great", false);
        ReviewResponseDto responseDto = ReviewResponseDto.builder()
                .id(10L)
                .tmdbId(27205)
                .movieTitle("Inception")
                .username("alice")
                .rating(9)
                .reviewText("Great")
                .hasReviewText(true)
                .containsSpoilers(false)
                .edited(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        when(reviewService.createReview(user, dto)).thenReturn(responseDto);

        ResponseEntity<ReviewResponseDto> response = reviewController.createReview(user, dto);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(10L, response.getBody().getId());
    }

    @Test
    void getReviewsByMovieReturnsList() {
        ReviewResponseDto responseDto = ReviewResponseDto.builder()
                .id(10L)
                .tmdbId(27205)
                .movieTitle("Inception")
                .username("alice")
                .rating(9)
                .reviewText("Great")
                .hasReviewText(true)
                .containsSpoilers(false)
                .edited(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        when(reviewService.getReviewsByMovie(27205)).thenReturn(List.of(responseDto));

        ResponseEntity<List<ReviewResponseDto>> response = reviewController.getReviewsByMovie(27205);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
    }

    @Test
    void getReviewSummaryByMovieReturnsSummary() {
        ReviewSummaryResponseDto summary = ReviewSummaryResponseDto.builder()
                .averageRating(8.5)
                .totalRatings(4L)
                .writtenReviewCount(2L)
                .ratingDistribution(java.util.Map.of(8, 2L, 9, 2L))
                .build();
        when(reviewService.getReviewSummaryByMovie(27205)).thenReturn(summary);

        ResponseEntity<ReviewSummaryResponseDto> response = reviewController.getReviewSummaryByMovie(27205);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(4L, response.getBody().getTotalRatings());
    }
}

