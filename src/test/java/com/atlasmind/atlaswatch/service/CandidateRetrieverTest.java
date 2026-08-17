package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.config.RecommendationScoringProperties;
import com.atlasmind.atlaswatch.models.Movie;
import com.atlasmind.atlaswatch.repository.MovieGenreRepository;
import com.atlasmind.atlaswatch.repository.MovieKeywordRepository;
import com.atlasmind.atlaswatch.repository.MovieRepository;
import com.atlasmind.atlaswatch.support.TestFixtures;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CandidateRetrieverTest {

    @Test
    void collaborativeScoresAddAnAuthenticatedCandidateChannel() {
        MovieGenreRepository movieGenreRepository = mock(MovieGenreRepository.class);
        MovieKeywordRepository movieKeywordRepository = mock(MovieKeywordRepository.class);
        MovieRepository movieRepository = mock(MovieRepository.class);
        Movie collaborativeMovie = TestFixtures.movie(40L, 400, "Behavioral match");
        when(movieRepository.findByTmdbIdIn(any())).thenReturn(List.of(collaborativeMovie));
        when(movieRepository.findRecommendationReadyPopularMoviesExcluding(anyDouble(), any(), any())).thenReturn(List.of());
        when(movieRepository.findRecommendationReadyTopRatedMoviesExcluding(
                anyDouble(), anyDouble(), anyDouble(), any(), any())).thenReturn(List.of());
        when(movieRepository.findRecommendationReadyMoviesExcluding(anyDouble(), any())).thenReturn(List.of());

        CandidateRetriever retriever = new CandidateRetriever(
                movieGenreRepository, movieKeywordRepository, movieRepository,
                new RecommendationScorer(new RecommendationScoringProperties()), new ContentSimilarityService());
        RecommendationContext context = new RecommendationContext(
                1L, Map.of(), Set.of(), Set.of(), Set.of(), Set.of(), UserTasteProfile.empty(), List.of(),
                Map.of(400, 1.0), "stable", false, true);

        List<CatalogCandidate> candidates = retriever.retrieveCandidates(context, Set.of(SoloMood.ANY), 5);

        assertTrue(candidates.stream().anyMatch(candidate -> candidate.movie().getTmdbId().equals(400)
                && candidate.sourceChannels().contains("collaborative")));
    }

    @Test
    void differentRefreshTokensProduceDifferentCandidateSamples() {
        MovieGenreRepository movieGenreRepository = mock(MovieGenreRepository.class);
        MovieKeywordRepository movieKeywordRepository = mock(MovieKeywordRepository.class);
        MovieRepository movieRepository = mock(MovieRepository.class);

        List<Movie> popularCandidates = IntStream.rangeClosed(1, 60)
                .mapToObj(index -> {
                    Movie movie = TestFixtures.movie((long) index, 5000 + index, "Popular " + index);
                    movie.setPopularity(50.0 + index);
                    return movie;
                })
                .toList();

        when(movieRepository.findRecommendationReadyPopularMovies(anyDouble(), any()))
                .thenReturn(popularCandidates);
        when(movieRepository.findRecommendationReadyTopRatedMovies(
                anyDouble(), anyDouble(), anyDouble(), any()))
                .thenReturn(List.of());
        when(movieKeywordRepository.findByMovieIdInWithKeyword(any()))
                .thenReturn(List.of());

        CandidateRetriever candidateRetriever = new CandidateRetriever(
                movieGenreRepository,
                movieKeywordRepository,
                movieRepository,
                new RecommendationScorer(new RecommendationScoringProperties()),
                new ContentSimilarityService()
        );

        RecommendationContext firstContext = RecommendationContext.createColdStart(
                UserTasteProfile.empty(),
                List.of(),
                Set.of(),
                "refresh-a"
        );
        RecommendationContext secondContext = RecommendationContext.createColdStart(
                UserTasteProfile.empty(),
                List.of(),
                Set.of(),
                "refresh-b"
        );

        List<Integer> firstIds = candidateRetriever.retrieveCandidates(firstContext, java.util.Set.of(SoloMood.ANY), 5)
                .stream()
                .map(candidate -> candidate.movie().getTmdbId())
                .toList();
        List<Integer> secondIds = candidateRetriever.retrieveCandidates(secondContext, java.util.Set.of(SoloMood.ANY), 5)
                .stream()
                .map(candidate -> candidate.movie().getTmdbId())
                .toList();

        assertNotEquals(firstIds, secondIds);
    }
}
