# AtlasWatch

AtlasWatch is a full-stack movie discovery, tracking, and recommendation platform built with Spring Boot and Next.js. It combines TMDB catalog ingestion with a local PostgreSQL search index, explainable hybrid ranking, account-based reviews, and personal watchlists.

Its recommender combines explicit session constraints, review/watchlist taste, semantic content similarity, confidence-adjusted catalog quality, and a compact offline-trained collaborative model. Recommendation changes are checked with deterministic regression tests and chronological MovieLens evaluation; a versioned human-intent rubric is ready for blind labeling rather than being replaced by screenshots.

The MVP keeps browsing public, while account-only actions such as watchlist updates and review submission require authentication.

## MVP features

- public movie browsing, search, and detail pages
- email-based sign up and account verification
- login, logout, refresh-token session handling
- forgot-password flow with emailed reset codes
- review creation for authenticated users
- watchlist management with `PLAN_TO_WATCH` and `WATCHED`
- **personalised movie recommendations** - ranked from seven retrieval channels using semantic mood, runtime/era intent, review/watchlist taste, Bayesian quality, content similarity, 64-factor collaborative retrieval, and diversity calibration
- cold-start recommendations for unauthenticated or new users (no history required)
- PostgreSQL persistence for core app data
- Redis-backed caching for trending and recommendation flows
- Docker Compose setup for local full-stack runs
- CSRF-protected cookie authentication with refresh-token rotation
- Resilience4j retry and circuit-breaker protection for TMDB calls

## Architecture

AtlasWatch has four main moving parts:

- `moviehub-frontend/`: Next.js 15 frontend for browsing, auth pages, watchlist, and reviews
- `src/main/java/...`: Spring Boot 3 backend with Spring Security, JPA, Redis, and mail integration
- PostgreSQL: primary relational database
- Redis: cache layer for high-read movie flows

### Domain model

Your UML/domain model is included below because it gives the clearest overview of how the backend entities relate to each other.

<img src="docs/assets/atlas_domain.png" alt="AtlasWatch domain model" width="1100" />

### Backend relationship summary

- `User` owns `Review`, `RefreshToken`, and `WatchList` records
- `Movie` connects to `Review`, `WatchList`, and `Genre`
- `MovieGenre` models the many-to-many relationship between `Movie` and `Genre`
- `WatchListStatus` captures the viewing lifecycle

## Tech stack

### Frontend

- Next.js 15
- React 19
- TypeScript
- Tailwind CSS 4

### Backend

- Java 21
- Spring Boot 3.4
- Spring Security
- Spring Data JPA
- Spring Data Redis
- Flyway
- Spring Mail
- JWT

### Infrastructure

- PostgreSQL 16
- Redis 8
- Docker Compose

## Repository structure

```text
atlaswatch/
|-- moviehub-frontend/         # Next.js frontend
|-- src/main/java/             # Spring Boot application code
|-- src/test/java/             # backend unit/web/integration-style tests
|-- docs/                      # architecture, debugging, operations notes
|-- docker-compose.yml         # local full-stack orchestration
|-- Dockerfile                 # backend container build
|-- pom.xml                    # Maven backend config
`-- README.md
```

## Developer knowledge base

- [`CHANGELOG.md`](CHANGELOG.md) records changes, bug investigations, root causes, and verification.
- [`DECISIONS.md`](DECISIONS.md) explains important engineering choices and trade-offs.
- [`FLOW.md`](FLOW.md) traces the system's request, authentication, persistence, and startup flows.
- [`AGENTS.md`](AGENTS.md) requires future contributors and coding agents to keep those documents current.

## Environment configuration

The backend reads a root-level `.env` file. Copy `.env.example` to `.env` and fill in real values.

Key variables:

| Variable | Docker Compose | Local (`mvn spring-boot:run`) |
|---|---|---|
| `DB_NAME` | name of the Postgres database | same |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://db:5432/<db>` (set by compose) | `jdbc:postgresql://localhost:5432/<db>` |
| `SPRING_DATABASE_USERNAME` | Postgres user | same |
| `SPRING_DATABASE_PASSWORD` | Postgres password | same |
| `REDIS_HOST` | **not used** - compose hardcodes `redis` (service name) | `localhost` |
| `REDIS_PORT` | `6379` | `6379` |
| `PUBLIC_KEY`, `PRIVATE_KEY` | optional DER values for hosted environments | optional |
| `PUBLIC_KEY_PATH`, `PRIVATE_KEY_PATH` | Compose mounts root PEM files | defaults to root PEM files |
| `TMDB_API_TOKEN` | TMDB bearer token | same |
| `MAIL_HOST`, `EMAIL`, `EMAIL_PASSWORD` | SMTP credentials | same |
| `CLIENT_ID`, `CLIENT_SECRET` | Google OAuth2 credentials | same |
| `ATLASWATCH_ALLOWED_ORIGIN_PATTERNS` | frontend origin(s), comma-separated | local wildcard defaults |

See `.env.example` for generation instructions for `PUBLIC_KEY` / `PRIVATE_KEY`.

The frontend can optionally use `moviehub-frontend/.env.example` for local standalone runs:

```env
NEXT_PUBLIC_API_URL=http://localhost:8080
```

## Database migrations

