package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.dto.request.CreateReviewDto;
import com.atlasmind.atlaswatch.dto.response.ReviewResponseDto;
import com.atlasmind.atlaswatch.dto.response.ReviewSummaryResponseDto;
import com.atlasmind.atlaswatch.exceptions.DuplicateResourceException;
import com.atlasmind.atlaswatch.models.Movie;
import com.atlasmind.atlaswatch.models.Review;
import com.atlasmind.atlaswatch.models.User;
import com.atlasmind.atlaswatch.repository.MovieRepository;
import com.atlasmind.atlaswatch.repository.ReviewRepository;
import com.atlasmind.atlaswatch.support.TestFixtures;
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
    @Mock
    private RecommendationCacheInvalidationService recommendationCacheInvalidationService;

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
        verify(recommendationCacheInvalidationService).evictForUser(1L);
    }

    @Test
    void getReviewsByMovieHidesRatingOnlyEntriesFromWrittenFeed() {
        Movie movie = TestFixtures.movie(5L, 27205, "Inception");
        User user = TestFixtures.user(1L, "alice", "alice@example.com");

        Review writtenReview = TestFixtures.review(10L, user, movie);
        Review ratingOnly = TestFixtures.review(11L, user, movie);
        ratingOnly.setReviewText("   ");

        when(movieRepository.findByTmdbId(27205)).thenReturn(Optional.of(movie));
        when(reviewRepository.findByMovieIdWithDetails(5L)).thenReturn(List.of(writtenReview, ratingOnly));

        List<ReviewResponseDto> result = reviewService.getReviewsByMovie(27205);

        assertEquals(1, result.size());
        assertEquals(10L, result.get(0).getId());
    }

    @Test
    void getReviewSummaryByMovieCountsRatingsAndWrittenReviewsSeparately() {
        Movie movie = TestFixtures.movie(5L, 27205, "Inception");
        User alice = TestFixtures.user(1L, "alice", "alice@example.com");
        User bob = TestFixtures.user(2L, "bob", "bob@example.com");

        Review writtenReview = TestFixtures.review(10L, alice, movie);
        writtenReview.setRating(9);
        writtenReview.setReviewText("Excellent");

        Review ratingOnly = TestFixtures.review(11L, bob, movie);
        ratingOnly.setRating(7);
        ratingOnly.setReviewText(null);

        when(movieRepository.findByTmdbId(27205)).thenReturn(Optional.of(movie));
        when(reviewRepository.findByMovieIdWithDetails(5L)).thenReturn(List.of(writtenReview, ratingOnly));

        ReviewSummaryResponseDto result = reviewService.getReviewSummaryByMovie(27205);

        assertEquals(2L, result.getTotalRatings());
        assertEquals(1L, result.getWrittenReviewCount());
        assertEquals(1L, result.getRatingDistribution().get(9));
        assertEquals(1L, result.getRatingDistribution().get(7));
        assertEquals(8.0, result.getAverageRating());
    }

    @Test
    void updateReviewInvalidatesRecommendationCache() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");
        Movie movie = TestFixtures.movie(5L, 27205, "Inception");
        CreateReviewDto dto = TestFixtures.createReviewDto(27205, 8);
        Review existing = TestFixtures.review(10L, user, movie);

        when(reviewRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(existing));
        when(reviewRepository.save(any(Review.class))).thenAnswer(invocation -> invocation.getArgument(0));

        reviewService.updateReview(user, 10L, dto);

        verify(recommendationCacheInvalidationService).evictForUser(1L);
    }

    @Test
    void deleteReviewInvalidatesRecommendationCache() {
        User user = TestFixtures.user(1L, "alice", "alice@example.com");
        Movie movie = TestFixtures.movie(5L, 27205, "Inception");
        Review existing = TestFixtures.review(10L, user, movie);

        when(reviewRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(existing));

        reviewService.deleteReview(user, 10L);

        verify(reviewRepository).delete(existing);
        verify(recommendationCacheInvalidationService).evictForUser(1L);
    }
}

