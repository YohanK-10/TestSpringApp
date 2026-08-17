package com.atlasmind.atlaswatch.service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public enum SoloMood {
    ANY("any", Set.of(), true, Set.of()),
    COMFORTING("comforting", Set.of("comedy", "family", "romance", "animation"), false,
            Set.of("heartwarming", "feel good", "found family", "healing", "friendship", "community", "gentle")),
    FUNNY("funny", Set.of("comedy", "animation"), true,
            Set.of("satire", "parody", "dark comedy", "screwball comedy", "absurdist humor", "stand up comedy")),
    TENSE("tense", Set.of("thriller", "mystery", "crime", "action"), true,
            Set.of("suspense", "race against time", "hostage", "manhunt", "survival", "high stakes", "conspiracy")),
    DARK("dark", Set.of("thriller", "horror", "crime"), false,
            Set.of("murder", "serial killer", "grim", "gore", "revenge", "corruption", "bleak", "dystopia", "psychological trauma")),
    EMOTIONAL("emotional", Set.of("drama", "romance"), false,
            Set.of("grief", "loss", "family relationships", "terminal illness", "tearjerker", "redemption", "reunion", "sacrifice")),
    THOUGHTFUL("thoughtful", Set.of("science fiction", "history", "documentary"), false,
            Set.of("philosophy", "existential", "social commentary", "ethics", "identity", "memory", "moral dilemma", "political")),
    ADVENTUROUS("adventurous", Set.of("adventure", "fantasy", "action", "science fiction"), true,
            Set.of("quest", "expedition", "treasure hunt", "journey", "exploration", "survival adventure")),
    COZY("cozy", Set.of("family", "romance", "comedy", "animation"), false,
            Set.of("small town", "friendship", "community", "baking", "holiday", "christmas", "feel good", "slice of life")),
    ROMANTIC("romantic", Set.of("romance"), true,
            Set.of("falling in love", "love story", "wedding", "romantic relationship", "first love")),
    EERIE("eerie", Set.of("horror", "mystery"), false,
            Set.of("supernatural", "haunted", "ghost", "uncanny", "occult", "curse", "mysterious disappearance", "survival horror")),
    HOPEFUL("hopeful", Set.of("family", "adventure", "drama", "animation"), false,
            Set.of("hope", "redemption", "second chance", "overcoming adversity", "dreams", "healing", "new beginning")),
    BITTERSWEET("bittersweet", Set.of("drama", "romance", "music"), false,
            Set.of("grief", "nostalgia", "loss", "coming of age", "sacrifice", "farewell", "reunion")),
    MIND_BENDING("mind bending", Set.of("science fiction", "mystery"), false,
            Set.of("time travel", "time loop", "alternate reality", "simulation", "parallel universe", "dream", "unreliable narrator", "nonlinear", "teleportation")),
    INSPIRING("inspiring", Set.of("history", "drama", "music", "adventure"), false,
            Set.of("based on true story", "overcoming adversity", "underdog", "achievement", "civil rights", "perseverance", "inspirational"));

    private static final Map<String, SoloMood> LOOKUP = new HashMap<>();

    static {
        for (SoloMood mood : values()) {
            LOOKUP.put(mood.label, mood);
        }
    }

    private final String label;
    private final Set<String> preferredGenres;
    private final boolean genreOnlyCoverage;
    private final Set<String> semanticCues;

    SoloMood(String label, Set<String> preferredGenres, boolean genreOnlyCoverage, Set<String> semanticCues) {
        this.label = label;
        this.preferredGenres = preferredGenres;
        this.genreOnlyCoverage = genreOnlyCoverage;
        this.semanticCues = semanticCues;
    }

    public String displayLabel() {
        return label.substring(0, 1).toUpperCase(Locale.ROOT) + label.substring(1);
    }

    public Set<String> preferredGenres() {
        return preferredGenres;
    }

    boolean isCovered(List<String> genres, List<String> keywords, String overview) {
        if (this == ANY) {
            return false;
        }
        if (hasSemanticEvidence(keywords, overview)) {
            return true;
        }
        if (!genreOnlyCoverage || genres == null) {
            return false;
        }
        return genres.stream()
                .map(SoloMood::normalizeEvidence)
                .anyMatch(preferredGenres::contains);
    }

    List<String> matchingSemanticCues(List<String> keywords, String overview) {
        String normalizedOverview = normalizeEvidence(overview);
        List<String> normalizedKeywords = keywords == null
                ? List.of()
                : keywords.stream().map(SoloMood::normalizeEvidence).filter(value -> !value.isBlank()).toList();

        return semanticCues.stream()
                .filter(cue -> containsPhrase(normalizedOverview, cue)
                        || normalizedKeywords.stream().anyMatch(keyword -> containsPhrase(keyword, cue)))
                .sorted()
                .toList();
    }

    boolean hasKeywordEvidence(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        return keywords.stream()
                .map(SoloMood::normalizeEvidence)
                .anyMatch(keyword -> semanticCues.stream().anyMatch(cue -> containsPhrase(keyword, cue)));
    }

    boolean hasOverviewEvidence(String overview) {
        String normalizedOverview = normalizeEvidence(overview);
        return semanticCues.stream().anyMatch(cue -> containsPhrase(normalizedOverview, cue));
    }

    boolean hasGenreEvidence(List<String> genres) {
        return genreOnlyCoverage && genres != null && genres.stream()
                .map(SoloMood::normalizeEvidence)
                .anyMatch(preferredGenres::contains);
    }

    private boolean hasSemanticEvidence(List<String> keywords, String overview) {
        return !matchingSemanticCues(keywords, overview).isEmpty();
    }

    private static boolean containsPhrase(String value, String phrase) {
        if (value == null || value.isBlank() || phrase == null || phrase.isBlank()) {
            return false;
        }
        return (" " + value + " ").contains(" " + phrase + " ");
    }

    private static String normalizeEvidence(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    public static Set<SoloMood> from(List<String> values, String fallbackValue) {
        List<String> rawValues = values == null || values.isEmpty()
                ? (fallbackValue == null || fallbackValue.isBlank() ? List.of("any") : List.of(fallbackValue))
                : values;

        Set<SoloMood> resolved = rawValues.stream()
                .map(SoloMood::fromSingle)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (resolved.isEmpty()) {
            return Set.of(ANY);
        }

        if (resolved.size() > 1) {
            resolved.remove(ANY);
        }

        return resolved.isEmpty() ? Set.of(ANY) : resolved;
    }

    private static SoloMood fromSingle(String value) {
        if (value == null || value.isBlank()) {
            return ANY;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        SoloMood mood = LOOKUP.get(normalized);
        if (mood == null) {
            throw new IllegalArgumentException("Invalid mood: '" + value + "'.");
        }
        return mood;
    }
}
