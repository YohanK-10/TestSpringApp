# AtlasWatch Recommendation Plan

## Purpose

This plan reflects the current recommendation system as it actually exists in the repo, plus the highest-value next improvements.

It replaces the older over-scoped rewrite plan.

The goal is not to rebuild the recommender from scratch. The goal is to turn an already-strong recommendation engine into something that is:

- easier to explain
- easier to maintain
- easier to measure
- more impressive for SWE interviews

## Current Reality

AtlasWatch already has a serious recommendation system.

What already exists:

- personalized recommendations across the catalog
- cold-start recommendations
- mood and runtime preferences
- multi-channel candidate retrieval
- content-similarity logic
- weighted scoring
- diversity reranking
- explanation generation
- recommendation impression tracking
- focused backend tests
- an offline-trained, Java-served collaborative item model
- synthetic and historical temporal evaluation layers, plus a frozen human-intent prompt/rubric awaiting labels
- strict release-era controls and semantic mood evidence

The core issue is not lack of functionality. The issue is packaging, measurability, and maintainability.

## Recent Progress

The following recommendation improvements are already complete:

- `RecommendationScorer` extracted from `RecommendationService`
- `RecommendationReasonBuilder` extracted from `RecommendationService`
- validation improved on recommendation request DTO/controller paths
- recommendation tests kept green after refactor

Current recommendation-related files of interest:

- `src/main/java/com/atlasmind/ai_travel_recommendation/service/RecommendationService.java`
- `src/main/java/com/atlasmind/ai_travel_recommendation/service/RecommendationScorer.java`
- `src/main/java/com/atlasmind/ai_travel_recommendation/service/RecommendationReasonBuilder.java`
- `src/main/java/com/atlasmind/ai_travel_recommendation/service/UserTasteProfileService.java`
- `src/test/java/com/atlasmind/ai_travel_recommendation/service/RecommendationServiceTest.java`

## What Still Feels Weak

Even though the logic is strong, the recommendation layer still has some issues:

- `RecommendationService` remains too large at roughly 1,300+ lines
- recommendation caching is not implemented
- user taste profile caching/eviction is not implemented
- there is no per-stage timing instrumentation
- AtlasWatch interaction volume is still too small for a reliable first-party collaborative model
- there is no concise architecture doc explaining the pipeline

There are also a few easy structural wins still sitting in the service:

- shared inner types like `SoloMood`, `RuntimePreference`, and recommendation records still create coupling
- several tiny private delegation wrappers remain in `RecommendationService` even though the work already moved into `RecommendationScorer`
- the TF-IDF/content-similarity block is still embedded in the service even though it is one of the most self-contained extraction targets

## Recommended Direction

Keep the recommendation system in Java inside the current Spring Boot backend.

Do not:

- split it into Python
- replace it with an overbuilt new package tree
- rebuild the whole pipeline before the current code is stabilized

Instead:

- extract the next obvious module
- add caching
- add timing
- add evaluation

## Priority Backlog

### P1: Finish Modularization

The next refactor steps should stay incremental.

Recommended order:

1. move shared inner types out first:
   - `SoloMood`
   - `RuntimePreference`
   - `PreparedCandidate`
   - `RecommendationContext`
   - any other recommendation-specific records that are currently nested only for convenience
2. delete dead delegation methods in `RecommendationService` that only forward to `RecommendationScorer`
3. extract a dedicated `ContentSimilarityService` for TF-IDF, tokenization, cosine similarity, and content-similarity candidate building
4. extract candidate retrieval / retrieval orchestration after the type graph is cleaner
5. avoid giant multi-file rewrites unless they clearly reduce complexity

Definition of done:

- `RecommendationService` becomes smaller and more orchestration-focused
- existing recommendation behavior stays stable
- recommendation tests still pass

### P1: Add Recommendation Caching

Add Redis-backed caching for recommendation responses.

Why:

- recommendation requests are significantly more expensive than trending/details lookups
- caching is a strong production-readiness signal

Suggested scope:

- cache recommendation responses for a short TTL, such as 5 minutes
- add a `userProfiles` cache for taste-profile computation
- evict or invalidate on review/watchlist changes

Cache-key guidance:

- authenticated recommendations should key on:
  - user ID
  - moods
  - runtime preference
  - limit
- cold-start recommendations should key on:
  - moods
  - runtime preference
  - limit

Important caution:

- `getRecommendations()` currently records impressions
- do not naively cache the whole method if that suppresses or duplicates impression writes
- either cache the pure ranking result below the impression-writing layer, or make impression recording explicitly compatible with cached reads

Definition of done:

- repeated identical recommendation requests are faster
- cache scope is understandable and not overengineered

### P1: Add Timing Instrumentation

Add timing around recommendation stages.

Suggested measurements:

- context/profile build
- candidate retrieval
- scoring/reranking
- response mapping

This can start with simple `StopWatch` logging before moving to metrics.

Definition of done:

- logs make bottlenecks visible
- you can describe approximate latency by stage in an interview

### Completed: Build Evaluation Harness

The deterministic Java regression harness and the external historical collaborative benchmark are now implemented. The next evaluation work is completing human session-intent labels and collecting enough first-party outcomes for a temporal AtlasWatch benchmark.

The implemented evaluation paths now answer:

- are recommendations getting better?
- are they too repetitive?
- how broad is coverage?

Recorded metrics include:

- Precision@K or hit-rate-like metric
- coverage
- genre diversity

First-party ground-truth remains future work once AtlasWatch has enough chronological activity:

- use existing review and watchlist data as implicit positive signals
- treat a recommendation as a hit when the user later:
  - reviews the movie positively (for example `>= 7/10`), or
  - adds it to their watchlist
- document the exact label choice so the evaluation is reproducible and understandable

Completed evidence:

- synthetic and historical results are stored in `docs/`
- Python and Java paths are reproducible and test-protected
- before/after claims use recorded absolute and relative metrics

### P2: Add Observability and Reliability

After caching/timing/evaluation:

- add Micrometer metrics for recommendation latency and cache behavior
- add Resilience4j for TMDB-related fallbacks/timeouts/retries where relevant
- add a short recommendation-pipeline architecture note or diagram in `docs/`

## What Not To Build Next

These are lower-value than the items above:

- social/follow/activity feed work
- a request-time Python ML service when a versioned artifact can be served by Java
- fully new recommendation package hierarchy with dozens of classes
- fancy models without measurable baseline evaluation

## Recommended Next Chunks

If working incrementally, the next recommendation chunks should be:

1. move inner recommendation types out of `RecommendationService`
2. delete dead scorer-delegation wrappers from `RecommendationService`
3. extract `ContentSimilarityService`
4. extract candidate retrieval from `RecommendationService`
5. complete the human session-intent labels and blind comparison
6. add first-party temporal evaluation once interaction volume is sufficient
7. compare the item model with matrix factorization or a learned ranker using development-only selection followed by a separately frozen audit
8. promote only models that preserve explicit-constraint and diversity guardrails

## Interview Story

The recommendation system should ultimately be explainable as:

- a hybrid recommender using multiple signals
- an offline-evaluated collaborative retrieval feature served from a compact artifact
- explicit scoring and explanation logic
- modular enough to reason about
- performance-aware through caching and timing
- measurable through offline evaluation

That is a much stronger SWE story than simply saying "I used AI recommendations."
