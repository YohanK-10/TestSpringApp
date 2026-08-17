package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.dto.tmdb.MovieDetailDto;
import com.atlasmind.atlaswatch.dto.tmdb.MovieDto;
import com.atlasmind.atlaswatch.dto.tmdb.SearchResponseDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TmdbApiService}.
 * <p>
 * Tests the happy-path delegation to {@link RestClient} and validates
 * that Resilience4j annotations are correctly configured.
 * <p>
 * Note: {@code @CircuitBreaker} and {@code @Retry} annotations require
 * Spring AOP proxying to fire. These unit tests verify the method logic
 * and annotation placement; full integration with Resilience4j should be
 * verified via actuator endpoints ({@code /actuator/circuitbreakers},
 * {@code /actuator/retries}) in a running application.
 */
@ExtendWith(MockitoExtension.class)
class TmdbApiServiceTest {

    @Mock
    private RestClient tmdbRestClient;
    @Mock
    private RestClient.RequestHeadersUriSpec<?> requestHeadersUriSpec;
    @Mock
    private RestClient.RequestHeadersSpec<?> requestHeadersSpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;

    private TmdbApiService tmdbApiService;

    @BeforeEach
    void setUp() {
        tmdbApiService = new TmdbApiService(tmdbRestClient);

        // Wire up the RestClient mock chain: get() -> uri(...) -> retrieve() -> body(...)
        // Using doReturn().when() to avoid wildcard capture issues with thenReturn().
        lenient().doReturn(requestHeadersUriSpec).when(tmdbRestClient).get();
        lenient().doReturn(requestHeadersSpec).when(requestHeadersUriSpec).uri(anyString(), any(Object[].class));
        lenient().doReturn(requestHeadersSpec).when(requestHeadersUriSpec).uri(any(java.util.function.Function.class));
        lenient().doReturn(responseSpec).when(requestHeadersSpec).retrieve();
    }

    // ═══════════════════════════════════════════════════════
    //  Happy-path delegation tests
    // ═══════════════════════════════════════════════════════

    @Test
    void searchMoviesReturnsResponseFromRestClient() {
        SearchResponseDto expected = new SearchResponseDto(1, List.of(new MovieDto()), 5, 100);
        when(responseSpec.body(SearchResponseDto.class)).thenReturn(expected);

        SearchResponseDto result = tmdbApiService.searchMovies("inception", 1);

        assertSame(expected, result);
        verify(requestHeadersUriSpec).uri("/search/movie?query={query}&page={page}", "inception", 1);
    }

    @Test
    void getMovieDetailsReturnsDetailFromRestClient() {
        MovieDetailDto expected = new MovieDetailDto();
        when(responseSpec.body(MovieDetailDto.class)).thenReturn(expected);

        MovieDetailDto result = tmdbApiService.getMovieDetails(27205L);

        assertSame(expected, result);
        verify(requestHeadersUriSpec).uri("/movie/{tmdbId}?append_to_response=keywords", 27205L);
    }

    @Test
    void getTrendingMoviesDefaultsDayWindowPage1() {
        SearchResponseDto expected = new SearchResponseDto(1, List.of(), 1, 0);
        when(responseSpec.body(SearchResponseDto.class)).thenReturn(expected);

        SearchResponseDto result = tmdbApiService.getTrendingMovies();

        assertSame(expected, result);
        verify(requestHeadersUriSpec).uri("/trending/movie/day?page=1");
    }

    @Test
    void getPopularMoviesDelegatesToCorrectEndpoint() {
        SearchResponseDto expected = new SearchResponseDto(2, List.of(), 10, 200);
        when(responseSpec.body(SearchResponseDto.class)).thenReturn(expected);

        SearchResponseDto result = tmdbApiService.getPopularMovies(2);

        assertSame(expected, result);
        verify(requestHeadersUriSpec).uri("/movie/popular?page={page}", 2);
    }

    @Test
    void getTopRatedMoviesDelegatesToCorrectEndpoint() {
        SearchResponseDto expected = new SearchResponseDto(3, List.of(), 15, 300);
        when(responseSpec.body(SearchResponseDto.class)).thenReturn(expected);

        SearchResponseDto result = tmdbApiService.getTopRatedMovies(3);

        assertSame(expected, result);
        verify(requestHeadersUriSpec).uri("/movie/top_rated?page={page}", 3);
    }

    @Test
    void discoverMoviesByGenreDelegatesToCorrectEndpoint() {
        SearchResponseDto expected = new SearchResponseDto(1, List.of(), 5, 100);
        when(responseSpec.body(SearchResponseDto.class)).thenReturn(expected);

        SearchResponseDto result = tmdbApiService.discoverMoviesByGenre(28, 1);

        assertSame(expected, result);
        // discoverMoviesByGenre uses a UriBuilder function, so verify the function-based uri() was called
        verify(requestHeadersUriSpec).uri(any(java.util.function.Function.class));
    }

    // ═══════════════════════════════════════════════════════
    //  Annotation configuration validation
    // ═══════════════════════════════════════════════════════