AtlasWatch now uses Flyway for schema management. The baseline migration lives at:

- `src/main/resources/db/migration/V1__initial_schema.sql`

What this means in practice:

- schema changes are versioned in the repo instead of being created implicitly by Hibernate
- Hibernate now validates the schema instead of mutating it on startup
- PostgreSQL-specific features such as the full-text-search `search_vector` column and GIN index are created explicitly

Current backend settings:

- `spring.jpa.hibernate.ddl-auto=validate`
- `spring.flyway.baseline-on-migrate=true`

How to work with it:

- for a fresh local database, start the app and Flyway will apply `V1__initial_schema.sql`
- for an existing non-empty local database, Flyway will baseline it on first startup and then manage future migrations from there
- for future schema changes, add a new migration file like `V2__...sql` instead of relying on Hibernate schema updates

If you want the cleanest verification path, create a fresh Postgres database and start the backend once to confirm Flyway can build the schema from scratch.

## Running the app

### Option 1: Docker Compose

This is the easiest way to run the full MVP locally.

1. Copy `.env.example` to `.env` and fill in real values.
2. From the repository root, run:

```bash
docker compose up --build
```

Services:

- frontend: [http://localhost:3000](http://localhost:3000)
- backend: [http://localhost:8080](http://localhost:8080)
- postgres: `localhost:5432`
- redis: `localhost:6379`

Compose includes health checks for all four services and waits for PostgreSQL,
Redis, and the backend before starting dependent containers.

### Option 2: Run backend and frontend separately

#### Prerequisites

- Java 21
- Maven 3.9+ or the Maven wrapper
- Node.js 20+
- npm
- PostgreSQL
- Redis

#### Backend

From the repository root:

```bash
mvn spring-boot:run
```

The backend reads:

```properties
spring.config.import=optional:file:.env[.properties]
```

#### Frontend

```bash
cd moviehub-frontend
npm install
npm run dev
```

The frontend runs on [http://localhost:3000](http://localhost:3000).

## Verification flows

For the current MVP, the important end-to-end flows are:

- browse homepage and search without logging in
- register, receive verification code, and verify account
- log in with username or email
- request a password reset and set a new password
- add a movie to watchlist when authenticated
- submit a review when authenticated
- log out and confirm auth-only actions are blocked again

The frontend obtains a CSRF token before mutations and retries one failed API
request after rotating an expired access token through `/auth/refresh`.

## Testing

### Backend

Full backend suite:

```bash
mvn test
```

The suite covers controllers, service behavior, recommendation scoring and
evaluation, Redis serialization, TMDB resilience, validation, and CSRF
enforcement. One infrastructure-bound application-context smoke test is
explicitly skipped. The 800-session catalog evaluator is also opt-in; the
remaining tests must pass with zero failures.

The session-intent baseline uses the real local catalog without reading or
changing user, cache, or impression state:

```powershell
$env:ATLASWATCH_RUN_LIVE_EVALUATION = 'true'
mvn "-Dtest=RecommendationSessionBatchEvaluationTest" test
```

It requires the normal PostgreSQL and Redis environment variables. See
`docs/evaluation/session-intent-batch-evaluation.md` for the Docker-network
variant, metric definitions, and interpretation limits. The frozen report is
under `docs/evaluation/runs/session-intent-v1/`.

Package check:

```bash
mvn -q -DskipTests package
```

### Frontend

```bash
cd moviehub-frontend
npm ci
npm run lint
npm exec tsc --noEmit
npm run build
```

## API overview

### Auth

- `GET /auth/csrf`
- `POST /auth/register`
- `POST /auth/login`
- `POST /auth/verify`
- `POST /auth/resend`
- `POST /auth/password-reset/request`
- `POST /auth/password-reset/confirm`
- `POST /auth/logout`
- `POST /auth/refresh`

### Movies

- `GET /api/movies/trending`
- `GET /api/movies/search`
- `GET /api/movies/{tmdbId}`

### Reviews

- `GET /api/reviews/movie/{tmdbId}`
- `POST /api/reviews`
- `PUT /api/reviews/{reviewId}`
- `DELETE /api/reviews/{reviewId}`

### Recommendations

- `POST /api/recommendations` — personalised ranked list for an authenticated user
- `GET /api/recommendations/cold-start` — genre/mood-based list requiring no login or history
- `POST /api/recommendations/solo` — single-pick endpoint (deprecated, kept for compatibility)

### Watchlist

- `GET /api/watchlist`
- `POST /api/watchlist`
- `PUT /api/watchlist/{id}/status`
- `DELETE /api/watchlist/{id}`

### Operations

- `GET /api/health`

## Supporting docs

- [DTO boundary review](docs/architecture/dto-boundary-review.md)
- [Movie details debugging postmortem](docs/debugging/movie-details-debugging-postmortem.md)
- [Cache behavior](docs/operations/cache-behavior.md)
- [Deployment verification](docs/operations/deployment-verification.md)
- [Search performance notes](docs/performance/search-performance.md)

## MVP status

AtlasWatch is an application-ready local MVP: the browse-search-track-review-recommend loop is implemented, authentication mutations are CSRF protected, sessions support refresh-token rotation, and automated backend/frontend checks are available in CI. A public hosted deployment and production traffic measurements are intentionally not claimed in this repository yet.
