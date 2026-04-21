package com.atlasmind.ai_travel_recommendation.repository;

import com.atlasmind.ai_travel_recommendation.models.Movie;
import com.atlasmind.ai_travel_recommendation.models.MovieGenre;
import com.atlasmind.ai_travel_recommendation.models.MovieGenreId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MovieGenreRepository extends JpaRepository<MovieGenre, MovieGenreId> {

    /**
     * Find all genre links for a specific movie.
     * Used when building the movie response.
     */
    List<MovieGenre> findByMovieId(Long movieId);

    @Query("SELECT mg FROM MovieGenre mg JOIN FETCH mg.genre JOIN FETCH mg.movie WHERE mg.movie.id IN :movieIds")
    List<MovieGenre> findByMovieIdInWithGenre(@Param("movieIds") Collection<Long> movieIds);

    @Query("""
            SELECT DISTINCT mg.movie FROM MovieGenre mg
            WHERE LOWER(mg.genre.name) IN :genreNames
            ORDER BY mg.movie.popularity DESC, mg.movie.movieRating DESC, mg.movie.cachedAt DESC
            """)
    List<Movie> findDistinctMoviesByGenreNames(@Param("genreNames") Collection<String> genreNames, Pageable pageable);

    @Query("""
            SELECT DISTINCT mg.movie FROM MovieGenre mg
            WHERE LOWER(mg.genre.name) IN :genreNames
              AND mg.movie.movieRating IS NOT NULL
              AND mg.movie.movieRating >= :minimumRating
              AND mg.movie.runtime IS NOT NULL
              AND LENGTH(TRIM(COALESCE(mg.movie.posterPath, ''))) > 0
              AND mg.movie.releaseDate IS NOT NULL
              AND LENGTH(TRIM(COALESCE(mg.movie.overview, ''))) > 0
            ORDER BY mg.movie.popularity DESC, mg.movie.movieRating DESC, mg.movie.cachedAt DESC
            """)
    List<Movie> findDistinctRecommendationReadyMoviesByGenreNames(
            @Param("genreNames") Collection<String> genreNames,
            @Param("minimumRating") double minimumRating,
            Pageable pageable
    );

    @Query("""
            SELECT DISTINCT mg.movie FROM MovieGenre mg
            WHERE LOWER(mg.genre.name) IN :genreNames
              AND mg.movie.id NOT IN :excludedMovieIds
              AND mg.movie.movieRating IS NOT NULL
              AND mg.movie.movieRating >= :minimumRating
              AND mg.movie.runtime IS NOT NULL
              AND LENGTH(TRIM(COALESCE(mg.movie.posterPath, ''))) > 0
              AND mg.movie.releaseDate IS NOT NULL
              AND LENGTH(TRIM(COALESCE(mg.movie.overview, ''))) > 0
            ORDER BY mg.movie.popularity DESC, mg.movie.movieRating DESC, mg.movie.cachedAt DESC
            """)
    List<Movie> findDistinctRecommendationReadyMoviesByGenreNamesExcluding(
            @Param("genreNames") Collection<String> genreNames,
            @Param("minimumRating") double minimumRating,
            @Param("excludedMovieIds") Collection<Long> excludedMovieIds,
            Pageable pageable
    );

    /**
     * Delete all genre links for a movie.
     * Used when refreshing stale movie data.
     */
    void deleteByMovieId(Long movieId);
}
