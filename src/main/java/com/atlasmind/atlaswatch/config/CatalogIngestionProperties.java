package com.atlasmind.atlaswatch.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "atlaswatch.catalog.ingestion")
public class CatalogIngestionProperties {

    private boolean enabled = true;
    private long fixedDelayMs = 43_200_000L;
    private long initialDelayMs = 300_000L;
    private long rateLimitMs = 250L;
    private long staleAfterHours = 168L;
    private long minimumCatalogSize = 500L;
    private int popularPages = 10;
    private int topRatedPages = 10;
    private int trendingPages = 5;
    private int discoverPagesPerGenre = 3;
    private List<Integer> discoverGenreIds = List.of(
            28, 12, 16, 35, 80, 99, 18, 10751, 14,
            36, 27, 10402, 9648, 10749, 878, 53, 10752, 37
    );
}

