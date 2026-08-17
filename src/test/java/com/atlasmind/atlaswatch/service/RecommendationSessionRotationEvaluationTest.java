package com.atlasmind.atlaswatch.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlasmind.atlaswatch.AtlasWatchApplication;
import com.atlasmind.atlaswatch.dto.request.RecommendationRequestDto;
import com.atlasmind.atlaswatch.repository.GenreRepository;
import com.atlasmind.atlaswatch.repository.KeywordRepository;
import com.atlasmind.atlaswatch.repository.MovieGenreRepository;
import com.atlasmind.atlaswatch.repository.MovieKeywordRepository;
import com.atlasmind.atlaswatch.repository.MovieRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
        classes = AtlasWatchApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "atlaswatch.catalog.ingestion.enabled=false",
                "atlaswatch.catalog.metadata-backfill.run-on-startup=false",
                "spring.cache.type=none",
                "spring.jpa.hibernate.ddl-auto=validate",
                "logging.level.com.atlasmind.atlaswatch.service.CandidateRetriever=WARN",
                "logging.level.com.atlasmind.atlaswatch.service.RecommendationService=WARN"
        })
@EnabledIfEnvironmentVariable(named = "ATLASWATCH_RUN_PHASE2_EVALUATION", matches = "true")
class RecommendationSessionRotationEvaluationTest {

    // A disposable database is the point: the earlier Phase 2 run shared a live
    // catalog with a running backend, so scheduled ingestion moved the input
    // mid-evaluation and the recorded snapshot no longer described the run.
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("atlaswatch_evaluation")
            .withUsername("atlaswatch")
            .withPassword("atlaswatch");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean RestClient tmdbRestClient;
    @MockitoBean TmdbApiService tmdbApiService;

    private static final int ROTATIONS = 10;
    private static final int LIMIT = 5;
    private static final int BOOTSTRAP_ITERATIONS = 2_000;
    private static final long BOOTSTRAP_SEED = 20260817L;
    private static final List<String> PAIRED_METRICS = List.of(
            "fullShortlistRate",
            "meanSlotFillRate",
            "meanRuleMoodCoverage",
            "primaryMoodViolationRate",
            "meanRotationOverlap",
            "meanUniqueResultRate",
            "topOneRepeatRate",
            "runtimeViolationRate",
            "eraViolationRate"
    );

    @Autowired RecommendationService recommendationService;
    @Autowired MovieRepository movieRepository;
    @Autowired GenreRepository genreRepository;
    @Autowired KeywordRepository keywordRepository;
    @Autowired MovieGenreRepository movieGenreRepository;
    @Autowired MovieKeywordRepository movieKeywordRepository;
    @Autowired ObjectMapper objectMapper;

