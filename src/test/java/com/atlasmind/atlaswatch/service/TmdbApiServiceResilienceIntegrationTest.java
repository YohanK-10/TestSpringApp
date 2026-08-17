package com.atlasmind.atlaswatch.service;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest(
        classes = TmdbApiServiceResilienceIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "resilience4j.circuitbreaker.instances.tmdb.sliding-window-type=COUNT_BASED",
                "resilience4j.circuitbreaker.instances.tmdb.sliding-window-size=10",
                "resilience4j.circuitbreaker.instances.tmdb.failure-rate-threshold=50",
                "resilience4j.circuitbreaker.instances.tmdb.wait-duration-in-open-state=10m",
                "resilience4j.circuitbreaker.instances.tmdb.permitted-number-of-calls-in-half-open-state=1",
                "resilience4j.circuitbreaker.instances.tmdb.minimum-number-of-calls=10",
                "resilience4j.retry.instances.tmdb.max-attempts=3",
                "resilience4j.retry.instances.tmdb.wait-duration=1ms",
                "resilience4j.retry.instances.tmdb.retry-exceptions=org.springframework.web.client.RestClientException"
        }
)
class TmdbApiServiceResilienceIntegrationTest {

    @Autowired
    private TmdbApiService tmdbApiService;

    @Autowired
    private MockRestServiceServer mockRestServiceServer;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetResilienceState() {
        circuitBreakerRegistry.circuitBreaker("tmdb").reset();
        mockRestServiceServer.reset();
    }

    @Test
    void retriesTransientFailuresBeforeReturningSuccess() {
        mockRestServiceServer.expect(ExpectedCount.once(), requestTo("https://tmdb.test/movie/popular?page=1"))
                .andRespond(withServerError());
        mockRestServiceServer.expect(ExpectedCount.once(), requestTo("https://tmdb.test/movie/popular?page=1"))
                .andRespond(withServerError());
        mockRestServiceServer.expect(ExpectedCount.once(), requestTo("https://tmdb.test/movie/popular?page=1"))
                .andRespond(withSuccess("""
                        {
                          "page": 1,
                          "results": [],
                          "total_pages": 1,
                          "total_results": 0
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = tmdbApiService.getPopularMovies(1);

        assertNotNull(result);
        assertEquals(1, result.getPage());
        assertEquals(1, result.getTotalPages());
        mockRestServiceServer.verify();
    }

    @Test
    void fallbackReturnsNullAfterRetriesAreExhausted() {
        mockRestServiceServer.expect(ExpectedCount.times(3), requestTo("https://tmdb.test/movie/top_rated?page=2"))
                .andRespond(withServerError());

        var result = tmdbApiService.getTopRatedMovies(2);

        assertNull(result);
        mockRestServiceServer.verify();
    }

    @Test
    void circuitBreakerShortCircuitsCallsWhenOpenAtRuntime() {
        circuitBreakerRegistry.circuitBreaker("tmdb").transitionToOpenState();
        assertNull(tmdbApiService.getPopularMovies(7));
        assertEquals(
                io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN,
                circuitBreakerRegistry.circuitBreaker("tmdb").getState()
        );
        mockRestServiceServer.verify();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            MailSenderAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class
    })
    static class TestConfig {

        @Bean
        RestClient.Builder tmdbRestClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        MockRestServiceServer mockRestServiceServer(RestClient.Builder tmdbRestClientBuilder) {
            return MockRestServiceServer.bindTo(tmdbRestClientBuilder).build();
        }

        @Bean
        RestClient tmdbRestClient(RestClient.Builder tmdbRestClientBuilder) {
            return tmdbRestClientBuilder
                    .baseUrl("https://tmdb.test")
                    .defaultHeader("Authorization", "Bearer test-token")
                    .defaultHeader("Accept", "application/json")
                    .build();
        }

        @Bean
        TmdbApiService tmdbApiService(RestClient tmdbRestClient) {
            return new TmdbApiService(tmdbRestClient);
        }
    }
}
