package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.dto.request.CreateReviewDto;
import com.atlasmind.atlaswatch.dto.response.ReviewResponseDto;
import com.atlasmind.atlaswatch.dto.response.ReviewSummaryResponseDto;
import com.atlasmind.atlaswatch.exceptions.DuplicateResourceException;
import com.atlasmind.atlaswatch.exceptions.ResourceNotFoundException;
import com.atlasmind.atlaswatch.models.Movie;
import com.atlasmind.atlaswatch.models.Review;
import com.atlasmind.atlaswatch.models.User;
import com.atlasmind.atlaswatch.repository.MovieRepository;
import com.atlasmind.atlaswatch.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final MovieRepository movieRepository;
    private final RecommendationCacheInvalidationService recommendationCacheInvalidationService;

    /**
     * Create a new review for a movie.
     *
     * Why does this take a User object instead of just a userId?
     * Because the controller gets the full User from the SecurityContext
     * (it's the authenticated principal). Passing the object avoids an
     * extra database query to look up the user by ID.
     */
    @Transactional
    public ReviewResponseDto createReview(User user, CreateReviewDto dto) {
        // Validate rating range
        if (dto.getRating() < 1 || dto.getRating() > 10) {
            throw new IllegalArgumentException("Rating must be between 1 and 10");
        }

        // Find the movie in our local database by TMDB ID
        Movie movie = movieRepository.findByTmdbId(dto.getTmdbId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Movie", "tmdbId", dto.getTmdbId().toString()));

        // Check for duplicate review (friendly error before DB constraint fires)
        if (reviewRepository.existsByUserIdAndMovieId(user.getId(), movie.getId())) {
            throw new DuplicateResourceException(
                    "You have already reviewed this movie. Use PUT to update your review.");
        }

        // Build and save the review
        Review review = new Review();
        review.setUser(user);
        review.setMovie(movie);
        review.setRating(dto.getRating());
        String normalizedReviewText = normalizeReviewText(dto.getReviewText());
        review.setReviewText(normalizedReviewText);
        review.setContainsSpoilers(hasWrittenReview(normalizedReviewText) &&
                dto.getContainsSpoilers() != null && dto.getContainsSpoilers());

        Review saved = reviewRepository.save(review);
        recommendationCacheInvalidationService.evictForUser(user.getId());
        log.info("User {} reviewed movie tmdbId={} with rating {}",
                user.getUsername(), dto.getTmdbId(), dto.getRating());

        return ReviewResponseDto.fromReview(saved);
    }

    /**
     * Get all reviews for a specific movie.
     */
    @Transactional(readOnly = true)
    public List<ReviewResponseDto> getReviewsByMovie(Integer tmdbId) {
        Movie movie = movieRepository.findByTmdbId(tmdbId).orElse(null);

        if (movie == null) {
            return List.of();
        }

        return reviewRepository.findByMovieIdWithDetails(movie.getId())
                .stream()
                .filter(review -> hasWrittenReview(review.getReviewText()))
                .map(ReviewResponseDto::fromReview)
                .toList();
    }

    @Transactional(readOnly = true)
    public ReviewSummaryResponseDto getReviewSummaryByMovie(Integer tmdbId) {
        Movie movie = movieRepository.findByTmdbId(tmdbId).orElse(null);

        if (movie == null) {
            return emptySummary();
        }

        List<Review> allEntries = reviewRepository.findByMovieIdWithDetails(movie.getId());
        if (allEntries.isEmpty()) {
            return emptySummary();
        }

        Map<Integer, Long> distribution = allEntries.stream()
                .filter(review -> review.getRating() != null)
                .collect(Collectors.groupingBy(Review::getRating, Collectors.counting()));

        long totalRatings = allEntries.stream()
                .filter(review -> review.getRating() != null)
                .count();

        long writtenReviewCount = allEntries.stream()
                .filter(review -> hasWrittenReview(review.getReviewText()))
                .count();

        Double averageRating = totalRatings == 0
                ? null
                : allEntries.stream()
                .filter(review -> review.getRating() != null)
                .mapToInt(Review::getRating)
                .average()
                .orElse(0.0);

        return ReviewSummaryResponseDto.builder()
                .averageRating(averageRating)
                .totalRatings(totalRatings)
                .writtenReviewCount(writtenReviewCount)
                .ratingDistribution(distribution)
                .build();
    }

    @Transactional(readOnly = true)
    public Optional<ReviewResponseDto> getUserReviewByMovie(User user, Integer tmdbId) {
        if (user == null) {
            return Optional.empty();
        }

        Movie movie = movieRepository.findByTmdbId(tmdbId).orElse(null);
        if (movie == null) {
            return Optional.empty();
        }

        return reviewRepository.findByUserIdAndMovieIdWithDetails(user.getId(), movie.getId())
                .map(ReviewResponseDto::fromReview);
    }

    /**
     * Get all reviews by a specific user.
     */
    @Transactional(readOnly = true)
    public List<ReviewResponseDto> getReviewsByUser(Long userId) {
        return reviewRepository.findByUserIdWithDetails(userId)
                .stream()
                .map(ReviewResponseDto::fromReview)
                .toList();
    }

    /**
     * Update an existing review. Only the review owner can do this.
     *
     * The authorization check (review.user == currentUser) happens HERE
     * in the service layer, not in the controller. Why? Because authorization
     * is a business rule ("only the owner can edit"), not an HTTP concern.
     * If another part of the code (like an admin batch process) calls this
     * method, the check still applies.
     */
    @Transactional
    public ReviewResponseDto updateReview(User user, Long reviewId, CreateReviewDto dto) {
        Review review = reviewRepository.findByIdWithDetails(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Review", "id", reviewId.toString()));

        // Authorization: only the owner can update
        if (!review.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("You can only edit your own reviews");
        }

        // Validate rating
        if (dto.getRating() < 1 || dto.getRating() > 10) {
            throw new IllegalArgumentException("Rating must be between 1 and 10");
        }

        // Update fields
        review.setRating(dto.getRating());
        String normalizedReviewText = normalizeReviewText(dto.getReviewText());
        review.setReviewText(normalizedReviewText);
        review.setContainsSpoilers(hasWrittenReview(normalizedReviewText) &&
                dto.getContainsSpoilers() != null && dto.getContainsSpoilers());

        Review saved = reviewRepository.save(review);
        recommendationCacheInvalidationService.evictForUser(user.getId());
        return ReviewResponseDto.fromReview(saved);
    }

    /**
     * Delete a review. Only the review owner can do this.
     */
    @Transactional
    public void deleteReview(User user, Long reviewId) {
        Review review = reviewRepository.findByIdWithDetails(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Review", "id", reviewId.toString()));

        if (!review.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("You can only delete your own reviews");
        }

        reviewRepository.delete(review);
        recommendationCacheInvalidationService.evictForUser(user.getId());
        log.info("User {} deleted review {}", user.getUsername(), reviewId);
    }

    private boolean hasWrittenReview(String reviewText) {
        return reviewText != null && !reviewText.trim().isEmpty();
    }

    private String normalizeReviewText(String reviewText) {
        if (reviewText == null) {
            return null;
        }

        String normalized = reviewText.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private ReviewSummaryResponseDto emptySummary() {
        return ReviewSummaryResponseDto.builder()
                .averageRating(null)
                .totalRatings(0L)
                .writtenReviewCount(0L)
                .ratingDistribution(Map.of())
                .build();
    }
}

