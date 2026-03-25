package com.atlasmind.ai_travel_recommendation.repository;

import com.atlasmind.ai_travel_recommendation.models.Movie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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

    /**
     * Full-text search against the GIN-indexed tsvector column.
     *
     * plainto_tsquery converts a user's plain text input into a tsquery.
     * For example: "dark knight" → 'dark' & 'knight' (AND search).
     * This is safer than to_tsquery which requires exact syntax.
     *
     * ts_rank scores results by relevance — movies where the search
     * terms appear in the title rank higher than those where terms
     * only appear in the overview.
     *
     * The @@ operator means "matches the text search query" and is
     * what triggers PostgreSQL to use the GIN index.
     */
    @Query(value = """
            SELECT * FROM movie
            WHERE search_vector @@ plainto_tsquery('english', :query)
            ORDER BY ts_rank(search_vector, plainto_tsquery('english', :query)) DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<Movie> searchByFullText(@Param("query") String query,
                                 @Param("limit") int limit,
                                 @Param("offset") int offset);

    /**
     * Count total results for a full-text search (needed for pagination).
     */
    @Query(value = """
            SELECT COUNT(*) FROM movie
            WHERE search_vector @@ plainto_tsquery('english', :query)
            """, nativeQuery = true)
    long countByFullText(@Param("query") String query);
}