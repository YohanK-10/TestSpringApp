package com.atlasmind.atlaswatch.dto.response;

import com.atlasmind.atlaswatch.models.WatchList;
import com.atlasmind.atlaswatch.models.WatchListStatus;
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
                .status(normalizeStatus(watchlist.getStatus()).name())
                .addedAt(watchlist.getAddedAt())
                .build();
    }

    private static WatchListStatus normalizeStatus(WatchListStatus status) {
        return status == WatchListStatus.WATCHING ? WatchListStatus.PLAN_TO_WATCH : status;
    }
}

