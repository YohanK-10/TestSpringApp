package com.atlasmind.ai_travel_recommendation.dto.tmdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps TMDB's detailed movie response (/movie/{id}).
 * This has MORE fields than the search response — notably:
 * - runtime (movie length in minutes)
 * - genres as full objects [{id: 28, name: "Action"}] instead of just [28]
 * Why a separate DTO from TmdbMovieDto? Because the search endpoint
 * and the details endpoint return DIFFERENT JSON structures.
 * The search endpoint gives genre_ids (just numbers),
 * the detail endpoint gives genres (objects with id + name).
 * If we tried to use one class for both, we'd need confusing
 * conditional logic. Separate DTOs keep it clean.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MovieDetailDto {

    private Long id;
    private String title;
    private String overview;

    @JsonProperty("poster_path")
    private String posterPath;

    @JsonProperty("backdrop_path")
    private String backdropPath;

    @JsonProperty("release_date")
    private String releaseDate;

    @JsonProperty("vote_average")
    private Double voteAverage;

    private Integer runtime;
    private List<Genre> genres;
    private Double popularity;

    /**
     * Inner class for genre objects.
     * TMDB returns: { "id": 28, "name": "Action" }
     * This is a static inner class because it only makes sense
     * in the context of a TMDB movie detail — no other class needs it.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Genre {
        private Integer id;
        private String name;
    }
}
