package com.atlasmind.atlaswatch.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verifyNoInteractions;

import com.atlasmind.atlaswatch.AtlasWatchApplication;
import com.atlasmind.atlaswatch.dto.request.RecommendationRequestDto;
import com.atlasmind.atlaswatch.repository.GenreRepository;
import com.atlasmind.atlaswatch.repository.KeywordRepository;
import com.atlasmind.atlaswatch.repository.MovieGenreRepository;
import com.atlasmind.atlaswatch.repository.MovieKeywordRepository;
import com.atlasmind.atlaswatch.repository.MovieRepository;
import com.atlasmind.atlaswatch.repository.RecommendationImpressionRepository;
import com.atlasmind.atlaswatch.repository.ReviewRepository;
import com.atlasmind.atlaswatch.repository.UserRepository;
import com.atlasmind.atlaswatch.repository.WatchlistRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Proves the frozen evaluation catalog is replayable without mutable application state. */
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
@EnabledIfEnvironmentVariable(named = "ATLASWATCH_RUN_HERMETIC_EVALUATION_INFRA", matches = "true")
class HermeticEvaluationInfrastructureTest {

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

    // Replacing both TMDB beans is the structural half of the hermeticity
    // proof: the context cannot reach the network because it never builds an
    // HTTP client, and the assertion below shows nothing tried to.
    @MockitoBean RestClient tmdbRestClient;
    @MockitoBean TmdbApiService tmdbApiService;

    @Autowired ObjectMapper objectMapper;
    @Autowired MovieRepository movieRepository;
    @Autowired GenreRepository genreRepository;
    @Autowired KeywordRepository keywordRepository;
    @Autowired MovieGenreRepository movieGenreRepository;
    @Autowired MovieKeywordRepository movieKeywordRepository;
    @Autowired UserRepository userRepository;
    @Autowired ReviewRepository reviewRepository;
    @Autowired WatchlistRepository watchlistRepository;
    @Autowired RecommendationImpressionRepository impressionRepository;
    @Autowired RecommendationService recommendationService;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void replaysFrozenCatalogThroughFlywayWithoutExternalOrPersistentSideEffects() throws Exception {
        EvaluationCatalogDataset.Dataset frozen = EvaluationCatalogDataset.read(
                objectMapper, EvaluationCatalogDataset.RESOURCE_PATH);
        // Assert the migration outcome rather than a version string, so adding
        // a migration does not require editing this test. `db_watch_list` is
        // the sequence Hibernate resolves the WatchList entity to; a clean
        // Flyway database only carries it once V6 has run.
        assertEquals(0L, jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = false", Long.class),
                "Every migration must apply cleanly to an empty schema.");
        assertEquals(1L, jdbcTemplate.queryForObject(
                "select count(*) from pg_sequences where schemaname = 'public' and sequencename = 'db_watch_list'",
                Long.class),
                "Clean Flyway databases must expose the sequence Hibernate validates against.");

        EvaluationCatalogDataset.seed(
                frozen, movieRepository, genreRepository, keywordRepository,
                movieGenreRepository, movieKeywordRepository);
        EvaluationCatalogDataset.Dataset before = EvaluationCatalogDataset.capture(
                movieRepository, movieGenreRepository, movieKeywordRepository);
        assertEquals(frozen.catalogSize(), before.catalogSize());
        assertEquals(frozen.contentFingerprint(), before.contentFingerprint());

        RecommendationRequestDto request = new RecommendationRequestDto(List.of("tense"), "any", 5);
        request.setRefreshToken("hermetic-r0");
        RecommendationEvaluationPersona persona = new RecommendationEvaluationPersona(
                "hermetic-cold", false, List.of(), List.of());
        RecommendationEvaluationRun first = recommendationService.evaluateRecommendations(request, persona);
        RecommendationEvaluationRun replay = recommendationService.evaluateRecommendations(request, persona);

        assertFalse(first.items().isEmpty(), "Frozen catalog should produce a recommendation shortlist.");
        assertEquals(first.items().stream().map(RecommendationEvaluationRun.Item::tmdbId).toList(),
                replay.items().stream().map(RecommendationEvaluationRun.Item::tmdbId).toList(),
                "Identical hermetic requests must replay identically.");

        EvaluationCatalogDataset.Dataset after = EvaluationCatalogDataset.capture(
                movieRepository, movieGenreRepository, movieKeywordRepository);
        assertEquals(before.contentFingerprint(), after.contentFingerprint(),
                "Evaluation must not mutate ranking-relevant catalog content.");
        assertEquals(0, userRepository.count());
        assertEquals(0, reviewRepository.count());
        assertEquals(0, watchlistRepository.count());
        assertEquals(0, impressionRepository.count());
        verifyNoInteractions(tmdbApiService);
    }
}
