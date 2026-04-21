package com.atlasmind.ai_travel_recommendation.repository;

import com.atlasmind.ai_travel_recommendation.models.RecommendationImpression;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RecommendationImpressionRepository extends JpaRepository<RecommendationImpression, Long> {

    @Query("""
            SELECT impression.movie.id
            FROM RecommendationImpression impression
            WHERE impression.user.id = :userId
              AND impression.recommendedAt >= :since
            GROUP BY impression.movie.id
            HAVING COUNT(impression) >= :minimumCount
            """)
    List<Long> findMovieIdsWithAtLeastImpressionsSince(
            @Param("userId") Long userId,
            @Param("since") LocalDateTime since,
            @Param("minimumCount") long minimumCount
    );
}
