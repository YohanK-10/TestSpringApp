package com.atlasmind.ai_travel_recommendation.dto.response;

import com.atlasmind.ai_travel_recommendation.models.WatchList;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@Builder
public class WatchlistResponseDto {

    private final Long id;
    private final Integer tmdbId;
    private final String movieTitle;
    private final String posterPath;
    private final String status;
    private final LocalDateTime addedAt;

    public static WatchlistResponseDto fromWatchlist(WatchList watchlist) {
        return WatchlistResponseDto.builder()
                .id(watchlist.getId())
                .tmdbId(watchlist.getMovie().getTmdbId())
                .movieTitle(watchlist.getMovie().getMovieTitle())
                .posterPath(watchlist.getMovie().getPosterPath())
                .status(watchlist.getStatus().name())
                .addedAt(watchlist.getAddedAt())
                .build();
    }
}
