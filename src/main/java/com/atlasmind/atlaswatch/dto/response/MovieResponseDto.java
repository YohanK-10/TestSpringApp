package com.atlasmind.atlaswatch.dto.response;

import com.atlasmind.atlaswatch.models.Movie;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@AllArgsConstructor
@Builder
@Getter
public class MovieResponseDto {
    private Integer tmdbId;
    private String movieTitle;
    private String movieOverview;
    private LocalDate releaseDate;
    private String posterPath;
    private String backdropPath;
    private Double rating;
    private Integer voteCount;
    private Integer runtime;
    private Double popularity;
    private List<String> genres;


    public static MovieResponseDto fromMovie (Movie movie, List<String> genreNames){
        return MovieResponseDto.builder()
                .tmdbId(movie.getTmdbId())
                .movieTitle(movie.getMovieTitle())
                .movieOverview(movie.getOverview())
                .posterPath(movie.getPosterPath())
                .backdropPath(movie.getBackdropPath())
                .releaseDate(movie.getReleaseDate())
                .rating(movie.getMovieRating())
                .voteCount(movie.getVoteCount())
                .runtime(movie.getRuntime())
                .popularity(movie.getPopularity())
                .genres(genreNames)
                .build();
    }
}

