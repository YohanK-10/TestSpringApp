package com.atlasmind.ai_travel_recommendation.repository;

import com.atlasmind.ai_travel_recommendation.models.MovieGenre;
import com.atlasmind.ai_travel_recommendation.models.MovieGenreId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MovieGenreRepository extends JpaRepository<MovieGenre, MovieGenreId> {

    /**
     * Find all genre links for a specific movie.
     * Used when building the movie response — "what genres does this movie have?"
     */
    List<MovieGenre> findByMovieId(Long movieId);

    /**
     * Delete all genre links for a movie.
     * Used when refreshing stale movie data — delete old genres, insert new ones.
     */
    void deleteByMovieId(Long movieId);
}