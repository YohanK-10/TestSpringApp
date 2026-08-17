# AtlasWatch Project Plan

## Goal

AtlasWatch should read as a strong new grad SWE project for big-tech-style backend roles:

- one coherent Java/Spring Boot codebase
- clear recommendation-system thinking without unnecessary ML/service sprawl
- strong trust signals: reproducible setup, tests, CI, schema discipline
- measurable technical depth: caching, performance notes, recommendation evaluation, reliability

This document is the current source of truth for what the project is, what is already done, and what should happen next.

## Current State

AtlasWatch is already a substantial full-stack movie app, not a blank-slate MVP.

What exists today:

- Spring Boot backend with auth, reviews, watchlist, TMDB integration, Redis, and catalog ingestion
- Next.js frontend with browse/search/details/auth/recommendation flows
- PostgreSQL full-text search on movies
- Docker Compose local stack
- Recommendation engine with:
  - cold-start handling
  - multi-channel retrieval
  - hybrid scoring
  - diversity reranking
  - impression tracking
  - reason/explanation output

## Completed Improvements

The following important cleanup and architecture work has already been done:

- Docker Compose networking fixes for Postgres and Redis
- environment/config cleanup around datasource naming
- README improvements for setup and recommendations visibility
- recommendation validation cleanup at the controller boundary
- recommendation scoring extracted into `RecommendationScorer`
- recommendation explanation logic extracted into `RecommendationReasonBuilder`
- Flyway added for schema management
- baseline migration added at `src/main/resources/db/migration/V1__initial_schema.sql`
- Hibernate moved from `ddl-auto=update` to `ddl-auto=validate`
- host-side backend verification passes with `mvn test`

## What Matters Most Now

The project no longer needs random feature additions. It needs focused improvements that increase credibility and technical depth.

The biggest remaining opportunities are:

1. unify project identity and remove remaining stale naming drift
2. make the recommendation system easier to explain and measure
3. add production-style trust signals around reliability, caching, and observability
4. produce evidence that the recommendation quality is improving

## What Not To Prioritize

These are not the right focus for AtlasWatch right now:

- Python microservice split for recommendations
- Kafka or microservices
- social/feed features as the main next step
- broad UI polish without backend depth improvements
- adding more CRUD features just to increase feature count
- giant recommender rewrites that replace already-working logic

## Recommended Architecture Direction

Keep AtlasWatch as a Java-first modular monolith.

Why:

- this is better aligned with new grad SWE positioning than a Java + Python split
- the strongest value here is backend engineering and recommendation-system design, not ML specialization
- a single coherent codebase is easier to demo, explain, and trust

Recommendation work should continue inside the monolith through modular extraction, caching, timing, and evaluation.

## Priority Roadmap

### Priority 0: Finish Trust and Identity Cleanup

These are still high-value because they affect first impressions and repo clarity.

- decide the exact rename scope and execute it deliberately
- update CI environment variables and verify the workflow still passes
- make sure fresh setup documentation matches the Flyway-based workflow

Current known identity drift still includes:

- Java package names under `com.atlasmind.ai_travel_recommendation`
- logging namespace still using `com.atlasmind.ai_travel_recommendation`
- assorted backend references that still describe the project as `ai-travel-recommendation`

Current CI status:

- GitHub Actions already exists at `.github/workflows/ci.yml`
- the workflow still uses `SPRING_DATABASE_URL` for the datasource URL and should be aligned with `SPRING_DATASOURCE_URL`
- the username/password env vars are already consistent with the current backend config:
  - `SPRING_DATABASE_USERNAME`
  - `SPRING_DATABASE_PASSWORD`
- the `push` branch filter is still commented out and should be intentionally reviewed

Recommended decision on rename scope:

- rename backend-facing docs and metadata immediately
- rename the Java package only if done as a single controlled refactor with `mvn test` verification afterward
- do not spend time renaming parent filesystem folders just for polish

Priority 0 is done when:

- the remaining backend/project identity drift is intentionally resolved or explicitly deferred
- `.github/workflows/ci.yml` matches the current datasource/env configuration
- the backend test suite stays green after the cleanup
- README/setup docs accurately reflect the current Flyway-based workflow

### Priority 1: Recommendation System Elevation

This is the highest-value technical substance area.

- continue modularizing `RecommendationService` using the extraction order in `docs/recommendation-engine-plan.md`
- add recommendation-result caching
- add cached user-taste-profile invalidation on review/watchlist changes
- add per-stage timing logs for retrieval, scoring, reranking, and explanation

Priority 1 is done when:

- `RecommendationService` is materially smaller and more orchestration-focused
- recommendation caching and invalidation are implemented with explicit cache-key design
- timing instrumentation exposes where time is spent in the recommendation pipeline
- recommendation behavior remains stable and tests stay green

### Priority 2: Recommendation Quality Evidence

This is one of the strongest differentiators for SWE interviews.

- add an offline evaluation harness
- measure at least:
  - Precision@K or hit-rate style signal
  - catalog coverage
  - diversity / genre spread
- document before/after results in `docs/`

Priority 2 is done when:

- there is a documented offline evaluation flow
- recommendation changes can be compared with at least a small set of repeatable metrics
- the results live in-repo and can be referenced in interviews

### Priority 3: Reliability and Observability

- add Resilience4j around TMDB calls
- add Micrometer metrics for recommendation latency and cache behavior
- document a small load/performance verification pass

Priority 3 is done when:

- TMDB-facing calls have basic timeout/retry/fallback protection
- recommendation latency and cache behavior are visible through logs or metrics
- there is a short written performance/reliability note in `docs/`

### Priority 4: Portfolio Polish

- tighten README further with architecture and performance notes
- add Swagger / SpringDoc if kept lightweight
- add a concise recommendation-pipeline architecture note or diagram
- ensure the repo tells a clean story end to end

Priority 4 is done when:

- the README quickly communicates system design and tradeoffs
- the recommendation pipeline is documented clearly enough to explain in an interview
- optional developer-experience polish like lightweight API docs is in place if it adds signal

## Definition of "Impressive" For This Project

AtlasWatch should feel impressive because it shows engineering judgment, not because it has the maximum number of features.

The target story is:

- I built a full-stack movie platform with authentication, catalog ingestion, reviews, watchlist, and a hybrid recommendation engine.
- I used PostgreSQL, Redis, Flyway, Docker, CI, and structured tests to make it credible and reproducible.
- I improved recommendation quality through modular scoring, explainable outputs, and measurable evaluation rather than hand-wavy AI claims.
- I deliberately kept it as a coherent Java backend instead of adding unnecessary system complexity.

## Success Criteria

AtlasWatch is in the right final state when:

- the repo identity is fully coherent
- the backend test suite is green
- database setup is reproducible from migrations
- the recommender is modular enough to explain clearly in an interview
- recommendation quality is supported by at least some measurable evidence
- the README and docs make the project easy to trust quickly
