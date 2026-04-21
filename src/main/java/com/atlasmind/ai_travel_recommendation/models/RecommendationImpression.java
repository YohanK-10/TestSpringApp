package com.atlasmind.ai_travel_recommendation.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "recommendation_impression",
        indexes = {
                @Index(name = "idx_recommendation_impression_user_time", columnList = "user_id,recommended_at"),
                @Index(name = "idx_recommendation_impression_user_movie_time", columnList = "user_id,movie_id,recommended_at")
        }
)
public class RecommendationImpression {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "recommendation_impression_seq")
    @SequenceGenerator(
            name = "recommendation_impression_seq",
            sequenceName = "db_recommendation_impression",
            allocationSize = 50
    )
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movie_id", nullable = false)
    private Movie movie;

    @Column(name = "recommended_at", nullable = false, updatable = false)
    private LocalDateTime recommendedAt;

    @PrePersist
    protected void onCreate() {
        this.recommendedAt = LocalDateTime.now();
    }
}