    @Test
    void compareSessionAwareRotationAgainstSameSnapshotControl() throws Exception {
        EvaluationCatalogDataset.Dataset frozen = EvaluationCatalogDataset.read(
                objectMapper, EvaluationCatalogDataset.RESOURCE_PATH);
        EvaluationCatalogDataset.seed(
                frozen, movieRepository, genreRepository, keywordRepository,
                movieGenreRepository, movieKeywordRepository);
        EvaluationCatalogDataset.Dataset seeded = EvaluationCatalogDataset.capture(
                movieRepository, movieGenreRepository, movieKeywordRepository);
        assertEquals(frozen.contentFingerprint(), seeded.contentFingerprint(),
                "Both variants must start from the frozen evaluation catalog.");

        List<Prompt> prompts = readPrompts(Path.of("docs/evaluation/session-intent-prompts-v1.csv"));
        List<Persona> personas = readPersonas(Path.of("docs/evaluation/session-intent-personas-v1.csv"));
        List<SessionIntentBatchEvaluator.Observation> control = new ArrayList<>();
        List<SessionIntentBatchEvaluator.Observation> challenger = new ArrayList<>();

        for (Prompt prompt : prompts) {
            for (Persona persona : personas) {
                Set<Integer> sessionSeen = new LinkedHashSet<>();
                for (int rotation = 0; rotation < ROTATIONS; rotation++) {
                    RecommendationEvaluationPersona evaluationPersona = new RecommendationEvaluationPersona(
                            persona.id(), persona.warm(), persona.genres(), persona.keywords());
                    RecommendationEvaluationRun controlRun = recommendationService.evaluateRecommendations(
                            request(prompt, rotation, List.of()), evaluationPersona);
                    RecommendationEvaluationRun challengerRun = recommendationService.evaluateRecommendations(
                            request(prompt, rotation, new ArrayList<>(sessionSeen)), evaluationPersona);
                    control.add(observation(prompt, persona, rotation, controlRun));
                    challenger.add(observation(prompt, persona, rotation, challengerRun));
                    challengerRun.items().stream()
                            .map(RecommendationEvaluationRun.Item::tmdbId)
                            .filter(java.util.Objects::nonNull)
                            .forEach(sessionSeen::add);
                }
            }
        }

        // Assert the input again after 1,600 requests. A fingerprint recorded
        // only at the end cannot prove the catalog held still during the run.
        EvaluationCatalogDataset.Dataset afterRun = EvaluationCatalogDataset.capture(
                movieRepository, movieGenreRepository, movieKeywordRepository);
        assertEquals(frozen.contentFingerprint(), afterRun.contentFingerprint(),
                "Evaluation must not mutate the frozen catalog.");
        int catalogSize = frozen.catalogSize();
        SessionIntentBatchEvaluator.BatchReport controlReport = SessionIntentBatchEvaluator.evaluate(
                control, catalogSize, BOOTSTRAP_ITERATIONS, BOOTSTRAP_SEED);
        SessionIntentBatchEvaluator.BatchReport challengerReport = SessionIntentBatchEvaluator.evaluate(
                challenger, catalogSize, BOOTSTRAP_ITERATIONS, BOOTSTRAP_SEED);
        Map<String, SessionIntentBatchEvaluator.ConfidenceInterval> pairedDeltas = pairedDeltas(
                control, challenger, catalogSize);

        // Every cluster's first rotation has no prior slate, so the variants
        // must be identical before session history begins influencing selection.
        assertEquals(firstRotationIdsByCluster(control), firstRotationIdsByCluster(challenger));

        Phase2Artifact artifact = new Phase2Artifact(
                "session-intent-v1-phase2-rotation",
                challenger.getFirst().run().algorithmVersion(),
                frozen.version(),
                frozen.catalogSize(),
                frozen.contentFingerprint(),
                Instant.now().toString(),
                prompts.size(),
                personas.size(),
                ROTATIONS,
                "Control disables session exclusion; challenger accumulates prior displayed TMDB IDs. "
                        + "Rule-derived mood coverage is circular and is not semantic relevance evidence.",
                controlReport,
                challengerReport,
                pairedDeltas
        );

        Path outputDirectory = Path.of("docs/evaluation/runs/session-intent-v1");
        Files.createDirectories(outputDirectory);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                outputDirectory.resolve("phase2-rotation.json").toFile(), artifact);
        Files.writeString(
                outputDirectory.resolve("phase2-rotation-observations.csv"),
                observationsCsv(control, challenger),
                StandardCharsets.UTF_8
        );
        Files.writeString(
                outputDirectory.resolve("phase2-rotation.md"),
                markdown(artifact),
                StandardCharsets.UTF_8
        );

