package com.atlasmind.atlaswatch.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableCaching
public class RedisConfig {
    @Bean
    public GenericJackson2JsonRedisSerializer redisCacheValueSerializer() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                                           ObjectMapper.DefaultTyping.NON_FINAL,
                                           JsonTypeInfo.As.PROPERTY);
        return new GenericJackson2JsonRedisSerializer(objectMapper);
    }

    @Bean
    public RedisCacheManager cacheManager(
            RedisConnectionFactory redisConnectionFactory,
            GenericJackson2JsonRedisSerializer redisCacheValueSerializer
    ) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(redisCacheValueSerializer))
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> cacheConfiguration = new HashMap<>();
        // Trending movies: updated frequently by TMDB, short TTL
        cacheConfiguration.put("trending",
                defaultConfig.entryTtl(Duration.ofMinutes(10)));

        // Movie details: rarely change, longer TTL
        cacheConfiguration.put("movieDetails",
                defaultConfig.entryTtl(Duration.ofHours(1)));

        // Recommendation results are expensive to build but should stay fresh.
        cacheConfiguration.put("recommendations",
                defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfiguration.put("coldStartRecommendations",
                defaultConfig.entryTtl(Duration.ofMinutes(5)));

        // User taste profiles are stable until the user adds a review or watchlist entry.
        cacheConfiguration.put("userProfiles",
                defaultConfig.entryTtl(Duration.ofMinutes(10)));

        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfiguration)
                .build();
    }
}

