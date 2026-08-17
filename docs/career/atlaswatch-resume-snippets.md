# AtlasWatch Resume Snippets

Use `AtlasWatch` as the project name on your resume. It is clearer and stronger than the repository/package name.

## Recommended Project Header

**AtlasWatch** | Spring Boot, Java 21, Next.js 15, React 19, TypeScript, PostgreSQL, Redis, Docker

Full-stack movie discovery and recommendation platform with CSRF-protected cookie authentication, TMDB-powered catalog ingestion, PostgreSQL full-text search, Redis caching, and an explainable multi-signal ranking engine.

## Resume Bullets: Best 3

- Built a containerized Java 21/Spring Boot and Next.js movie discovery platform with 28 REST endpoints, using PostgreSQL/Flyway for persistent catalog and user data, Redis for cache-backed retrieval, and CSRF-protected JWT cookie authentication.
- Designed an explainable hybrid recommendation engine that retrieves up to 250 candidates across 7 channels, ranks them with 11 behavioral, user, context, quality, and content signals, and applies intent-first diversity and taste-calibration re-ranking.
- Trained a 64-factor collaborative model on 100,836 timestamped ratings and independently audited it against item-KNN on 1,000,209 separate ratings, improving HR@10 by 58%, nDCG@10 by 55%, and catalog coverage by 27% before serving a deterministic 1.23 MB artifact in Java.
- Established automated release checks across backend tests, TMDB resilience and recommendation evaluation, ESLint, TypeScript, and Next.js production builds through GitHub Actions.

## Resume Bullets: Backend-Focused 4

- Engineered a Spring Boot backend for a movie recommendation platform with JWT authentication, refresh-token rotation, email verification, password reset, review management, and watchlist lifecycle support.
- Built a recommendation service that combines review/watchlist taste, semantic mood and content features, Bayesian rating confidence, an offline-trained collaborative signal, strict runtime/era intent, and explanation-aware diversity re-ranking.
- Integrated PostgreSQL and Redis for persistence and performance, including GIN-indexed full-text search for movie discovery and cache-backed trending/detail retrieval to reduce repeat external API calls.
- Added health-checked Docker Compose orchestration, GitHub Actions CI, backend unit/web/resilience tests, and frontend lint, type-check, and production-build gates.

## Resume Bullets: Concise 2

- Built `AtlasWatch`, a full-stack movie recommendation app with Spring Boot, Next.js, PostgreSQL, Redis, and Docker, featuring CSRF-protected auth, reviews, watchlists, and TMDB-powered movie discovery.
- Developed a personalized recommendation engine using review/watchlist history, content similarity, latent collaborative factors, explicit mood/runtime/era intent, and diversity-aware ranking, backed by temporal evaluation, automated tests, and CI.

## One-Line Project Description

Built a full-stack movie recommendation platform with hardened cookie auth, PostgreSQL full-text search, Redis caching, TMDB ingestion, and a personalized multi-signal ranking pipeline.

## ATS Keywords

Java, Spring Boot, Spring Security, REST API, JWT, Redis, PostgreSQL, JPA, Next.js, React, TypeScript, Docker, CI/CD, GitHub Actions, full-text search, caching, recommendation systems, backend engineering, full-stack development

## Interview Talking Points

- Recommendation logic is not just a popularity sort; it combines reviews, watchlist behavior, semantic content, an offline-trained latent collaborative model, and strict session intent.
- The collaborative model uses temporal positive holdouts. Its resume metrics come from a separate MovieLens 1M audit run once with a frozen 64-factor configuration against the item-KNN baseline, rather than from training accuracy or the development dataset used to choose settings.
- Search is backed by PostgreSQL full-text search instead of simple `ILIKE`, which gave a better scaling story and relevance ranking.
- The app has real product flows: registration, verification, login, refresh, password reset, reviews, watchlist management, and personalized recommendations.
- The project is reproducible and testable locally through health-checked Docker Compose, GitHub Actions checks, backend tests, and a frontend production build.

## What Not To Claim

- You may call the truncated-SVD component machine learning, latent-factor modeling, or collaborative filtering, but describe the wider system as hybrid and keep the MovieLens evaluation boundary explicit.
- Do not claim microservices, distributed systems, or real-time streaming architecture for this project.
- Do not claim deep learning, an online A/B lift, or a model trained from AtlasWatch users; the current collaborative artifact is truncated SVD trained on MovieLens.
- Do not say the product is publicly deployed, used by real customers, or proven at production scale until those statements are backed by a live environment and measurements.
