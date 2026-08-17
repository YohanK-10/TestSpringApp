package com.atlasmind.atlaswatch.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SoloRecommendationResponseDto {
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
    private String watchlistStatus;
    private LocalDateTime addedAt;
    private int score;
    private List<String> reasons;
}

