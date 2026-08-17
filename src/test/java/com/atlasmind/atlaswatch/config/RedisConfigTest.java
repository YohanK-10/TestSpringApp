package com.atlasmind.atlaswatch.config;

import com.atlasmind.atlaswatch.dto.response.RecommendationResponseDto;
import com.atlasmind.atlaswatch.service.CachedRecommendationResponses;
import com.atlasmind.atlaswatch.service.CachedUserTasteProfile;
import com.atlasmind.atlaswatch.service.UserTasteProfile;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisConfigTest {

    @Test
    void redisSerializerRoundTripsCachedRecommendationResponses() {
        GenericJackson2JsonRedisSerializer serializer = new RedisConfig().redisCacheValueSerializer();
        RecommendationResponseDto response = RecommendationResponseDto.builder()
                .tmdbId(1180)
                .movieTitle("Cache Me If You Can")
                .movieOverview("A catalog recommendation that should survive Redis serialization.")
                .posterPath("/poster.jpg")
                .backdropPath("/backdrop.jpg")
                .releaseDate(LocalDate.of(2024, 6, 15))
                .rating(8.4)
                .runtime(112)
                .popularity(320.0)
                .genres(List.of("Action", "Comedy"))
                .onWatchlist(false)
                .watchlistStatus(null)
                .reasons(List.of("Popular right now", "Matches your recent picks"))
                .build();
        CachedRecommendationResponses cachedResponses = new CachedRecommendationResponses(List.of(response));

        byte[] payload = serializer.serialize(cachedResponses);
        assertNotNull(payload);

        Object restored = serializer.deserialize(payload);
        assertInstanceOf(CachedRecommendationResponses.class, restored);

        CachedRecommendationResponses deserialized = (CachedRecommendationResponses) restored;
        assertEquals(1, deserialized.getResponses().size());

        RecommendationResponseDto restoredResponse = deserialized.getResponses().getFirst();
        assertEquals(response.getTmdbId(), restoredResponse.getTmdbId());
        assertEquals(response.getMovieTitle(), restoredResponse.getMovieTitle());
        assertEquals(response.getMovieOverview(), restoredResponse.getMovieOverview());
        assertEquals(response.getPosterPath(), restoredResponse.getPosterPath());
        assertEquals(response.getBackdropPath(), restoredResponse.getBackdropPath());
        assertEquals(response.getReleaseDate(), restoredResponse.getReleaseDate());
        assertEquals(response.getRating(), restoredResponse.getRating());
        assertEquals(response.getRuntime(), restoredResponse.getRuntime());
        assertEquals(response.getPopularity(), restoredResponse.getPopularity());
        assertEquals(response.getGenres(), restoredResponse.getGenres());
        assertEquals(response.isOnWatchlist(), restoredResponse.isOnWatchlist());
        assertEquals(response.getWatchlistStatus(), restoredResponse.getWatchlistStatus());
        assertEquals(response.getReasons(), restoredResponse.getReasons());
    }

    @Test
    void redisSerializerRoundTripsCachedUserTasteProfile() {
        GenericJackson2JsonRedisSerializer serializer = new RedisConfig().redisCacheValueSerializer();

        UserTasteProfile original = new UserTasteProfile(
                Map.of("Thriller", 1.8, "Crime", 1.2),
                Map.of("Romance", 0.6),
                Map.of("Thriller", 1.8, "Crime", 1.2, "Romance", -0.6),
                Map.of("Time Loop", 0.9),
                Map.of("Love Triangle", 0.4),
                Map.of("Time Loop", 0.9, "Love Triangle", -0.4),
                3,
                2
        );
        CachedUserTasteProfile cached = new CachedUserTasteProfile(original);

        byte[] payload = serializer.serialize(cached);
        assertNotNull(payload);

        Object restored = serializer.deserialize(payload);
        assertInstanceOf(CachedUserTasteProfile.class, restored);

        CachedUserTasteProfile deserialized = (CachedUserTasteProfile) restored;
        assertEquals(cached.getPositiveGenreWeights(), deserialized.getPositiveGenreWeights());
        assertEquals(cached.getNegativeGenreWeights(), deserialized.getNegativeGenreWeights());
        assertEquals(cached.getNetGenreWeights(), deserialized.getNetGenreWeights());
        assertEquals(cached.getPositiveKeywordWeights(), deserialized.getPositiveKeywordWeights());
        assertEquals(cached.getNegativeKeywordWeights(), deserialized.getNegativeKeywordWeights());
        assertEquals(cached.getNetKeywordWeights(), deserialized.getNetKeywordWeights());
        assertEquals(cached.getReviewSignalCount(), deserialized.getReviewSignalCount());
        assertEquals(cached.getWatchlistSignalCount(), deserialized.getWatchlistSignalCount());

        // Verify round-trip back to the domain record
        UserTasteProfile restoredProfile = deserialized.toProfile();
        assertTrue(restoredProfile.hasSignals());
        assertEquals(1.8, restoredProfile.positiveWeight("Thriller"));
        assertEquals(0.6, restoredProfile.negativeWeight("Romance"));
        assertEquals(-0.6, restoredProfile.netWeight("Romance"));
        assertEquals(0.9, restoredProfile.positiveKeywordWeight("Time Loop"));
        assertEquals(0.4, restoredProfile.negativeKeywordWeight("Love Triangle"));
        assertEquals(-0.4, restoredProfile.netKeywordWeight("Love Triangle"));
        assertEquals(3, restoredProfile.reviewSignalCount());
        assertEquals(2, restoredProfile.watchlistSignalCount());
    }
}
