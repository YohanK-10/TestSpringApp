package com.atlasmind.atlaswatch.models;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "movie_keyword", indexes = {
        @Index(name = "idx_movie_keyword_movie", columnList = "movie_id"),
        @Index(name = "idx_movie_keyword_keyword", columnList = "keyword_id")
})
public class MovieKeyword {

    @EmbeddedId
    private MovieKeywordId movieKeywordId;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("movieId")
    @JoinColumn(name = "movie_id")
    private Movie movie;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("keywordId")
    @JoinColumn(name = "keyword_id")
    private Keyword keyword;

    public MovieKeyword(Movie movie, Keyword keyword) {
        this.movie = movie;
        this.keyword = keyword;
        this.movieKeywordId = new MovieKeywordId(movie.getId(), keyword.getId());
    }
}
