package com.atlasmind.ai_travel_recommendation.repository;

import com.atlasmind.ai_travel_recommendation.models.WatchList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WatchlistRepository extends JpaRepository<WatchList, Long> {

    /**
     * Check if a movie is already in a user's watchlist.
     */
    boolean existsByUserIdAndMovieId(Long userId, Long movieId);

    /**
     * Get a user's full watchlist with movie details loaded.
     * JOIN FETCH eliminates N+1 — one query loads watchlist entries
     * AND their associated movies.
     */
    @Query("SELECT w FROM WatchList w JOIN FETCH w.movie JOIN FETCH w.user WHERE w.user.id = :userId ORDER BY w.addedAt DESC")
    List<WatchList> findByUserIdWithDetails(@Param("userId") Long userId);

    /**
     * Find a specific watchlist entry with movie loaded.
     */
    @Query("SELECT w FROM WatchList w JOIN FETCH w.movie JOIN FETCH w.user WHERE w.id = :id")
    Optional<WatchList> findByIdWithDetails(@Param("id") Long id);
}