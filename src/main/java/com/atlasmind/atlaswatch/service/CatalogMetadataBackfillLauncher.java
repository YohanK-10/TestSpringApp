package com.atlasmind.atlaswatch.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Opt-in startup launcher; disabled unless explicitly requested for maintenance. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "atlaswatch.catalog.metadata-backfill.run-on-startup",
        havingValue = "true"
)
public class CatalogMetadataBackfillLauncher {

    private final CatalogMetadataBackfillService backfillService;

    @Value("${atlaswatch.catalog.metadata-backfill.batch-size:100}")
    private int batchSize;

    @Value("${atlaswatch.catalog.metadata-backfill.max-items:0}")
    private int maxItems;

    @Value("${atlaswatch.catalog.metadata-backfill.rate-limit-ms:100}")
    private long rateLimitMs;

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void launch() {
        try {
            backfillService.backfill(batchSize, maxItems, rateLimitMs);
        } catch (RuntimeException exception) {
            log.error("Semantic metadata backfill stopped: {}", exception.getMessage(), exception);
        }
    }
}
