package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.dto.response.MovieResponseDto;
import com.atlasmind.atlaswatch.dto.tmdb.MovieDetailDto;
import com.atlasmind.atlaswatch.dto.tmdb.MovieDto;
import com.atlasmind.atlaswatch.dto.tmdb.SearchResponseDto;
import com.atlasmind.atlaswatch.models.Genre;
import com.atlasmind.atlaswatch.models.Keyword;
import com.atlasmind.atlaswatch.models.Movie;
import com.atlasmind.atlaswatch.models.MovieGenre;
import com.atlasmind.atlaswatch.models.MovieKeyword;
import com.atlasmind.atlaswatch.repository.GenreRepository;
import com.atlasmind.atlaswatch.repository.KeywordRepository;
import com.atlasmind.atlaswatch.repository.MovieGenreRepository;
import com.atlasmind.atlaswatch.repository.MovieKeywordRepository;
import com.atlasmind.atlaswatch.repository.MovieRepository;
import com.atlasmind.atlaswatch.support.TestFixtures;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MovieServiceTest {

    @Mock
    private MovieRepository movieRepository;
    @Mock
    private GenreRepository genreRepository;
    @Mock
    private MovieGenreRepository movieGenreRepository;
    @Mock
    private KeywordRepository keywordRepository;
    @Mock
    private MovieKeywordRepository movieKeywordRepository;
    @Mock
    private TmdbApiService tmdbApiService;

    @InjectMocks
    private MovieService movieService;

    @Test
    void getMovieByTmdbIdReturnsFreshCachedMovieWithoutCallingTmdb() {
        Movie cached = TestFixtures.movie(10L, 27205, "Inception");
        cached.setCachedAt(LocalDateTime.now());
        when(movieRepository.findByTmdbId(27205)).thenReturn(Optional.of(cached));

        Movie result = movieService.getMovieByTmdbId(27205);

        assertSame(cached, result);
        verifyNoInteractions(tmdbApiService);
    }

    @Test
    void getMovieDetailsDtoRefreshesMovieAndBuildsResponseDto() {
        MovieDetailDto tmdbData = TestFixtures.movieDetailDto(
                27205L,
                "Inception",
                List.of(new MovieDetailDto.Genre(28, "Action")),
                List.of(new MovieDetailDto.Keyword(101, "dream"))
        );
        Genre action = TestFixtures.genre(2L, 28, "Action");
        Keyword dream = TestFixtures.keyword(3L, 101, "dream");

        when(movieRepository.findByTmdbId(27205)).thenReturn(Optional.empty());
        when(tmdbApiService.getMovieDetails(27205L)).thenReturn(tmdbData);
        when(movieRepository.save(any(Movie.class))).thenAnswer(invocation -> {
            Movie movie = invocation.getArgument(0);
            movie.setId(10L);
            return movie;
        });
        when(genreRepository.findByTmdbIdIn(List.of(28))).thenReturn(List.of(action));
        when(keywordRepository.findByTmdbIdIn(List.of(101))).thenReturn(List.of(dream));
        when(movieGenreRepository.findByMovieId(10L))
                .thenReturn(List.of(TestFixtures.movieGenre(TestFixtures.movie(10L, 27205, "Inception"), action)));

        MovieResponseDto result = movieService.getMovieDetailsDto(27205);

        assertNotNull(result);
        assertEquals(27205, result.getTmdbId());
        assertEquals("Inception", result.getMovieTitle());
        assertEquals(4_500, result.getVoteCount());
        assertEquals(List.of("Action"), result.getGenres());
        verify(movieGenreRepository).flush();
        verify(movieKeywordRepository).flush();
    }

    @Test
    void saveOrUpdateMovieDetailsCreatesMissingGenresAndKeywordsFromTmdbDetails() {
        MovieDetailDto tmdbData = TestFixtures.movieDetailDto(
                27205L,
                "Inception",
                List.of(new MovieDetailDto.Genre(878, "Science Fiction")),
                List.of(new MovieDetailDto.Keyword(501, "time loop"))
        );

        when(movieRepository.save(any(Movie.class))).thenAnswer(invocation -> {
            Movie movie = invocation.getArgument(0);
            if (movie.getId() == null) {
                movie.setId(42L);
            }
            return movie;
        });
        when(genreRepository.findByTmdbIdIn(List.of(878))).thenReturn(List.of());
        when(keywordRepository.findByTmdbIdIn(List.of(501))).thenReturn(List.of());
        when(genreRepository.save(any(Genre.class))).thenAnswer(invocation -> {
            Genre genre = invocation.getArgument(0);
            genre.setId(7L);
            return genre;
        });
        when(keywordRepository.save(any(Keyword.class))).thenAnswer(invocation -> {
            Keyword keyword = invocation.getArgument(0);
            keyword.setId(8L);
            return keyword;
        });

        Movie saved = movieService.saveOrUpdateMovieDetails(tmdbData, null);

        assertNotNull(saved);
        verify(genreRepository).save(any(Genre.class));
        verify(keywordRepository).save(any(Keyword.class));
        verify(movieGenreRepository).save(any(MovieGenre.class));
        verify(movieKeywordRepository).save(any(MovieKeyword.class));
    }

    /**
     * A partial TMDB detail response must never strip fields that already
     * qualify a movie for recommendation. Losing runtime, overview, poster, or
     * release date would silently evict rows from the recommendation-ready
     * pool, which is the failure mode the catalog stability audit ruled out.
     * That audit only runs against a cloned database, so this pins the
     * behaviour in the ordinary suite.
     */
    @Test
    void partialTmdbDetailResponseDoesNotClearPopulatedEligibilityFields() {
        Movie existing = TestFixtures.movie(11L, 27205, "Inception");
        existing.setOverview("A thief who steals corporate secrets.");
        existing.setPosterPath("/existing-poster.jpg");
        existing.setBackdropPath("/existing-backdrop.jpg");
        existing.setReleaseDate(java.time.LocalDate.of(2010, 7, 16));
        existing.setRuntime(148);
        existing.setMovieRating(8.4);

        MovieDetailDto partial = new MovieDetailDto(
                27205L, "Inception", "  ", "", null, null, null, null, null, List.of(), null, null);

        when(movieRepository.save(any(Movie.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Movie saved = movieService.saveOrUpdateMovieDetails(partial, existing);

        assertEquals("A thief who steals corporate secrets.", saved.getOverview());
        assertEquals("/existing-poster.jpg", saved.getPosterPath());
        assertEquals("/existing-backdrop.jpg", saved.getBackdropPath());
        assertEquals(java.time.LocalDate.of(2010, 7, 16), saved.getReleaseDate());
        assertEquals(148, saved.getRuntime());
        assertEquals(8.4, saved.getMovieRating());
    }

    @Test
    void searchMoviesPersistsReturnedResults() {
        MovieDto dto = TestFixtures.movieDto(100L, "Test Movie", List.of(12));
        SearchResponseDto response = new SearchResponseDto(1, List.of(dto), 1, 1);
        Genre genre = TestFixtures.genre(1L, 12, "Adventure");

        when(tmdbApiService.searchMovies("test", 1)).thenReturn(response);
        when(movieRepository.findByTmdbId(100)).thenReturn(Optional.empty());
        when(movieRepository.save(any(Movie.class))).thenAnswer(invocation -> {
            Movie movie = invocation.getArgument(0);
            movie.setId(5L);
            return movie;
        });
        when(genreRepository.findByTmdbIdIn(List.of(12))).thenReturn(List.of(genre));

        SearchResponseDto result = movieService.searchMovies("test", 1);

        assertEquals(1, result.getResults().size());
        verify(movieRepository).save(org.mockito.ArgumentMatchers.argThat(
                movie -> Integer.valueOf(4_000).equals(movie.getVoteCount())));
        verify(movieGenreRepository).save(any(MovieGenre.class));
    }
}

