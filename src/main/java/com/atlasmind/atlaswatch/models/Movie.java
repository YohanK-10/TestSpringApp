package com.atlasmind.atlaswatch.models;

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
@Table(name = "movie", indexes = {
        @Index(name = "idx_movie_popularity_rating", columnList = "popularity,movie_rating"),
        @Index(name = "idx_movie_rating_popularity", columnList = "movie_rating,popularity"),
        @Index(name = "idx_movie_cached_at", columnList = "cached_at")
})
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
    private Integer voteCount;
    private Double popularity;

    /**
     * Records a successful TMDB detail fetch, even when TMDB returned zero
     * keywords. This makes the metadata backfill resumable without repeatedly
     * fetching legitimately keyword-less movies.
     */
    @Column(name = "semantic_metadata_synced_at")
    private LocalDateTime semanticMetadataSyncedAt;

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

