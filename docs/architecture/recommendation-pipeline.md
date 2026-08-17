# Recommendation Pipeline Architecture

## Overview

AtlasWatch provides three recommendation pipelines, all orchestrated by `RecommendationService`:

| Pipeline       | Entry point                              | Auth required | Description                                            |
|----------------|------------------------------------------|---------------|--------------------------------------------------------|
| Personalized   | `getRecommendations(user, request)`      | Yes           | Full pipeline with taste profile, content similarity   |
| Cold-start     | `getColdStartRecommendations(request)`   | No            | Anonymous visitors; ranks by popularity & mood/runtime |
| Solo (watchlist)| `getSoloRecommendations(user, request)` | Yes           | Re-ranks the user's own watchlist entries               |

## Pipeline stages

```
Request
  |
  v
[Cache check] ──hit──> return cached response
  |
  miss
  v
[Catalog seed]          (ensures minimum catalog via CatalogIngestionService)
  |
  v
[Context build]         (taste profile, interaction history, penalty/suppression sets)
  |
  v
[Candidate retrieval]   (CandidateRetriever: genre-affinity, mood-aligned,
  |                       popular, high-rated, watchlist, content-similarity)
  v
[Scoring]               (RecommendationScorer: weighted feature scoring)
  |
  v
[Session-intent gates]  (per-mood coverage + exact runtime when pool permits)
  |
  v
[Diversity reranking]   (MMR-style Jaccard genre penalty)
  |
  v
[Response mapping]      (DTO conversion, reason text)
  |
  v
[Cache write + impressions]
  |
  v
Response
```

## Key components

### RecommendationService
Orchestrator. Manages StopWatch-based per-stage timing and Micrometer metrics (`recommendation.pipeline.duration`, `recommendation.cache`). It conditionally gates zero-mood and tolerance-only runtime candidates, then carries an intent-aware score through diversity and calibration. Delegates feature computation, candidate retrieval, and reason building to dedicated services.

### CandidateRetriever
Assembles a candidate pool from six sources: watchlist overlap, genre affinity, mood-aligned, popular, high-rated, and content-similarity. Each source is fetched, sampled, and deduped independently, then merged. Penalized and suppressed movie IDs are applied post-merge.

### RecommendationScorer
Computes a weighted score from ranking features (genre affinity, per-requested-mood coverage, runtime fit, quality, popularity, optional freshness, watchlist age, disliked-genre penalty). Weights are externalized in `RecommendationScoringProperties`; freshness defaults to zero until explicitly requested by product behavior. Also provides MMR-style diversity reranking via pairwise Jaccard genre similarity.

### ContentSimilarityService
TF-IDF cosine similarity over movie overviews. Used by `CandidateRetriever` to find movies textually similar to the user's top-rated or watchlisted seed movies.

### UserTasteProfileService
Builds a `UserTasteProfile` (genre weights, signal counts, cold-start flag) from the user's reviews and active watchlist entries. Profiles are cached in Redis (`userProfiles` cache, 10-min TTL).

### RecommendationReasonBuilder
Generates human-readable explanation strings for each recommendation based on which scoring features contributed most.

### RecommendationEvaluator
Offline evaluation utility computing hit rate, precision@K, catalog coverage, genre diversity, and intra-list similarity. Used by `RecommendationEvaluationTest` against a synthetic catalog. Baseline metrics are tracked in `docs/evaluation-baseline.md`.

## Caching strategy

| Cache name               | TTL    | Scope                | Eviction trigger                   |
|--------------------------|--------|----------------------|------------------------------------|
| `recommendations`        | 5 min  | Per user + request   | After first replay (single-use)    |
| `coldStartRecommendations`| 5 min | Per request params   | TTL expiry                         |
| `userProfiles`           | 10 min | Per user             | Review/watchlist write via `RecommendationCacheInvalidationService` |
| `trendingMovies`         | 10 min | Global               | TTL expiry                         |
| `movieDetails`           | 1 hr   | Per TMDB ID          | TTL expiry                         |

All cache reads/writes are wrapped in try-catch so Redis failures degrade gracefully.

Recommendation keys include an explicit algorithm version. Any ranking behavior change must increment it so cached output from an earlier model is not served under the new rules.

## Resilience (TMDB API)

`TmdbApiService` uses Resilience4j annotations:

- **Circuit breaker** (`tmdb`): count-based sliding window of 10 calls, opens at 50% failure rate, 30s wait in open state, 3 calls permitted in half-open. Records failures and re-throws.
- **Retry** (`tmdb`): up to 3 attempts with 500ms wait, retries on `RestClientException`. Owns the fallback methods.
- **Fallback**: attached to `@Retry` (not `@CircuitBreaker`) so retries fire before falling back. Each fallback returns `null`, preserving the existing caller contract.
- **Aspect order**: `Retry(CircuitBreaker(Method))` — the circuit breaker records failures and re-throws; the retry catches and retries; after exhausting attempts, the retry fallback returns null.

Resilience4j metrics are auto-published to Micrometer and exposed via the `/actuator/circuitbreakers` and `/actuator/retries` endpoints.

## Observability

- **Per-stage timing**: `StopWatch` logs a compact breakdown for every pipeline invocation (e.g., `candidateRetrieval=2ms, scoring=5ms, total=12ms`).
- **Micrometer timer**: `recommendation.pipeline.duration` tagged by `type` (personalized, cold-start, solo).
- **Micrometer counter**: `recommendation.cache` tagged by `result` (hit, miss, error) and `cache` (recommendations, coldStartRecommendations, userProfiles).
- **Actuator endpoints**: `/actuator/metrics`, `/actuator/health`, `/actuator/circuitbreakers`, `/actuator/retries`.

## Catalog ingestion

`CatalogIngestionService` populates the movie catalog from TMDB on a scheduled basis (default every 12 hours). Sources: popular, top-rated, trending, and per-genre discover lists. Uses an `AtomicBoolean` guard to prevent concurrent runs. When the catalog changes, all recommendation caches are evicted.
