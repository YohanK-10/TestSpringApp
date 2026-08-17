package com.atlasmind.atlaswatch.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
@EnabledIfEnvironmentVariable(named = "ATLASWATCH_RUN_LIVE_EVALUATION", matches = "true")
class RecommendationSessionBatchEvaluationTest {

    private static final int ROTATIONS = 10;
    private static final int LIMIT = 5;
    private static final int BOOTSTRAP_ITERATIONS = 2_000;
    private static final long BOOTSTRAP_SEED = 20260816L;

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

    @Autowired RecommendationService recommendationService;
    @Autowired MovieRepository movieRepository;
    @Autowired GenreRepository genreRepository;
    @Autowired KeywordRepository keywordRepository;
    @Autowired MovieGenreRepository movieGenreRepository;
    @Autowired MovieKeywordRepository movieKeywordRepository;
    @Autowired ObjectMapper objectMapper;

    @Test
    void generateVersionedLabelFreeSessionIntentBaseline() throws Exception {
        EvaluationCatalogDataset.Dataset frozen = EvaluationCatalogDataset.read(
                objectMapper, EvaluationCatalogDataset.RESOURCE_PATH);
        EvaluationCatalogDataset.seed(
                frozen, movieRepository, genreRepository, keywordRepository,
                movieGenreRepository, movieKeywordRepository);
        assertEquals(frozen.contentFingerprint(), EvaluationCatalogDataset.capture(
                        movieRepository, movieGenreRepository, movieKeywordRepository).contentFingerprint(),
                "The baseline must be measured on the frozen evaluation catalog.");

        List<Prompt> prompts = readPrompts(Path.of("docs/evaluation/session-intent-prompts-v1.csv"));
        List<Persona> personas = readPersonas(Path.of("docs/evaluation/session-intent-personas-v1.csv"));
        List<SessionIntentBatchEvaluator.Observation> observations = new ArrayList<>();

        for (Prompt prompt : prompts) {
            for (Persona persona : personas) {
                for (int rotation = 0; rotation < ROTATIONS; rotation++) {
                    RecommendationRequestDto request = request(prompt, rotation);
                    RecommendationEvaluationRun run = recommendationService.evaluateRecommendations(
                            request,
                            new RecommendationEvaluationPersona(
                                    persona.id(), persona.warm(), persona.genres(), persona.keywords())
                    );
                    observations.add(new SessionIntentBatchEvaluator.Observation(
                            prompt.id() + ":" + persona.id(),
                            prompt.id(),
                            prompt.primaryMood(),
                            requestedMoodCount(prompt),
                            persona.id(),
                            persona.warm(),
                            rotation,
                            LIMIT,
                            run
                    ));
                }
            }
        }

        // A fixed request must be replayable exactly; the rotation token, not
        // hidden mutable state, is the only source of sampling variation.
        var first = observations.getFirst();
        var replay = recommendationService.evaluateRecommendations(
                request(prompts.getFirst(), first.rotation()),
                new RecommendationEvaluationPersona(
                        personas.getFirst().id(), personas.getFirst().warm(),
                        personas.getFirst().genres(), personas.getFirst().keywords())
        );
        assertEquals(
                first.run().items().stream().map(RecommendationEvaluationRun.Item::tmdbId).toList(),
                replay.items().stream().map(RecommendationEvaluationRun.Item::tmdbId).toList()
        );

        assertEquals(frozen.contentFingerprint(), EvaluationCatalogDataset.capture(
                        movieRepository, movieGenreRepository, movieKeywordRepository).contentFingerprint(),
                "Evaluation must not mutate the frozen catalog.");
        var report = SessionIntentBatchEvaluator.evaluate(
                observations, frozen.catalogSize(), BOOTSTRAP_ITERATIONS, BOOTSTRAP_SEED);
        var artifact = new EvaluationArtifact(
                "session-intent-v1",
                observations.getFirst().run().algorithmVersion(),
                frozen.version(),
                frozen.catalogSize(),
                frozen.contentFingerprint(),
                Instant.now().toString(),
                prompts.size(),
                personas.size(),
                ROTATIONS,
                "Rule-derived mood coverage is a circular implementation guardrail, not semantic relevance evidence.",
                report
        );

        Path outputDirectory = Path.of("docs/evaluation/runs/session-intent-v1");
        Files.createDirectories(outputDirectory);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                outputDirectory.resolve("baseline.json").toFile(), artifact);
        Files.writeString(outputDirectory.resolve("observations.csv"), observationsCsv(observations), StandardCharsets.UTF_8);
        Files.writeString(outputDirectory.resolve("baseline.md"), markdown(artifact), StandardCharsets.UTF_8);
    }

    private RecommendationRequestDto request(Prompt prompt, int rotation) {
        RecommendationRequestDto request = new RecommendationRequestDto();
        List<String> moods = new ArrayList<>();
        moods.add(prompt.primaryMood());
        moods.addAll(prompt.secondaryMoods());
        request.setMoods(moods);
        request.setRuntimePreference(prompt.runtime());
        request.setReleaseEras(List.of(prompt.era()));
        request.setLimit(LIMIT);
        request.setRefreshToken("evaluation-r" + rotation);
        return request;
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

    private String observationsCsv(List<SessionIntentBatchEvaluator.Observation> observations) {
        StringBuilder csv = new StringBuilder("cluster_id,prompt_id,primary_mood,persona_id,warm,rotation,rank,tmdb_id,title,mood_coverage,evidence_source,runtime_satisfied,era_satisfied,duration_ms,merged_candidates,source_channels\n");
        for (var observation : observations) {
            for (var item : observation.run().items()) {
                csv.append(csv(observation.clusterId())).append(',')
                        .append(csv(observation.promptId())).append(',')
                        .append(csv(observation.primaryMood())).append(',')
                        .append(csv(observation.personaId())).append(',')
                        .append(observation.warmPersona()).append(',')
                        .append(observation.rotation()).append(',')
                        .append(item.rank()).append(',')
                        .append(item.tmdbId()).append(',')
                        .append(csv(item.title())).append(',')
                        .append(String.format(Locale.ROOT, "%.6f", item.moodCoverage())).append(',')
                        .append(item.evidenceSource()).append(',')
                        .append(item.runtimeSatisfied()).append(',')
                        .append(item.eraSatisfied()).append(',')
                        .append(String.format(Locale.ROOT, "%.3f", observation.run().durationNanos() / 1_000_000.0)).append(',')
                        .append(observation.run().mergedCandidateCount()).append(',')
                        .append(csv(String.join("|", item.sourceChannels()))).append('\n');
            }
        }
        return csv.toString();
    }

    private String markdown(EvaluationArtifact artifact) {
        var report = artifact.report();
        StringBuilder text = new StringBuilder();
        text.append("# Session-intent label-free baseline v1\n\n")
                .append("- Algorithm: `").append(artifact.algorithmVersion()).append("`\n")
                .append("- Evaluation catalog: `").append(artifact.datasetVersion()).append("` (")
                .append(artifact.catalogSize()).append(" recommendation-ready movies)\n")
                .append("- Content fingerprint: `").append(artifact.contentFingerprint()).append("`\n")
                .append("- Matrix: ").append(artifact.promptCount()).append(" prompts × ")
                .append(artifact.personaCount()).append(" personas × ").append(artifact.rotations())
                .append(" rotations = ").append(report.sessions()).append(" sessions\n")
                .append("- Shortlist size: 5\n")
                .append("- Bootstrap: 2,000 cluster resamples at prompt/persona session level\n\n")
                .append("> ").append(artifact.guardrailCaveat()).append("\n\n")
                .append("## Overall metrics\n\n")
                .append("| Metric | Value | 95% cluster-bootstrap CI |\n|---|---:|---:|\n");
        metric(text, "Full-shortlist rate", report.fullShortlistRate(), report.confidenceIntervals().get("fullShortlistRate"));
        metric(text, "Mean rule-derived mood coverage", report.meanRuleMoodCoverage(), report.confidenceIntervals().get("meanRuleMoodCoverage"));
        metric(text, "Primary-mood violation rate", report.primaryMoodViolationRate(), report.confidenceIntervals().get("primaryMoodViolationRate"));
        metric(text, "Consecutive-rotation overlap", report.meanRotationOverlap(), report.confidenceIntervals().get("meanRotationOverlap"));
        text.append("| Mean slot fill | ").append(format(report.meanSlotFillRate())).append(" | — |\n")
                .append("| Unique-result rate | ").append(format(report.meanUniqueResultRate())).append(" | — |\n")
                .append("| Top-1 repeat rate | ").append(format(report.topOneRepeatRate())).append(" | — |\n")
                .append("| Catalog coverage | ").append(format(report.catalogCoverage())).append(" | — |\n")
                .append("| Runtime violation rate | ").append(format(report.runtimeViolationRate())).append(" | — |\n")
                .append("| Era violation rate | ").append(format(report.eraViolationRate())).append(" | — |\n")
                .append("| Genre diversity | ").append(format(report.meanGenreDiversity())).append(" | — |\n")
                .append("| Intra-list similarity | ").append(format(report.meanIntraListSimilarity())).append(" | — |\n")
                .append("| Latency p50 | ").append(String.format(Locale.ROOT, "%.1f ms", report.latencyP50Ms())).append(" | — |\n")
                .append("| Latency p95 | ").append(String.format(Locale.ROOT, "%.1f ms", report.latencyP95Ms())).append(" | — |\n\n")
                .append("## Required diagnostic slices\n\n")
                .append("| Dimension | Slice | Sessions | Recommendations | Full shortlist | Mood coverage | Primary violation |\n|---|---|---:|---:|---:|---:|---:|\n");
        report.slices().forEach(slice -> text.append("| ").append(slice.dimension()).append(" | ")
                .append(slice.value()).append(" | ").append(format(slice.sessions())).append(" | ")
                .append(slice.recommendations()).append(" | ").append(format(slice.fullShortlistRate())).append(" | ")
                .append(format(slice.meanRuleMoodCoverage())).append(" | ")
                .append(format(slice.primaryMoodViolationRate())).append(" |\n"));
        text.append("\n## Interpretation boundary\n\n")
                .append("This report measures fill, constraint consistency, rotation, coverage, diversity, and latency. ")
                .append("It does **not** establish human semantic relevance. The mood values are generated by the same rules used by the ranker and are therefore circular guardrails. Human/AI-assisted graded labels remain a later, separately versioned phase.\n");
        return text.toString();
    }

    private void metric(StringBuilder text, String label, double value, SessionIntentBatchEvaluator.ConfidenceInterval interval) {
        text.append("| ").append(label).append(" | ").append(format(value)).append(" | [")
                .append(format(interval.lower95())).append(", ").append(format(interval.upper95())).append("] |\n");
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /**
     * Session-scoped values are undefined for item-level slices. Render them as
     * an em dash so the table cannot be misread as a measured zero.
     */
    private String format(Double value) {
        return value == null ? "—" : format(value.doubleValue());
    }

    private String format(Integer value) {
        return value == null ? "—" : value.toString();
    }

    /** `any` is a control prompt that expresses no mood constraint. */
    private int requestedMoodCount(Prompt prompt) {
        return ("any".equalsIgnoreCase(prompt.primaryMood()) ? 0 : 1) + prompt.secondaryMoods().size();
    }

    private String csv(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }

    private record Prompt(String id, String primaryMood, List<String> secondaryMoods, String runtime, String era) {}
    private record Persona(String id, boolean warm, List<String> genres, List<String> keywords) {}
    private record EvaluationArtifact(
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
            SessionIntentBatchEvaluator.BatchReport report
    ) {}
}
