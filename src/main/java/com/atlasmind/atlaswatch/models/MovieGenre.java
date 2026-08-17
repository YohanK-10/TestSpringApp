package com.atlasmind.atlaswatch.models;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "movie_genre", indexes = {
        @Index(name = "idx_movie_genre_movie", columnList = "movie_id"),
        @Index(name = "idx_movie_genre_genre", columnList = "genre_id")
})
public class MovieGenre {
    @EmbeddedId
    private MovieGenreId movieGenreId;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("movieId")
    @JoinColumn(name = "movie_id") // movie_id is the name of the column in this table.
    private Movie movie; // The type of the variable helps in understanding which class this is associated with.

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("genreId")
    @JoinColumn(name = "genre_id")
    private Genre genre;

    public MovieGenre(Movie movie, Genre genre) {
        this.movie = movie;
        this.genre = genre;
        this.movieGenreId = new MovieGenreId(movie.getId(), genre.getId());
    }
}

