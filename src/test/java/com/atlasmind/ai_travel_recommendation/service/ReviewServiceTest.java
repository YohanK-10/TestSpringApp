package com.atlasmind.ai_travel_recommendation.service;

import com.atlasmind.ai_travel_recommendation.dto.request.CreateReviewDto;
import com.atlasmind.ai_travel_recommendation.dto.response.ReviewResponseDto;
import com.atlasmind.ai_travel_recommendation.exceptions.DuplicateResourceException;
import com.atlasmind.ai_travel_recommendation.models.Movie;
import com.atlasmind.ai_travel_recommendation.models.Review;
import com.atlasmind.ai_travel_recommendation.models.User;
import com.atlasmind.ai_travel_recommendation.repository.MovieRepository;
import com.atlasmind.ai_travel_recommendation.repository.ReviewRepository;
import com.atlasmind.ai_travel_recommendation.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private MovieRepository movieRepository;

    @InjectMocks
    private ReviewService reviewService;

    @Test
    void getReviewsByMovieReturnsEmptyListWhenMovieIsMissing() {
        when(movieRepository.findByTmdbId(27205)).thenReturn(Optional.empty());

        List<ReviewResponseDto> result = reviewService.getReviewsByMovie(27205);

        assertTrue(result.isEmpty());
        verifyNoInteractions(reviewRepository);
    }

    @Test
    void createReviewRejectsDuplicateReview() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");
        Movie movie = TestFixtures.movie(5L, 27205, "Inception");
        CreateReviewDto dto = TestFixtures.createReviewDto(27205, 9);

        when(movieRepository.findByTmdbId(27205)).thenReturn(Optional.of(movie));
        when(reviewRepository.existsByUserIdAndMovieId(1L, 5L)).thenReturn(true);

        assertThrows(DuplicateResourceException.class, () -> reviewService.createReview(user, dto));
        verify(reviewRepository, never()).save(any(Review.class));
    }

    @Test
    void createReviewSavesReviewWhenValid() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");
        Movie movie = TestFixtures.movie(5L, 27205, "Inception");
        CreateReviewDto dto = TestFixtures.createReviewDto(27205, 9);

        when(movieRepository.findByTmdbId(27205)).thenReturn(Optional.of(movie));
        when(reviewRepository.existsByUserIdAndMovieId(1L, 5L)).thenReturn(false);
        when(reviewRepository.save(any(Review.class))).thenAnswer(invocation -> {
            Review review = invocation.getArgument(0);
            review.setId(44L);
            review.setCreatedAt(java.time.LocalDateTime.now());
            return review;
        });

        ReviewResponseDto result = reviewService.createReview(user, dto);

        assertEquals(44L, result.getId());
        assertEquals("alice", result.getUsername());
        assertEquals("Inception", result.getMovieTitle());
    }
}
