package com.atlasmind.atlaswatch.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlasmind.atlaswatch.AtlasWatchApplication;
import com.atlasmind.atlaswatch.config.CatalogIngestionProperties;
import com.atlasmind.atlaswatch.models.Movie;
import com.atlasmind.atlaswatch.repository.MovieGenreRepository;
import com.atlasmind.atlaswatch.repository.MovieKeywordRepository;
import com.atlasmind.atlaswatch.repository.MovieRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Opt-in, networked audit. It must only target a cloned database whose name
 * starts with atlaswatch_catalog_audit_. It performs one ingestion cycle.
 */
@SpringBootTest(
        classes = AtlasWatchApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "atlaswatch.catalog.ingestion.enabled=true",
                "atlaswatch.catalog.ingestion.initial-delay-ms=86400000",
                "atlaswatch.catalog.ingestion.fixed-delay-ms=86400000",
                "atlaswatch.catalog.ingestion.rate-limit-ms=0",
                "atlaswatch.catalog.metadata-backfill.run-on-startup=false",
                "spring.cache.type=none"
        })
@EnabledIfEnvironmentVariable(named = "ATLASWATCH_RUN_CATALOG_AUDIT", matches = "true")
class CatalogEligibilityAuditTest {

    private static final double MINIMUM_RATING = EvaluationCatalogDataset.MINIMUM_RATING;
    private static final Path OUTPUT_DIR = Path.of("docs/evaluation/runs/catalog-stability-v1");

    @Autowired CatalogIngestionService catalogIngestionService;
    @Autowired CatalogIngestionProperties ingestionProperties;
    @Autowired MovieRepository movieRepository;
    @Autowired MovieGenreRepository movieGenreRepository;
    @Autowired MovieKeywordRepository movieKeywordRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void auditExactlyOneIngestionCycleAndFreezePostCycleCatalog() throws Exception {
        String database = jdbcTemplate.queryForObject("select current_database()", String.class);
        assertTrue(database != null && database.startsWith("atlaswatch_catalog_audit_"),
                "Refusing to run catalog audit outside an isolated atlaswatch_catalog_audit_* database.");

        Map<Integer, EligibilityState> before = captureStates();
        catalogIngestionService.ingestCatalogNow();
        Map<Integer, EligibilityState> after = captureStates();

        List<EligibilityTransition> transitions = transitions(before, after);
        List<EligibilityTransition> entrants = transitions.stream().filter(item -> !item.before().eligible()
                && item.after().eligible()).toList();
        List<EligibilityTransition> exits = transitions.stream().filter(item -> item.before().eligible()
                && !item.after().eligible()).toList();
        List<EligibilityTransition> destructiveLosses = exits.stream()
                .filter(CatalogEligibilityAuditTest::lostPreviouslyPresentRequiredField)
                .toList();

        EvaluationCatalogDataset.Dataset dataset = EvaluationCatalogDataset.capture(
                movieRepository, movieGenreRepository, movieKeywordRepository);
        AuditArtifact artifact = new AuditArtifact(
                "catalog-stability-v1",
                Instant.now().toString(),
                database,
                cycleConfiguration(),
                summary(before),
                summary(after),
                entrants.size(),
                exits.size(),
                destructiveLosses.size(),
                reasonCounts(entrants, true),
                reasonCounts(exits, false),
                dataset.version(),
                dataset.catalogSize(),
                dataset.contentFingerprint(),
                transitions
        );

        Files.createDirectories(OUTPUT_DIR);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(OUTPUT_DIR.resolve("audit.json").toFile(), artifact);
        Files.writeString(OUTPUT_DIR.resolve("eligibility-diff.csv"), csv(transitions), StandardCharsets.UTF_8);
        Files.writeString(OUTPUT_DIR.resolve("audit.md"), markdown(artifact), StandardCharsets.UTF_8);
        EvaluationCatalogDataset.write(objectMapper, dataset, EvaluationCatalogDataset.RESOURCE_PATH);

        assertEquals(0, destructiveLosses.size(),
                "Ingestion erased one or more fields required for recommendation eligibility; inspect audit artifacts.");
        assertEquals(dataset.catalogSize(), after.values().stream().filter(EligibilityState::eligible).count());
    }

