package com.atlasmind.atlaswatch.service;

import com.atlasmind.atlaswatch.config.RecommendationScoringProperties;
import com.atlasmind.atlaswatch.models.Movie;
import com.atlasmind.atlaswatch.models.WatchList;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RecommendationReasonBuilder {

    private final RecommendationScoringProperties scoringProperties;

    List<String> buildWatchlistReasons(
            WatchList entry,
            Movie movie,
            Set<SoloMood> moods,
            RuntimePreference runtimePreference,
            UserTasteProfile tasteProfile,
            List<String> genres,
            List<String> keywords,
            RecommendationScorer.RankingFeatures features
    ) {
        List<String> reasons = new ArrayList<>();

        addWatchlistAgeReason(entry, features.watchlistAge(), reasons);
        addMoodReason(movie, moods, genres, keywords, features.moodMatch(), reasons);
        addRuntimeReason(runtimePreference, features.runtimeMatch(), reasons);
        addTasteReason(tasteProfile, genres, keywords, features, reasons);
        addQualityReason(movie, false, features.quality(), reasons);
        addPopularityReason(movie, false, features.popularity(), reasons);
        addFreshnessReason(movie, features.freshness(), reasons);

        if (reasons.isEmpty()) {
            reasons.add("It is still one of the strongest unfinished options in your current watchlist.");
        }

        return dedupeReasons(reasons);
    }

    List<String> buildCatalogReasons(
            Movie movie,
            Set<SoloMood> moods,
            RuntimePreference runtimePreference,
            Set<ReleaseEra> releaseEras,
            UserTasteProfile tasteProfile,
            List<String> genres,
            List<String> keywords,
            RecommendationScorer.RankingFeatures features,
            int sourceCount,
            double contentSimilarityScore,
            List<Movie> contentSimilaritySeeds,
            boolean coldStart
    ) {
        List<String> reasons = new ArrayList<>();

        if (features.watchlistBoost() > 0) {
            reasons.add("It is already on your watchlist, so this lines up with something you were already curious about.");
        }
        addMoodReason(movie, moods, genres, keywords, features.moodMatch(), reasons);
        addRuntimeReason(runtimePreference, features.runtimeMatch(), reasons);
        addReleaseEraReason(movie, releaseEras, reasons);
        addTasteReason(tasteProfile, genres, keywords, features, reasons);
        addCollaborativeReason(features.collaborative(), reasons);
        addSourceCountReason(sourceCount, reasons);
        addContentSimilarityReason(contentSimilarityScore, contentSimilaritySeeds, reasons);
        addQualityReason(movie, coldStart, features.quality(), reasons);
        addPopularityReason(movie, coldStart, features.popularity(), reasons);
        addFreshnessReason(movie, features.freshness(), reasons);

        if (reasons.isEmpty()) {
            if (coldStart) {
                reasons.add("It is a strong wider-catalog pick while AtlasWatch learns your taste.");
            } else {
                reasons.add("It fits the strongest combination of mood, runtime, and taste signals available right now.");
            }
        }

        return dedupeReasons(reasons);
    }

    private void addWatchlistAgeReason(WatchList entry, double watchlistAgeScore, List<String> reasons) {
        if (entry == null || entry.getAddedAt() == null || watchlistAgeScore <= 0.0) {
            return;
        }

        long days = Math.max(0, Duration.between(entry.getAddedAt(), LocalDateTime.now()).toDays());
        if (days >= 180) {
            reasons.add("It has been sitting in your watchlist for a while, so this is a good time to finally watch it.");
        } else if (days >= 60) {
            reasons.add("It has been on your watchlist long enough to deserve a bump.");
        }
    }

    private void addMoodReason(
            Movie movie,
            Set<SoloMood> moods,
            List<String> genres,
            List<String> keywords,
            double moodMatchScore,
            List<String> reasons
    ) {
        if (moodMatchScore <= 0.0 || !hasMoodIntent(moods)) {
            return;
        }

        List<SoloMood> matchedMoods = moods.stream()
                .filter(mood -> mood != SoloMood.ANY)
                .filter(mood -> mood.isCovered(genres, keywords, movie != null ? movie.getOverview() : null))
                .toList();
        if (matchedMoods.isEmpty()) {
            return;
        }

        List<String> moodLabels = matchedMoods.stream()
                .map(SoloMood::displayLabel)
                .toList();

        List<String> semanticCues = matchedMoods.stream()
                .flatMap(mood -> mood.matchingSemanticCues(
                        keywords,
                        movie != null ? movie.getOverview() : null
                ).stream())
                .distinct()
                .limit(3)
                .toList();

        if (!semanticCues.isEmpty()) {
            reasons.add("It has direct " + humanizeLabels(moodLabels) + " story signals such as "
                    + humanizeLabels(semanticCues) + ".");
            return;
        }

        List<String> matches = matchingMoodGenres(new LinkedHashSet<>(matchedMoods), genres);
        if (matches.isEmpty()) {
            return;
        }

        reasons.add("It matches your " + humanizeLabels(moodLabels) + " vibe mix through " + humanizeGenres(matches) + ".");
    }

    private void addRuntimeReason(
            RuntimePreference runtimePreference,
            double runtimeMatchScore,
            List<String> reasons
    ) {
        if (runtimePreference == RuntimePreference.ANY || runtimeMatchScore < 0.95) {
            return;
        }

        reasons.add("Its runtime fits your " + runtimePreference.label() + " preference.");
    }

    private void addReleaseEraReason(Movie movie, Set<ReleaseEra> releaseEras, List<String> reasons) {
        if (movie == null || movie.getReleaseDate() == null || !ReleaseEra.hasIntent(releaseEras)) {
            return;
        }

        List<String> matchingEras = releaseEras.stream()
                .filter(era -> era.matches(movie.getReleaseDate()))
                .map(ReleaseEra::displayLabel)
                .toList();
        if (!matchingEras.isEmpty()) {
            reasons.add("Its " + movie.getReleaseDate().getYear() + " release fits your "
                    + humanizeLabels(matchingEras) + " era selection.");
        }
    }

    private void addTasteReason(
            UserTasteProfile tasteProfile,
            List<String> genres,
            List<String> keywords,
            RecommendationScorer.RankingFeatures features,
            List<String> reasons
    ) {
        double positiveGenreReasonThreshold = scoringProperties.getPositiveGenreReasonThreshold();
        if (tasteProfile == null
                || !tasteProfile.hasSignals()
                || features.tasteAffinity() < positiveGenreReasonThreshold
                || features.tasteAffinity() < features.dislikedTastePenalty()) {
            return;
        }

        List<String> positiveMatches = genres.stream()
                .map(this::normalize)
                .distinct()
                .filter(genre -> tasteProfile.netWeight(genre) >= positiveGenreReasonThreshold)
                .toList();

        if (!positiveMatches.isEmpty()) {
            reasons.add("It lines up with genres you tend to rate highly, like " + humanizeGenres(positiveMatches) + ".");
            return;
        }

        List<String> positiveKeywordMatches = keywords.stream()
                .map(this::normalize)
                .distinct()
                .filter(keyword -> tasteProfile.netKeywordWeight(keyword) >= positiveGenreReasonThreshold)
                .limit(3)
                .toList();
        if (!positiveKeywordMatches.isEmpty()) {
            reasons.add("It lines up with themes you tend to respond well to, like "
                    + humanizeLabels(positiveKeywordMatches) + ".");
        }
    }

    private void addQualityReason(Movie movie, boolean coldStart, double qualityScore, List<String> reasons) {
        if (qualityScore < 0.55 || movie.getMovieRating() == null) {
            return;
        }

        reasons.add(coldStart
                ? "It has one of the stronger audience ratings in the wider catalog."
                : "It also stands out as one of the stronger-rated matches here.");
    }

    private void addPopularityReason(Movie movie, boolean coldStart, double popularityScore, List<String> reasons) {
        if (popularityScore < 0.55 || movie.getPopularity() == null) {
            return;
        }

        reasons.add(coldStart
                ? "It is also one of the more popular catalog options right now."
                : "It has enough popularity to make it a safer all-around pick.");
    }

    private void addFreshnessReason(Movie movie, double freshnessScore, List<String> reasons) {
        if (scoringProperties.getFreshnessWeight() <= 0.0
                || freshnessScore < 0.60
                || movie.getReleaseDate() == null) {
            return;
        }

        reasons.add("It is also a relatively recent release, which can help when you want something fresher.");
    }

    private void addSourceCountReason(int sourceCount, List<String> reasons) {
        if (sourceCount < 2) {
            return;
        }

        reasons.add(sourceCount >= 3
                ? "Multiple recommendation signals all surfaced this, so it kept winning across the pool."
                : "More than one recommendation signal pointed to this, which makes it a sturdier pick.");
    }

    private void addCollaborativeReason(double collaborativeScore, List<String> reasons) {
        if (collaborativeScore >= 0.35) {
            reasons.add("MovieLens viewing patterns connect it strongly to movies you already liked.");
        }
    }

    private void addContentSimilarityReason(
            double contentSimilarityScore,
            List<Movie> contentSimilaritySeeds,
            List<String> reasons
    ) {
        if (contentSimilarityScore < RecommendationScorer.MIN_CONTENT_SIMILARITY_SCORE
                || contentSimilaritySeeds == null
                || contentSimilaritySeeds.isEmpty()) {
            return;
        }

        List<String> seedTitles = contentSimilaritySeeds.stream()
                .map(Movie::getMovieTitle)
                .filter(Objects::nonNull)
                .filter(title -> !title.isBlank())
                .limit(2)
                .toList();

        if (seedTitles.isEmpty()) {
            reasons.add("Its plot and overall premise are close to movies you have already responded well to.");
            return;
        }

        reasons.add("Its plot and overall premise are close to " + humanizeLabels(seedTitles) + ".");
    }

    private boolean hasMoodIntent(Set<SoloMood> moods) {
        return moods != null
                && !(moods.size() == 1 && moods.contains(SoloMood.ANY))
                && !moods.isEmpty();
    }

    private List<String> matchingMoodGenres(Set<SoloMood> moods, List<String> genres) {
        if (!hasMoodIntent(moods) || genres.isEmpty()) {
            return List.of();
        }
        Set<String> preferredGenres = moods.stream()
                .filter(mood -> mood != SoloMood.ANY)
                .map(SoloMood::preferredGenres)
                .flatMap(Set::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return genres.stream()
                .map(this::normalize)
                .filter(preferredGenres::contains)
                .distinct()
                .toList();
    }

    private List<String> dedupeReasons(List<String> reasons) {
        return new ArrayList<>(
                new LinkedHashSet<>(reasons).stream()
                        .limit(3)
                        .toList()
        );
    }

    private String humanizeGenres(List<String> genres) {
        return genres.stream()
                .map(genre -> genre.substring(0, 1).toUpperCase(Locale.ROOT) + genre.substring(1))
                .collect(Collectors.joining(", "));
    }

    private String humanizeLabels(List<String> labels) {
        return String.join(", ", labels);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
