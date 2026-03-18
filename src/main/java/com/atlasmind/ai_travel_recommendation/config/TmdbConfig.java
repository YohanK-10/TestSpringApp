package com.atlasmind.ai_travel_recommendation.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class TmdbConfig {
    @Value("${tmdb.api.base-url}")
    private String baseUrl;

    @Value("${tmdb.api.token}")
    private String apiToken;

    @Bean
    public RestClient tmdbRestClient() {
        // 1. Configure timeouts — how long to wait before giving up
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults().
                withConnectTimeout(Duration.ofSeconds(5)).withReadTimeout(Duration.ofSeconds(10));

        // 2. Use the new Builder to create the factory
        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.detect().build(settings);

        // 3. Build and return the RestClient
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiToken)
                .defaultHeader("Accept", "application/json")
                .requestFactory(factory)
                .build();
    }
}