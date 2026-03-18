package com.atlasmind.ai_travel_recommendation.repository;

import com.atlasmind.ai_travel_recommendation.models.Genre;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GenreRepository extends JpaRepository<Genre, Long> {

    /**
     * Find a genre by its TMDB ID.
     * Used when saving a movie: TMDB gives us genre_ids like [28, 878],
     * we look up which Genre records match those TMDB IDs.
     */
    Optional<Genre> findByTmdbId(Integer tmdbId);

    /**
     * Find multiple genres by their TMDB IDs in one query.
     * More efficient than calling findByTmdbId in a loop.
     * For Inception with genre_ids [28, 878, 53], this returns
     * all 3 Genre objects in a single database query.
     */
    List<Genre> findByTmdbIdIn(List<Integer> tmdbIds);
}