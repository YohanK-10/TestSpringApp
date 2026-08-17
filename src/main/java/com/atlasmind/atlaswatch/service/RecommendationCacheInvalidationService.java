package com.atlasmind.atlaswatch.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationCacheInvalidationService {

    static final String PERSONALIZED_RECOMMENDATIONS_CACHE = "recommendations";
    static final String COLD_START_RECOMMENDATIONS_CACHE = "coldStartRecommendations";
    static final String USER_PROFILES_CACHE = "userProfiles";

    private final CacheManager cacheManager;

    /**
     * Evict cached recommendations and the taste profile for a specific user.
     * <p>
     * Spring's {@link Cache} API does not support prefix-based eviction, so
     * we clear the entire recommendations cache. With the short TTL (5 minutes)
     * configured in {@link com.atlasmind.atlaswatch.config.RedisConfig}, the
     * blast radius is limited. The profile cache supports per-key eviction
     * because its key is just the userId.
     */
    public void evictForUser(Long userId) {
        evictCacheSilently(PERSONALIZED_RECOMMENDATIONS_CACHE);
        evictProfileForUser(userId);
    }

    /**
     * Evict all cached recommendation and profile entries. Use when a broad
     * change (e.g. catalog ingestion) makes all cached results potentially stale.
     */
    public void evictAll() {
        evictCacheSilently(PERSONALIZED_RECOMMENDATIONS_CACHE);
        evictCacheSilently(COLD_START_RECOMMENDATIONS_CACHE);
        evictCacheSilently(USER_PROFILES_CACHE);
    }

    private void evictProfileForUser(Long userId) {
        if (userId == null) {
            return;
        }

        try {
            Cache cache = cacheManager.getCache(USER_PROFILES_CACHE);
            if (cache != null) {
                cache.evict("user:" + userId);
            }
        } catch (Exception ex) {
            log.warn("Failed to evict profile cache for user {}: {}", userId, ex.getMessage());
        }
    }

    private void evictCacheSilently(String cacheName) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        } catch (Exception ex) {
            log.warn("Failed to clear cache '{}': {}", cacheName, ex.getMessage());
        }
    }
}
