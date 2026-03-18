package com.atlasmind.ai_travel_recommendation.models;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
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