    @Test
    void allPublicApiMethodsHaveCircuitBreakerAnnotation() throws NoSuchMethodException {
        assertHasCircuitBreaker("searchMovies", String.class, int.class);
        assertHasCircuitBreaker("getMovieDetails", Long.class);
        assertHasCircuitBreaker("getTrendingMovies", String.class, int.class);
        assertHasCircuitBreaker("getPopularMovies", int.class);
        assertHasCircuitBreaker("getTopRatedMovies", int.class);
        assertHasCircuitBreaker("discoverMoviesByGenre", int.class, int.class);
    }

    @Test
    void allPublicApiMethodsHaveRetryWithFallback() throws NoSuchMethodException {
        assertRetryHasFallback("searchMovies", "searchMoviesFallback", String.class, int.class);
        assertRetryHasFallback("getMovieDetails", "getMovieDetailsFallback", Long.class);
        assertRetryHasFallback("getTrendingMovies", "getTrendingMoviesFallback", String.class, int.class);
        assertRetryHasFallback("getPopularMovies", "getPopularMoviesFallback", int.class);
        assertRetryHasFallback("getTopRatedMovies", "getTopRatedMoviesFallback", int.class);
        assertRetryHasFallback("discoverMoviesByGenre", "discoverMoviesByGenreFallback", int.class, int.class);
    }

    @Test
    void retryFallbackMethodsExistWithThrowableParameter() throws NoSuchMethodException {
        assertFallbackSignature("searchMoviesFallback", SearchResponseDto.class, String.class, int.class, Throwable.class);
        assertFallbackSignature("getMovieDetailsFallback", MovieDetailDto.class, Long.class, Throwable.class);
        assertFallbackSignature("getTrendingMoviesFallback", SearchResponseDto.class, String.class, int.class, Throwable.class);
        assertFallbackSignature("getPopularMoviesFallback", SearchResponseDto.class, int.class, Throwable.class);
        assertFallbackSignature("getTopRatedMoviesFallback", SearchResponseDto.class, int.class, Throwable.class);
        assertFallbackSignature("discoverMoviesByGenreFallback", SearchResponseDto.class, int.class, int.class, Throwable.class);
    }

    @Test
    void circuitBreakerAnnotationsDoNotHaveFallbackMethod() throws NoSuchMethodException {
        // Fallback should be on @Retry, not @CircuitBreaker.
        // If fallback is on @CircuitBreaker, the exception is swallowed before
        // @Retry can see it, making retry effectively dead code.
        String[] methods = {
                "searchMovies", "getMovieDetails", "getTrendingMovies",
                "getPopularMovies", "getTopRatedMovies", "discoverMoviesByGenre"
        };
        for (String methodName : methods) {
            for (Method method : TmdbApiService.class.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    CircuitBreaker cb = method.getAnnotation(CircuitBreaker.class);
                    if (cb != null) {
                        assertEquals("", cb.fallbackMethod(),
                                "@CircuitBreaker on " + methodName + " must NOT have fallbackMethod; "
                                        + "fallback belongs on @Retry so retries fire before fallback");
                    }
                }
            }
        }
    }

    @Test
    void noArgGetTrendingMoviesHasResilienceAnnotations() throws NoSuchMethodException {
        // The homepage calls this overload directly. It must own the annotations
        // because self-invocation into the two-argument overload bypasses Spring AOP.
        Method noArg = TmdbApiService.class.getMethod("getTrendingMovies");
        assertNotNull(noArg.getAnnotation(CircuitBreaker.class),
                "No-arg getTrendingMovies should have @CircuitBreaker");
        assertNotNull(noArg.getAnnotation(Retry.class),
                "No-arg getTrendingMovies should have @Retry");
    }

    // ── helpers ──

    private void assertHasCircuitBreaker(String methodName, Class<?>... paramTypes) throws NoSuchMethodException {
        Method method = TmdbApiService.class.getMethod(methodName, paramTypes);
        CircuitBreaker annotation = method.getAnnotation(CircuitBreaker.class);
        assertNotNull(annotation, methodName + " should have @CircuitBreaker");
        assertEquals("tmdb", annotation.name(), methodName + " CircuitBreaker name should be 'tmdb'");
    }

    private void assertRetryHasFallback(String methodName, String expectedFallback, Class<?>... paramTypes)
            throws NoSuchMethodException {
        Method method = TmdbApiService.class.getMethod(methodName, paramTypes);
        Retry annotation = method.getAnnotation(Retry.class);
        assertNotNull(annotation, methodName + " should have @Retry");
        assertEquals("tmdb", annotation.name(), methodName + " Retry name should be 'tmdb'");
        assertEquals(expectedFallback, annotation.fallbackMethod(),
                methodName + " @Retry fallbackMethod should be '" + expectedFallback + "'");
    }

    private void assertFallbackSignature(String methodName, Class<?> expectedReturnType, Class<?>... paramTypes)
            throws NoSuchMethodException {
        Method fallbackMethod = TmdbApiService.class.getDeclaredMethod(methodName, paramTypes);
        assertEquals(expectedReturnType, fallbackMethod.getReturnType(),
                methodName + " should return " + expectedReturnType.getSimpleName());
    }
}
