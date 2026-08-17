package com.atlasmind.atlaswatch.service;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Stable, versioned input for recommendation evaluation. */
final class EvaluationCatalogDataset {

    static final double MINIMUM_RATING = 5.5;
    static final String VERSION = "session-intent-catalog-v1";
    static final Path RESOURCE_PATH = Path.of(
            "src/test/resources/evaluation/catalog/session-intent-catalog-v1.json");
    private static final LocalDateTime FIXED_CACHE_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);

    private EvaluationCatalogDataset() {}

    static Dataset capture(
            MovieRepository movieRepository,
            MovieGenreRepository movieGenreRepository,
            MovieKeywordRepository movieKeywordRepository
    ) {
        List<Movie> movies = movieRepository.findRecommendationReadyMovies(MINIMUM_RATING).stream()
                .sorted(Comparator.comparing(Movie::getTmdbId))
                .toList();
        List<Long> movieIds = movies.stream().map(Movie::getId).toList();

        Map<Long, List<Signal>> genres = movieIds.isEmpty() ? Map.of() : movieGenreRepository
                .findByMovieIdInWithGenre(movieIds).stream()
                .collect(Collectors.groupingBy(
                        link -> link.getMovie().getId(),
                        Collectors.mapping(link -> new Signal(
                                link.getGenre().getTmdbId(), link.getGenre().getName()), Collectors.toList())
                ));
        Map<Long, List<Signal>> keywords = movieIds.isEmpty() ? Map.of() : movieKeywordRepository
                .findByMovieIdInWithKeyword(movieIds).stream()
                .collect(Collectors.groupingBy(
                        link -> link.getMovie().getId(),
                        Collectors.mapping(link -> new Signal(
                                link.getKeyword().getTmdbId(), link.getKeyword().getName()), Collectors.toList())
                ));

        List<MovieEntry> entries = movies.stream().map(movie -> new MovieEntry(
                movie.getTmdbId(),
                movie.getMovieTitle(),
                movie.getOverview(),
                movie.getPosterPath(),
                movie.getBackdropPath(),
                movie.getReleaseDate() == null ? null : movie.getReleaseDate().toString(),
                movie.getRuntime(),
                movie.getMovieRating(),
                movie.getVoteCount(),
                movie.getPopularity(),
                movie.getSemanticMetadataSyncedAt() != null,
                sortedSignals(genres.get(movie.getId())),
                sortedSignals(keywords.get(movie.getId()))
        )).toList();
        Dataset unsigned = new Dataset(VERSION, entries.size(), "", entries);
        return new Dataset(VERSION, entries.size(), fingerprint(unsigned), entries);
    }

    static Dataset read(ObjectMapper objectMapper, Path path) throws IOException {
        Dataset dataset = objectMapper.readValue(path.toFile(), Dataset.class);
        validate(dataset);
        return dataset;
    }

    static void write(ObjectMapper objectMapper, Dataset dataset, Path path) throws IOException {
        validate(dataset);
        Files.createDirectories(path.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), dataset);
    }

    static void seed(
            Dataset dataset,
            MovieRepository movieRepository,
            GenreRepository genreRepository,
            KeywordRepository keywordRepository,
            MovieGenreRepository movieGenreRepository,
            MovieKeywordRepository movieKeywordRepository
    ) {
        validate(dataset);
        Map<Integer, Signal> genreSignals = uniqueSignals(dataset, MovieEntry::genres);
        Map<Integer, Signal> keywordSignals = uniqueSignals(dataset, MovieEntry::keywords);

        Map<Integer, Genre> genres = genreSignals.values().stream()
                .map(signal -> new Genre(null, signal.name(), signal.tmdbId()))
                .map(genreRepository::save)
                .collect(Collectors.toMap(Genre::getTmdbId, Function.identity()));
        Map<Integer, Keyword> keywords = keywordSignals.values().stream()
                .map(signal -> new Keyword(null, signal.name(), signal.tmdbId()))
                .map(keywordRepository::save)
                .collect(Collectors.toMap(Keyword::getTmdbId, Function.identity()));

        Map<Integer, Movie> movies = new LinkedHashMap<>();
        for (MovieEntry entry : dataset.movies()) {
            Movie movie = new Movie();
            movie.setTmdbId(entry.tmdbId());
            movie.setMovieTitle(entry.title());
            movie.setOverview(entry.overview());
            movie.setPosterPath(entry.posterPath());
            movie.setBackdropPath(entry.backdropPath());
            movie.setReleaseDate(entry.releaseDate() == null ? null : java.time.LocalDate.parse(entry.releaseDate()));
            movie.setRuntime(entry.runtime());
            movie.setMovieRating(entry.rating());
            movie.setVoteCount(entry.voteCount());
            movie.setPopularity(entry.popularity());
            movie.setCachedAt(FIXED_CACHE_TIME);
            movie.setSemanticMetadataSyncedAt(entry.semanticMetadataComplete() ? FIXED_CACHE_TIME : null);
            movies.put(entry.tmdbId(), movieRepository.save(movie));
        }
        movieRepository.flush();

        List<MovieGenre> movieGenres = new ArrayList<>();
        List<MovieKeyword> movieKeywords = new ArrayList<>();
        for (MovieEntry entry : dataset.movies()) {
            Movie movie = movies.get(entry.tmdbId());
            entry.genres().forEach(signal -> movieGenres.add(new MovieGenre(movie, genres.get(signal.tmdbId()))));
            entry.keywords().forEach(signal -> movieKeywords.add(new MovieKeyword(movie, keywords.get(signal.tmdbId()))));
        }
        movieGenreRepository.saveAll(movieGenres);
        movieKeywordRepository.saveAll(movieKeywords);
    }

    static String fingerprint(Dataset dataset) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, dataset.version());
        dataset.movies().stream().sorted(Comparator.comparing(MovieEntry::tmdbId)).forEach(movie -> {
            append(canonical, movie.tmdbId());
            append(canonical, movie.title());
            append(canonical, movie.overview());
            append(canonical, movie.posterPath());
            append(canonical, movie.backdropPath());
            append(canonical, movie.releaseDate());
            append(canonical, movie.runtime());
            append(canonical, movie.rating() == null ? null : Double.toHexString(movie.rating()));
            append(canonical, movie.voteCount());
            append(canonical, movie.popularity() == null ? null : Double.toHexString(movie.popularity()));
            append(canonical, movie.semanticMetadataComplete());
            movie.genres().stream().sorted(Signal.ORDER).forEach(signal -> appendSignal(canonical, "G", signal));
            movie.keywords().stream().sorted(Signal.ORDER).forEach(signal -> appendSignal(canonical, "K", signal));
        });
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void validate(Dataset dataset) {
        if (!VERSION.equals(dataset.version())) {
            throw new IllegalArgumentException("Unsupported evaluation catalog version: " + dataset.version());
        }
        if (dataset.catalogSize() != dataset.movies().size()) {
            throw new IllegalArgumentException("Evaluation catalog size does not match its movie entries.");
        }
        String computed = fingerprint(dataset);
        if (!computed.equals(dataset.contentFingerprint())) {
            throw new IllegalArgumentException("Evaluation catalog fingerprint mismatch.");
        }
    }

    private static Map<Integer, Signal> uniqueSignals(
            Dataset dataset, Function<MovieEntry, List<Signal>> extractor
    ) {
        return dataset.movies().stream().flatMap(movie -> extractor.apply(movie).stream())
                .sorted(Signal.ORDER)
                .collect(Collectors.toMap(Signal::tmdbId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    private static List<Signal> sortedSignals(List<Signal> signals) {
        return signals == null ? List.of() : signals.stream().sorted(Signal.ORDER).toList();
    }

    private static void appendSignal(StringBuilder target, String kind, Signal signal) {
        append(target, kind);
        append(target, signal.tmdbId());
        append(target, signal.name());
    }

    private static void append(StringBuilder target, Object value) {
        String text = value == null ? "<null>" : value.toString();
        target.append(text.length()).append(':').append(text).append(';');
    }

    record Dataset(String version, int catalogSize, String contentFingerprint, List<MovieEntry> movies) {
        Dataset {
            movies = movies == null ? List.of() : List.copyOf(movies);
        }
    }

    record MovieEntry(
            Integer tmdbId,
            String title,
            String overview,
            String posterPath,
            String backdropPath,
            String releaseDate,
            Integer runtime,
            Double rating,
            Integer voteCount,
            Double popularity,
            boolean semanticMetadataComplete,
            List<Signal> genres,
            List<Signal> keywords
    ) {
        MovieEntry {
            genres = genres == null ? List.of() : List.copyOf(genres);
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }

    record Signal(Integer tmdbId, String name) {
        private static final Comparator<Signal> ORDER = Comparator
                .comparing(Signal::tmdbId).thenComparing(Signal::name);
    }
}
