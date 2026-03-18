package com.atlasmind.ai_travel_recommendation.repository;

import com.atlasmind.ai_travel_recommendation.models.Movie;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MovieRepository extends JpaRepository<Movie, Long> {

    /**
     * Find a movie by its TMDB ID.
     * This is the primary lookup method — when a user requests movie details,
     * we check "do we already have this movie cached locally?"
     */
    Optional<Movie> findByTmdbId(Integer tmdbId);

    /**
     * Check if a movie exists by TMDB ID without loading the full entity.
     * More efficient than findByTmdbId when you only need a yes/no answer.
     */
    boolean existsByTmdbId(Integer tmdbId);
}