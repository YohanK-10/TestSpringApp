package com.atlasmind.atlaswatch.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlasmind.atlaswatch.dto.tmdb.MovieDetailDto;
import com.atlasmind.atlaswatch.models.Movie;
import com.atlasmind.atlaswatch.repository.MovieRepository;
import com.atlasmind.atlaswatch.support.TestFixtures;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class CatalogMetadataBackfillServiceTest {

    @Mock MovieRepository movieRepository;
    @Mock TmdbApiService tmdbApiService;
    @Mock MovieService movieService;
    @Mock RecommendationCacheInvalidationService cacheInvalidationService;

    private CatalogMetadataBackfillService service;

    @BeforeEach
    void setUp() {
        service = new CatalogMetadataBackfillService(
                movieRepository,
                tmdbApiService,
                movieService,
                cacheInvalidationService
        );
    }

    @Test
    void backfillIsResumableAndContinuesAfterPerMovieFailure() {
        Movie first = TestFixtures.movie(1L, 101, "First");
        Movie second = TestFixtures.movie(2L, 102, "Second");
        MovieDetailDto firstDetail = TestFixtures.movieDetailDto(101L, "First", List.of());

        when(movieRepository.countRecommendationReadyMovies(anyDouble())).thenReturn(2L);
        when(movieRepository.countRecommendationReadyMoviesWithSemanticMetadata(anyDouble()))
                .thenReturn(0L, 1L);
        when(movieRepository.findRecommendationReadyMoviesMissingSemanticMetadata(anyDouble(), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        when(movieRepository.findRecommendationReadyMoviesMissingSemanticMetadataExcluding(
                anyDouble(), any(), any(Pageable.class)))
                .thenReturn(List.of());
        when(tmdbApiService.getMovieDetails(101L)).thenReturn(firstDetail);
        when(tmdbApiService.getMovieDetails(102L)).thenThrow(new RuntimeException("temporary failure"));

        var report = service.backfill(100, 0, 0);

        assertEquals(2, report.attempted());
        assertEquals(1, report.succeeded());
        assertEquals(1, report.failed());
        assertEquals(1, report.syncedAfter());
        verify(movieService).saveOrUpdateMovieDetails(firstDetail, first);
        verify(tmdbApiService, times(1)).getMovieDetails(102L);
        verify(cacheInvalidationService).evictAll();
    }

    @Test
    void emptyBackfillDoesNotInvalidateCaches() {
        when(movieRepository.countRecommendationReadyMovies(anyDouble())).thenReturn(5L);
        when(movieRepository.countRecommendationReadyMoviesWithSemanticMetadata(anyDouble()))
                .thenReturn(5L, 5L);
        when(movieRepository.findRecommendationReadyMoviesMissingSemanticMetadata(anyDouble(), any(Pageable.class)))
                .thenReturn(List.of());

        var report = service.backfill(100, 0, 0);

        assertEquals(0, report.attempted());
        assertEquals(1.0, report.completionRate());
        verify(cacheInvalidationService, never()).evictAll();
    }
}
