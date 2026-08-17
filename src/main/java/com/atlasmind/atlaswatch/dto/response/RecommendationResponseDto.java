package com.atlasmind.atlaswatch.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecommendationResponseDto {
    private Integer tmdbId;
    private String movieTitle;
    private String movieOverview;
    private String posterPath;
    private String backdropPath;
    private LocalDate releaseDate;
    private Double rating;
    private Integer voteCount;
    private Integer runtime;
    private Double popularity;
    private List<String> genres;
    private boolean onWatchlist;
    private String watchlistStatus;
    private List<String> reasons;
}

