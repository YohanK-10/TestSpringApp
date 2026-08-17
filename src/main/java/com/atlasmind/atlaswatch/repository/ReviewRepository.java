package com.atlasmind.atlaswatch.repository;

import com.atlasmind.atlaswatch.models.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {

    /**
     * Check if a user has already reviewed a specific movie.
     * Used before creating a review to give a friendly 409 error
     * instead of letting the DB unique constraint throw an ugly exception.
     */
    boolean existsByUserIdAndMovieId(Long userId, Long movieId);

    /**
     * Find all reviews for a movie. Uses JOIN FETCH to load the User
     * and Movie entities in a SINGLE query instead of N+1 lazy loads.
     *
     * Without JOIN FETCH: 1 query for reviews + 1 query per review to load user
     * With JOIN FETCH: 1 query total (SQL JOIN)
     */
    @Query("SELECT r FROM Review r JOIN FETCH r.user JOIN FETCH r.movie WHERE r.movie.id = :movieId ORDER BY r.createdAt DESC")
    List<Review> findByMovieIdWithDetails(@Param("movieId") Long movieId);

    /**
     * Find all reviews by a user. Same JOIN FETCH pattern.
     */
    @Query("SELECT r FROM Review r JOIN FETCH r.user JOIN FETCH r.movie WHERE r.user.id = :userId ORDER BY r.createdAt DESC")
    List<Review> findByUserIdWithDetails(@Param("userId") Long userId);

    /**
     * Find a specific review with user and movie loaded.
     * Used for update/delete where we need to check ownership.
     */
    @Query("SELECT r FROM Review r JOIN FETCH r.user JOIN FETCH r.movie WHERE r.id = :reviewId")
    Optional<Review> findByIdWithDetails(@Param("reviewId") Long reviewId);

    @Query("SELECT r FROM Review r JOIN FETCH r.user JOIN FETCH r.movie WHERE r.user.id = :userId AND r.movie.id = :movieId")
    Optional<Review> findByUserIdAndMovieIdWithDetails(@Param("userId") Long userId, @Param("movieId") Long movieId);

}

