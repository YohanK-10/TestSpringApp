package com.atlasmind.ai_travel_recommendation.support;

import com.atlasmind.ai_travel_recommendation.dto.request.AddToWatchlistDto;
import com.atlasmind.ai_travel_recommendation.dto.request.CreateReviewDto;
import com.atlasmind.ai_travel_recommendation.dto.tmdb.MovieDetailDto;
import com.atlasmind.ai_travel_recommendation.dto.tmdb.MovieDto;
import com.atlasmind.ai_travel_recommendation.models.Genre;
import com.atlasmind.ai_travel_recommendation.models.Movie;
import com.atlasmind.ai_travel_recommendation.models.MovieGenre;
import com.atlasmind.ai_travel_recommendation.models.Review;
import com.atlasmind.ai_travel_recommendation.models.User;
import com.atlasmind.ai_travel_recommendation.models.WatchList;
import com.atlasmind.ai_travel_recommendation.models.WatchListStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class TestFixtures {

    private TestFixtures() {
    }

    public static User user(Long id, String username, String email) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword("encoded-password");
        user.setEnable(true);
        user.setLocked(false);
        return user;
    }

    public static Movie movie(Long id, Integer tmdbId, String title) {
        Movie movie = new Movie();
        movie.setId(id);
        movie.setTmdbId(tmdbId);
        movie.setMovieTitle(title);
        movie.setOverview(title + " overview");
        movie.setPosterPath("/poster.jpg");
        movie.setBackdropPath("/backdrop.jpg");
        movie.setReleaseDate(LocalDate.of(2024, 1, 1));
        movie.setMovieRating(8.1);
        movie.setRuntime(120);
        movie.setPopularity(99.9);
        movie.setCachedAt(LocalDateTime.now());
        return movie;
    }

    public static Genre genre(Long id, Integer tmdbId, String name) {
        Genre genre = new Genre();
        genre.setId(id);
        genre.setTmdbId(tmdbId);
        genre.setName(name);
        return genre;
    }

    public static MovieGenre movieGenre(Movie movie, Genre genre) {
        return new MovieGenre(movie, genre);
    }

    public static Review review(Long id, User user, Movie movie) {
        Review review = new Review();
        review.setId(id);
        review.setUser(user);
        review.setMovie(movie);
        review.setRating(9);
        review.setReviewText("Excellent movie");
        review.setContainsSpoilers(false);
        review.setCreatedAt(LocalDateTime.now().minusDays(1));
        review.setUpdatedAt(LocalDateTime.now());
        return review;
    }

    public static WatchList watchList(Long id, User user, Movie movie, WatchListStatus status) {
        WatchList watchList = new WatchList();
        watchList.setId(id);
        watchList.setUser(user);
        watchList.setMovie(movie);
        watchList.setStatus(status);
        watchList.setAddedAt(LocalDateTime.now());
        return watchList;
    }

    public static MovieDto movieDto(long tmdbId, String title, List<Integer> genreIds) {
        return new MovieDto(tmdbId, title, title + " overview", 88.8, "/poster.jpg",
                "/backdrop.jpg", "2024-01-01", 8.5, genreIds);
    }

    public static MovieDetailDto movieDetailDto(long tmdbId, String title, List<MovieDetailDto.Genre> genres) {
        return new MovieDetailDto(tmdbId, title, title + " overview", "/poster.jpg",
                "/backdrop.jpg", "2024-01-01", 8.7, 130, genres, 101.5);
    }

    public static CreateReviewDto createReviewDto(Integer tmdbId, Integer rating) {
        return new CreateReviewDto(tmdbId, rating, "Great film", false);
    }

    public static AddToWatchlistDto addToWatchlistDto(Integer tmdbId, String status) {
        return new AddToWatchlistDto(tmdbId, status);
    }
}