        // Phase 2 promotion gates. They run after artifact creation so a
        // rejected challenger still leaves inspectable evidence. Mood coverage
        // remains a circular rule-based guardrail, not semantic relevance.
        assertTrue(challengerReport.fullShortlistRate() + 1e-12 >= controlReport.fullShortlistRate(),
                "Rotation must not cost shortlist fill.");
        assertTrue(challengerReport.meanRuleMoodCoverage() + 1e-12 >= controlReport.meanRuleMoodCoverage(),
                "Rotation must not cost rule-derived mood coverage.");
        // Runtime and era are user-visible constraints, so recycling an unseen
        // candidate may not quietly buy rotation with a worse-fitting film.
        // The gate is the paired CI rather than the point estimate: a delta
        // whose interval spans zero is noise, not a regression.
        assertTrue(pairedDeltas.get("runtimeViolationRate").upper95() <= 1e-12,
                "Challenger increased runtime violations with a paired CI excluding zero.");
        assertTrue(pairedDeltas.get("eraViolationRate").upper95() <= 1e-12,
                "Challenger increased era violations with a paired CI excluding zero.");
        assertTrue(challengerReport.meanRotationOverlap() < controlReport.meanRotationOverlap());
        assertTrue(challengerReport.meanUniqueResultRate() > controlReport.meanUniqueResultRate());
        assertTrue(challengerReport.topOneRepeatRate() < controlReport.topOneRepeatRate());
    }

    private SessionIntentBatchEvaluator.Observation observation(
            Prompt prompt,
            Persona persona,
            int rotation,
            RecommendationEvaluationRun run
    ) {
        return new SessionIntentBatchEvaluator.Observation(
                prompt.id() + ":" + persona.id(),
                prompt.id(),
                prompt.primaryMood(),
                ("any".equalsIgnoreCase(prompt.primaryMood()) ? 0 : 1) + prompt.secondaryMoods().size(),
                persona.id(),
                persona.warm(),
                rotation,
                LIMIT,
                run
        );
    }

    private RecommendationRequestDto request(Prompt prompt, int rotation, List<Integer> seenTmdbIds) {
        RecommendationRequestDto request = new RecommendationRequestDto();
        List<String> moods = new ArrayList<>();
        moods.add(prompt.primaryMood());
        moods.addAll(prompt.secondaryMoods());
        request.setMoods(moods);
        request.setRuntimePreference(prompt.runtime());
        request.setReleaseEras(List.of(prompt.era()));
        request.setLimit(LIMIT);
        request.setRefreshToken("evaluation-r" + rotation);
        request.setSeenTmdbIds(seenTmdbIds.stream().limit(50).toList());
        return request;
    }

    private Map<String, SessionIntentBatchEvaluator.ConfidenceInterval> pairedDeltas(
            List<SessionIntentBatchEvaluator.Observation> control,
            List<SessionIntentBatchEvaluator.Observation> challenger,
            int catalogSize
    ) {
        Map<String, SessionIntentBatchEvaluator.ConfidenceInterval> result = new LinkedHashMap<>();
        for (int index = 0; index < PAIRED_METRICS.size(); index++) {
            String metric = PAIRED_METRICS.get(index);
            result.put(metric, SessionIntentBatchEvaluator.pairedClusterBootstrapDelta(
                    SessionIntentBatchEvaluator.metricByCluster(control, catalogSize, metric),
                    SessionIntentBatchEvaluator.metricByCluster(challenger, catalogSize, metric),
                    BOOTSTRAP_ITERATIONS,
                    BOOTSTRAP_SEED + index
            ));
        }
        return Map.copyOf(result);
    }

    private List<Prompt> readPrompts(Path path) throws Exception {
        return Files.readAllLines(path).stream().skip(1).filter(line -> !line.isBlank()).map(line -> {
            String[] values = line.split(",", -1);
            return new Prompt(values[1], values[2], splitPipe(values[3]), values[4], values[5]);
        }).toList();
    }

    private List<Persona> readPersonas(Path path) throws Exception {
        return Files.readAllLines(path).stream().skip(1).filter(line -> !line.isBlank()).map(line -> {
            String[] values = line.split(",", -1);
            return new Persona(values[1], "warm".equalsIgnoreCase(values[2]), splitPipe(values[3]), splitPipe(values[4]));
        }).toList();
    }

    private List<String> splitPipe(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split("\\|"));
    }

    private List<Integer> ids(SessionIntentBatchEvaluator.Observation observation) {
        return observation.run().items().stream().map(RecommendationEvaluationRun.Item::tmdbId).toList();
    }

    private Map<String, List<Integer>> firstRotationIdsByCluster(
            List<SessionIntentBatchEvaluator.Observation> observations
    ) {
        return observations.stream()
                .filter(observation -> observation.rotation() == 0)
                .collect(Collectors.toMap(
                        SessionIntentBatchEvaluator.Observation::clusterId,
                        this::ids,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private String observationsCsv(
            List<SessionIntentBatchEvaluator.Observation> control,
            List<SessionIntentBatchEvaluator.Observation> challenger
    ) {
        StringBuilder csv = new StringBuilder(
                "variant,cluster_id,prompt_id,primary_mood,persona_id,warm,rotation,rank,tmdb_id,title,mood_coverage,evidence_source,runtime_satisfied,era_satisfied,duration_ms\n");
        appendObservations(csv, "control", control);
        appendObservations(csv, "challenger", challenger);
        return csv.toString();
    }

    private void appendObservations(
            StringBuilder csv,
            String variant,
            List<SessionIntentBatchEvaluator.Observation> observations
    ) {
        for (var observation : observations) {
            for (var item : observation.run().items()) {
                csv.append(csv(variant)).append(',')
                        .append(csv(observation.clusterId())).append(',')
                        .append(csv(observation.promptId())).append(',')
                        .append(csv(observation.primaryMood())).append(',')
                        .append(csv(observation.personaId())).append(',')
                        .append(observation.warmPersona()).append(',')
                        .append(observation.rotation()).append(',')
                        .append(item.rank()).append(',')
                        .append(item.tmdbId()).append(',')
                        .append(csv(item.title())).append(',')
                        .append(format(item.moodCoverage())).append(',')
                        .append(item.evidenceSource()).append(',')
                        .append(item.runtimeSatisfied()).append(',')
                        .append(item.eraSatisfied()).append(',')
                        .append(String.format(Locale.ROOT, "%.3f", observation.run().durationNanos() / 1_000_000.0))
                        .append('\n');
            }
        }
    }

    private String markdown(Phase2Artifact artifact) {
        StringBuilder text = new StringBuilder("# Phase 2 — session-aware rotation\n\n")
                .append("- Algorithm: `").append(artifact.algorithmVersion()).append("`\n")
                .append("- Evaluation catalog: `").append(artifact.datasetVersion()).append("` (")
                .append(artifact.catalogSize()).append(" recommendation-ready movies)\n")
                .append("- Content fingerprint: `").append(artifact.contentFingerprint()).append("`\n")
                .append("- Matrix per variant: ").append(artifact.promptCount()).append(" prompts × ")
                .append(artifact.personaCount()).append(" personas × ").append(artifact.rotations())
                .append(" rotations = ").append(artifact.control().sessions()).append(" sessions\n")
                .append("- Bootstrap: 2,000 paired prompt/persona cluster resamples\n\n")
                .append("> ").append(artifact.guardrailCaveat()).append("\n\n")
                .append("| Metric | Control | Challenger | Paired delta | 95% paired CI |\n")
                .append("|---|---:|---:|---:|---:|\n");
        comparison(text, "Full-shortlist rate", "fullShortlistRate",
                artifact.control().fullShortlistRate(), artifact.challenger().fullShortlistRate(), artifact);
        comparison(text, "Mean slot fill", "meanSlotFillRate",
                artifact.control().meanSlotFillRate(), artifact.challenger().meanSlotFillRate(), artifact);
        comparison(text, "Rule-derived mood coverage", "meanRuleMoodCoverage",
                artifact.control().meanRuleMoodCoverage(), artifact.challenger().meanRuleMoodCoverage(), artifact);
        comparison(text, "Primary-mood violation", "primaryMoodViolationRate",
                artifact.control().primaryMoodViolationRate(), artifact.challenger().primaryMoodViolationRate(), artifact);
        comparison(text, "Consecutive overlap", "meanRotationOverlap",
                artifact.control().meanRotationOverlap(), artifact.challenger().meanRotationOverlap(), artifact);
        comparison(text, "Unique-result rate", "meanUniqueResultRate",
                artifact.control().meanUniqueResultRate(), artifact.challenger().meanUniqueResultRate(), artifact);
        comparison(text, "Top-1 repeat rate", "topOneRepeatRate",
                artifact.control().topOneRepeatRate(), artifact.challenger().topOneRepeatRate(), artifact);
        comparison(text, "Runtime violation rate", "runtimeViolationRate",
                artifact.control().runtimeViolationRate(), artifact.challenger().runtimeViolationRate(), artifact);
        comparison(text, "Era violation rate", "eraViolationRate",
                artifact.control().eraViolationRate(), artifact.challenger().eraViolationRate(), artifact);
        text.append("\n## Promotion result\n\n")
                .append("Phase 2 passes only when fill and rule-derived mood coverage do not regress, ")
                .append("runtime and era violations do not rise with a paired CI excluding zero, ")
                .append("and overlap and top-1 repetition fall while unique-result rate rises. ")
                .append("These gates do not claim human relevance.\n");
        return text.toString();
    }

    private void comparison(
            StringBuilder text,
            String label,
            String metric,
            double control,
            double challenger,
            Phase2Artifact artifact
    ) {
        var delta = artifact.pairedDeltas().get(metric);
        text.append("| ").append(label).append(" | ").append(format(control)).append(" | ")
                .append(format(challenger)).append(" | ").append(format(delta.estimate())).append(" | [")
                .append(format(delta.lower95())).append(", ").append(format(delta.upper95())).append("] |\n");
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private String csv(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }

    private record Prompt(String id, String primaryMood, List<String> secondaryMoods, String runtime, String era) {}
    private record Persona(String id, boolean warm, List<String> genres, List<String> keywords) {}
    private record Phase2Artifact(
            String benchmarkVersion,
            String algorithmVersion,
            String datasetVersion,
            int catalogSize,
            String contentFingerprint,
            String generatedAt,
            int promptCount,
            int personaCount,
            int rotations,
            String guardrailCaveat,
            SessionIntentBatchEvaluator.BatchReport control,
            SessionIntentBatchEvaluator.BatchReport challenger,
            Map<String, SessionIntentBatchEvaluator.ConfidenceInterval> pairedDeltas
    ) {}
}
