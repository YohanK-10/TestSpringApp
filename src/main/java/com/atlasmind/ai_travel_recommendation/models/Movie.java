package com.atlasmind.ai_travel_recommendation.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class Movie {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "movie_seq")
    @SequenceGenerator(name = "movie_seq", sequenceName = "db_movie_counter", allocationSize = 50)
    private Long id;

    @Column(nullable = false)
    private String movieTitle;

    @Column(nullable = false, unique = true)
    private Integer tmdbId;

    @Column(columnDefinition = "TEXT")
    private String overview;

    @Column(length = 500)
    private String posterPath;

    @Column(length = 500)
    private String backdropPath;

    @Column(nullable = false)
    private LocalDateTime cachedAt;

    private LocalDate releaseDate;
    private Integer runtime;
    private Double movieRating;
    private Double popularity;

    /**
     * PostgreSQL generated tsvector column for full-text search.
     * Combines movie_title + overview, stemmed with English rules.
     * insertable/updatable = false because this is a GENERATED column —
     * PostgreSQL computes it automatically. Hibernate must not try to
     * write to it or the INSERT/UPDATE will fail.
     */
    @Column(name = "search_vector", insertable = false, updatable = false,
            columnDefinition = "tsvector")
    private String searchVector;

    @PrePersist
    protected void onCreate() {
        this.cachedAt = LocalDateTime.now();
    }

}