    private Map<Integer, EligibilityState> captureStates() {
        return movieRepository.findAll().stream()
                .sorted(Comparator.comparing(Movie::getTmdbId))
                .collect(Collectors.toMap(
                        Movie::getTmdbId,
                        this::state,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private EligibilityState state(Movie movie) {
        Set<String> reasons = new LinkedHashSet<>();
        if (movie.getMovieRating() == null) reasons.add("RATING_MISSING");
        else if (movie.getMovieRating() < MINIMUM_RATING) reasons.add("RATING_BELOW_MINIMUM");
        if (movie.getRuntime() == null) reasons.add("RUNTIME_MISSING");
        if (!hasText(movie.getPosterPath())) reasons.add("POSTER_MISSING");
        if (movie.getReleaseDate() == null) reasons.add("RELEASE_DATE_MISSING");
        if (!hasText(movie.getOverview())) reasons.add("OVERVIEW_MISSING");
        return new EligibilityState(
                movie.getTmdbId(), movie.getMovieTitle(), movie.getMovieRating(), movie.getRuntime(),
                movie.getReleaseDate() == null ? null : movie.getReleaseDate().toString(),
                hasText(movie.getPosterPath()), hasText(movie.getOverview()), reasons.isEmpty(), List.copyOf(reasons)
        );
    }

    private List<EligibilityTransition> transitions(
            Map<Integer, EligibilityState> before,
            Map<Integer, EligibilityState> after
    ) {
        Set<Integer> ids = new LinkedHashSet<>(before.keySet());
        ids.addAll(after.keySet());
        List<EligibilityTransition> result = new ArrayList<>();
        ids.stream().sorted().forEach(tmdbId -> {
            EligibilityState left = before.getOrDefault(tmdbId, EligibilityState.absent(tmdbId));
            EligibilityState right = after.getOrDefault(tmdbId, EligibilityState.absent(tmdbId));
            if (left.eligible() != right.eligible()) {
                result.add(new EligibilityTransition(tmdbId, transitionCode(left, right), left, right));
            }
        });
        return List.copyOf(result);
    }

    private String transitionCode(EligibilityState before, EligibilityState after) {
        if (!before.eligible() && after.eligible()) {
            return before.reasons().contains("NOT_IN_CATALOG") ? "ENTER_NEW_MOVIE" : "ENTER_REQUIREMENTS_SATISFIED";
        }
        if (after.reasons().contains("NOT_IN_CATALOG")) return "EXIT_REMOVED_FROM_CATALOG";
        if (after.reasons().contains("RATING_BELOW_MINIMUM")) return "EXIT_RATING_BELOW_MINIMUM";
        if (after.reasons().contains("RATING_MISSING")) return "EXIT_RATING_MISSING";
        if (after.reasons().contains("RUNTIME_MISSING")) return "EXIT_RUNTIME_MISSING";
        if (after.reasons().contains("POSTER_MISSING")) return "EXIT_POSTER_MISSING";
        if (after.reasons().contains("RELEASE_DATE_MISSING")) return "EXIT_RELEASE_DATE_MISSING";
        if (after.reasons().contains("OVERVIEW_MISSING")) return "EXIT_OVERVIEW_MISSING";
        return "ELIGIBILITY_CHANGED";
    }

    private CycleConfiguration cycleConfiguration() {
        return new CycleConfiguration(
                ingestionProperties.getPopularPages(),
                ingestionProperties.getTopRatedPages(),
                ingestionProperties.getTrendingPages(),
                ingestionProperties.getDiscoverPagesPerGenre(),
                List.copyOf(ingestionProperties.getDiscoverGenreIds()),
                ingestionProperties.getRateLimitMs()
        );
    }

    private CatalogSummary summary(Map<Integer, EligibilityState> states) {
        return new CatalogSummary(states.size(), states.values().stream().filter(EligibilityState::eligible).count(),
                states.values().stream().flatMap(item -> item.reasons().stream())
                        .collect(Collectors.groupingBy(reason -> reason, TreeMap::new, Collectors.counting())));
    }

    private Map<String, Long> reasonCounts(List<EligibilityTransition> transitions, boolean useBefore) {
        return transitions.stream()
                .flatMap(item -> (useBefore ? item.before().reasons() : item.after().reasons()).stream())
                .filter(reason -> !"NOT_IN_CATALOG".equals(reason))
                .collect(Collectors.groupingBy(reason -> reason, TreeMap::new, Collectors.counting()));
    }

    private static boolean lostPreviouslyPresentRequiredField(EligibilityTransition transition) {
        EligibilityState before = transition.before();
        EligibilityState after = transition.after();
        return (before.runtime() != null && after.runtime() == null)
                || (before.releaseDate() != null && after.releaseDate() == null)
                || (before.posterPresent() && !after.posterPresent())
                || (before.overviewPresent() && !after.overviewPresent())
                || (before.rating() != null && after.rating() == null);
    }

    private String csv(List<EligibilityTransition> transitions) {
        StringBuilder csv = new StringBuilder("tmdb_id,title,transition,before_rating,after_rating,before_reasons,after_reasons\n");
        transitions.forEach(item -> csv.append(item.tmdbId()).append(',')
                .append(quote(item.after().title() != null ? item.after().title() : item.before().title())).append(',')
                .append(item.code()).append(',')
                .append(value(item.before().rating())).append(',').append(value(item.after().rating())).append(',')
                .append(quote(String.join("|", item.before().reasons()))).append(',')
                .append(quote(String.join("|", item.after().reasons()))).append('\n'));
        return csv.toString();
    }

    private String markdown(AuditArtifact artifact) {
        long delta = artifact.after().recommendationReady() - artifact.before().recommendationReady();
        return """
                # Catalog stability audit v1

                - Database: `%s` (isolated clone; the test refuses any other database name)
                - Controlled cycles: **1**
                - Catalog before: %d total / %d recommendation-ready
                - Catalog after: %d total / %d recommendation-ready
                - Ready-pool delta: %+d
                - Entrants: %d
                - Exits: %d
                - Destructive required-field losses: %d
                - Frozen dataset: `%s`, %d movies, fingerprint `%s`

                ## Interpretation

                `eligibility-diff.csv` lists every movie that entered or left the ready pool and the exact reason codes.
                A rating that crosses 5.5 is classified separately from a required field becoming blank. The audit fails if
                ingestion erases a previously populated required field, after writing all evidence to disk.

                This is a networked forensic audit, not the normal CI evaluation. The frozen post-cycle JSON dataset is the
                immutable input used by the hermetic Testcontainers evaluation path.
                """.formatted(
                artifact.database(), artifact.before().total(), artifact.before().recommendationReady(),
                artifact.after().total(), artifact.after().recommendationReady(), delta,
                artifact.entrants(), artifact.exits(), artifact.destructiveRequiredFieldLosses(),
                artifact.datasetVersion(), artifact.datasetCatalogSize(), artifact.datasetContentFingerprint());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }

    record CycleConfiguration(int popularPages, int topRatedPages, int trendingPages,
                              int discoverPagesPerGenre, List<Integer> discoverGenreIds, long rateLimitMs) {}
    record CatalogSummary(long total, long recommendationReady, Map<String, Long> ineligibilityReasons) {}
    record EligibilityState(Integer tmdbId, String title, Double rating, Integer runtime, String releaseDate,
                            boolean posterPresent, boolean overviewPresent, boolean eligible, List<String> reasons) {
        static EligibilityState absent(Integer tmdbId) {
            return new EligibilityState(tmdbId, null, null, null, null, false, false, false,
                    List.of("NOT_IN_CATALOG"));
        }
    }
    record EligibilityTransition(Integer tmdbId, String code, EligibilityState before, EligibilityState after) {}
    record AuditArtifact(String auditVersion, String generatedAt, String database,
                         CycleConfiguration cycle, CatalogSummary before, CatalogSummary after,
                         int entrants, int exits, int destructiveRequiredFieldLosses,
                         Map<String, Long> entrantPriorReasons, Map<String, Long> exitReasons,
                         String datasetVersion, int datasetCatalogSize, String datasetContentFingerprint,
                         List<EligibilityTransition> transitions) {}
}
