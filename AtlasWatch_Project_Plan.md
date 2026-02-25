# AtlasWatch — Project Architecture & 14-Day Plan

## What Is AtlasWatch?

A **personalized movie discovery and social recommendation platform** where users can:
- Discover movies via the TMDB API (real movie data)
- Get AI-powered recommendations based on their watch history and preferences
- Build watchlists, rate/review movies, and track what they've seen
- Follow other users and see what friends are watching
- Get real-time notifications when friends review movies

**Why this project will impress interviewers:**
It naturally requires every technology those job descriptions mention: REST APIs, relational + caching databases, async processing, external API integration, AI/ML integration, Docker, testing, and clean architecture. You're not bolting on technologies for the sake of it — each one solves a real problem.

---

## Your Existing Code — What Stays and What Changes

### What Stays (Your Auth System Is Good)
Your entire auth layer is solid and stays as-is:
- `User` entity with JPA + PostgreSQL
- `AuthService` with registration, login, email verification, resend
- `JwtService` with RSA asymmetric signing (this is above-average — most tutorials use HMAC)
- `RefreshTokenService` with token rotation
- `JwtAuthFilter` reading from HttpOnly cookies
- `SecurityConfiguration` with CORS, CSRF, stateless sessions
- `AuthController` with all endpoints

**Interview talking point**: "I chose asymmetric JWT signing (RS256) over symmetric (HS256) because in a microservices setup, only the auth service needs the private key to sign tokens, while any other service can verify tokens using just the public key. This follows the principle of least privilege."

### What Needs to Change
1. **Rename the project**: `ai-travel-recommendation` → `atlaswatch` (or whatever you choose)
2. **Rename the frontend folder**: `moviehub-frontend` → `atlaswatch-frontend`
3. **Fix the `register` endpoint**: It currently returns the entire `User` object including the hashed password. You need a response DTO that excludes sensitive fields.
4. **Add a global exception handler**: Right now you're throwing raw `RuntimeException`s. You need a `@ControllerAdvice` class.
5. **Add Docker for the Spring Boot app**: You only have PostgreSQL in docker-compose.

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     FRONTEND (Next.js)                       │
│  Login / Register / Verify / Homepage / Movies / Profile     │
│  Communicates via REST API (HttpOnly cookies for auth)       │
└────────────────────────┬────────────────────────────────────┘
                         │ HTTP (port 3000 → 8080)
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                SPRING BOOT BACKEND (port 8080)               │
│                                                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────┐  │
│  │   Auth   │  │  Movie   │  │  User    │  │  Social    │  │
│  │Controller│  │Controller│  │Controller│  │ Controller │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └─────┬──────┘  │
│       │              │              │               │        │
│  ┌────▼─────┐  ┌────▼─────┐  ┌────▼─────┐  ┌─────▼──────┐  │
│  │  Auth    │  │  Movie   │  │  User    │  │  Social    │  │
│  │ Service  │  │ Service  │  │ Service  │  │  Service   │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └─────┬──────┘  │
│       │              │              │               │        │
│  ┌────▼──────────────▼──────────────▼───────────────▼─────┐  │
│  │              REPOSITORY LAYER (Spring Data JPA)        │  │
│  └────────────────────────┬───────────────────────────────┘  │
│                           │                                  │
│  ┌────────────────┐  ┌────▼────────┐  ┌──────────────────┐  │
│  │   TMDB API     │  │ PostgreSQL  │  │   Redis Cache    │  │
│  │   (External)   │  │  (Primary)  │  │  (Caching layer) │  │
│  └────────────────┘  └─────────────┘  └──────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                    All in Docker Compose
```

**Why this architecture?**

This is a **layered monolith** — not microservices. Here's why that's the RIGHT choice for this project and something you can explain in an interview:

- **Microservices would be over-engineering** for a single-developer project with this scope. Microservices solve organizational problems (multiple teams deploying independently), not just technical ones.
- A well-structured monolith with clear package boundaries (`auth`, `movie`, `user`, `social`) can be *extracted* into microservices later. This shows you understand when NOT to use a pattern, which is more impressive than blindly applying it.
- You still demonstrate distributed systems thinking through: external API calls (TMDB), caching (Redis), async processing, and containerization (Docker).

**Interview talking point**: "I chose a modular monolith over microservices because for a single-developer project, the operational overhead of microservices (service discovery, inter-service communication, distributed transactions) outweighs the benefits. But I designed the package structure so each module (auth, movie, social) has its own controller, service, and repository layer — making it straightforward to extract into separate services if the team scales."

---

## Database Schema Design

```sql
-- Already exists (your User table)
Users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR UNIQUE NOT NULL,
    email VARCHAR UNIQUE NOT NULL,
    password VARCHAR NOT NULL,
    verification_codes VARCHAR,
    expiration_time_of_verification_codes TIMESTAMP,
    enable BOOLEAN,
    locked BOOLEAN,
    bio TEXT,
    avatar_url VARCHAR,
    created_at TIMESTAMP DEFAULT NOW()
)

-- Already exists
RefreshToken (
    id BIGSERIAL PRIMARY KEY,
    token VARCHAR UNIQUE NOT NULL,
    expiry_time TIMESTAMP,
    user_id BIGINT REFERENCES Users(id),
    revoked BOOLEAN
)

-- NEW: Movies cached from TMDB
Movie (
    id BIGSERIAL PRIMARY KEY,
    tmdb_id INTEGER UNIQUE NOT NULL,    -- TMDB's movie ID
    title VARCHAR NOT NULL,
    overview TEXT,
    poster_path VARCHAR,
    backdrop_path VARCHAR,
    release_date DATE,
    vote_average DOUBLE,
    genres VARCHAR,                       -- stored as comma-separated or JSON
    runtime INTEGER,
    cached_at TIMESTAMP DEFAULT NOW()    -- when we cached this from TMDB
)

-- NEW: User reviews and ratings
Review (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES Users(id),
    movie_id BIGINT REFERENCES Movie(id),
    rating INTEGER CHECK (rating >= 1 AND rating <= 10),
    review_text TEXT,
    contains_spoilers BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP,
    UNIQUE(user_id, movie_id)            -- one review per user per movie
)

-- NEW: User's personal watchlist
Watchlist (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES Users(id),
    movie_id BIGINT REFERENCES Movie(id),
    status VARCHAR DEFAULT 'PLAN_TO_WATCH',  -- PLAN_TO_WATCH, WATCHING, WATCHED
    added_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, movie_id)
)

-- NEW: Social follows
Follow (
    id BIGSERIAL PRIMARY KEY,
    follower_id BIGINT REFERENCES Users(id),
    following_id BIGINT REFERENCES Users(id),
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(follower_id, following_id)
)

-- NEW: Activity feed
Activity (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES Users(id),
    activity_type VARCHAR NOT NULL,      -- REVIEW, WATCHLIST_ADD, FOLLOW
    target_id BIGINT,                    -- movie_id or user_id depending on type
    metadata JSONB,                      -- flexible extra data
    created_at TIMESTAMP DEFAULT NOW()
)
```

**Why this schema design matters (interview-ready):**

1. **The Movie table caches TMDB data** rather than calling the API every time. This is a real-world pattern: you don't want your app to break if TMDB's API goes down, and you don't want to hit their rate limit. The `cached_at` column lets you implement cache invalidation ("refresh data if older than 24 hours").

2. **The UNIQUE constraint on Review(user_id, movie_id)** enforces at the database level that a user can only review a movie once. You could enforce this in Java code, but database constraints are your last line of defense — if a bug in your code tries to insert a duplicate, the DB will reject it. This is the principle of **defense in depth**.

3. **The Activity table uses JSONB for metadata** instead of having separate columns for every possible activity detail. This is a practical trade-off: JSONB gives flexibility (a review activity has different data than a follow activity), but you lose strong typing and it's harder to query efficiently. For an activity feed where you're mostly reading chronologically, this trade-off makes sense.

4. **Follow uses a self-referential relationship** (both columns reference Users). The UNIQUE constraint prevents duplicate follows.

---

## Package Structure

```
src/main/java/com/atlasmind/atlaswatch/
├── config/                          # EXISTING (keep)
│   ├── ApplicationConfig.java
│   ├── JwtAuthFilter.java
│   └── SecurityConfiguration.java
├── controller/                      # Thin layer — just handles HTTP
│   ├── AuthController.java          # EXISTING
│   ├── MovieController.java         # NEW
│   ├── ReviewController.java        # NEW
│   ├── WatchlistController.java     # NEW
│   ├── UserController.java          # EXISTING (expand)
│   └── SocialController.java        # NEW
├── dto/                             # Data Transfer Objects (what the API sends/receives)
│   ├── request/                     # NEW: what the client sends
│   │   ├── RegisterUserDto.java
│   │   ├── LoginUserDto.java
│   │   ├── CreateReviewDto.java
│   │   └── ...
│   └── response/                    # NEW: what the API returns
│       ├── UserResponseDto.java     # User WITHOUT password/verification fields
│       ├── MovieResponseDto.java
│       ├── ReviewResponseDto.java
│       └── ...
├── exception/                       # NEW: centralized error handling
│   ├── GlobalExceptionHandler.java  # @ControllerAdvice
│   ├── ResourceNotFoundException.java
│   ├── DuplicateResourceException.java
│   └── ...
├── model/                           # JPA Entities
│   ├── User.java                    # EXISTING
│   ├── RefreshToken.java            # EXISTING
│   ├── Movie.java                   # NEW
│   ├── Review.java                  # NEW
│   ├── Watchlist.java               # NEW
│   ├── Follow.java                  # NEW
│   └── Activity.java                # NEW
├── repository/                      # Spring Data JPA interfaces
│   ├── UserRepository.java          # EXISTING
│   ├── RefreshTokenRepository.java  # EXISTING
│   ├── MovieRepository.java         # NEW
│   ├── ReviewRepository.java        # NEW
│   ├── WatchlistRepository.java     # NEW
│   ├── FollowRepository.java        # NEW
│   └── ActivityRepository.java      # NEW
├── service/                         # Business logic lives here
│   ├── AuthService.java             # EXISTING
│   ├── JwtService.java              # EXISTING
│   ├── EmailService.java            # EXISTING
│   ├── RefreshTokenService.java     # EXISTING
│   ├── UserService.java             # EXISTING (expand)
│   ├── MovieService.java            # NEW
│   ├── TmdbApiService.java          # NEW: calls external TMDB API
│   ├── ReviewService.java           # NEW
│   ├── WatchlistService.java        # NEW
│   ├── SocialService.java           # NEW
│   └── ActivityService.java         # NEW
└── util/                            # Helpers
    └── PasswordValidator.java       # EXISTING
```

**Why separate request/response DTOs?**

Right now, your `/register` endpoint returns the entire `User` entity, including the hashed password. That's a security issue. By having separate DTOs:
- **Request DTOs** define what the client sends (only the fields you need)
- **Response DTOs** define what you send back (excluding sensitive data)
- **Entities** are your internal database representation

This is called the **DTO pattern**, and it's standard in enterprise Java. In an interview: "I never expose JPA entities directly in my API responses because it couples my database schema to my API contract. If I add a column to my database, I don't want that to accidentally appear in my API. DTOs give me control over my API surface."

---

# AtlasWatch — Final GitHub Issue List

> **Agreed plan:** Build the core product (search + details + reviews/watchlist), stand out via 4 depth pillars (DB performance, caching, reliability, testing/CI), use Flyway (minimal), no scope creep.

---

## Phase 1: Foundation ✅ COMPLETE

### Issue #1: Global Exception Handler ✅ DONE
**What:** `@ControllerAdvice` class that catches exceptions and returns consistent JSON error responses.  
**Why:** Raw `RuntimeException` gives clients 500 + stack trace. Structured errors like `{ "status": 404, "message": "User not found", "timestamp": "..." }` are what production APIs return.  
**Touches:** `GlobalExceptionHandler.java`, `ErrorResponse` DTO, custom exception classes (`ResourceNotFoundException`, `DuplicateResourceException`, `VerificationException`, `WeakPasswordException`).  
**Status:** Complete and tested via Postman.

---

### Issue #2: Response DTOs for Auth Endpoints ✅ DONE
**What:** Stop returning raw `User` entity from `/register`. Create `UserResponseDto` that excludes password, verification codes, etc.  
**Why:** Security — never send hashed passwords over the wire. DTOs decouple your database schema from your API contract.  
**Touches:** `UserResponseDto.java` with `fromUser()` factory method, cleaned up `AuthController` (no try-catch blocks, controllers only handle happy path).  
**Status:** Complete and tested.

---

### Issue #3: Docker Setup ✅ DONE
**What:** `docker-compose.yml` with PostgreSQL 16 + Redis 8 (alpine), persistent volumes, shared Docker network. Dockerfile for Spring Boot app exists (not used in dev).  
**Why:** Every job description mentions Docker. `docker compose up` starts your infrastructure.  
**Touches:** `docker-compose.yml`, `Dockerfile`.  
**Status:** Both containers running and tested.

---

### Issue #4: Redis Configuration ✅ DONE
**What:** Add `spring-boot-starter-data-redis` dependency, configure connection, create `RedisConfig.java` with JSON serialization and 30-min default TTL.  
**Why:** Redis will back caching (Issue #9) and rate limiting (Issue #11). JSON serialization makes cache entries human-readable and debuggable.  
**Touches:** `pom.xml`, `application.properties`, `RedisConfig.java` with `@EnableCaching`.  
**Status:** Connected and verified.

---

## Phase 2: Core Movie Features (Build the product)

### Issue #5: Database Schema + Flyway Migrations
**What:** Create versioned database schema using Flyway. Two migration files:
- `V1__create_core_tables.sql` — movies, genres, movie_genres, reviews, watchlist, follow, activity tables
- `V2__add_indexes.sql` — unique constraints, foreign keys, GIN index for FTS (added in Issue #10)

**Why:** Flyway gives versioned, repeatable migrations that prevent schema drift across environments. `ddl-auto=update` is risky in production — it can't handle destructive changes (dropping columns, renaming tables). Flyway is production-aligned.

**Schema highlights:**
- `movie` table caches TMDB data locally (durable cache — survives TMDB outages)
- `genre` + `movie_genre` join table — normalized many-to-many (use explicit join entity, NOT JPA `@ManyToMany`)
- `review` has `UNIQUE(user_id, movie_id)` — defense in depth, prevents duplicates even under race conditions
- `follow` has `CHECK(follower_id != following_id)` — prevents self-follows at DB level
- `activity` uses JSONB metadata — flexible schema for different activity types
- All FKs use `ON DELETE CASCADE` — no orphaned data
- Scope note: V1 migration includes only tables needed for Phase 2-3: movie, genre, movie_genre, review, watchlist. The follow and activity tables are deferred to a V3__add_social_tables.sql migration if/when Phase 4 is reached. Don't create entities for tables you haven't migrated yet — schema and code should evolve together, tied to features.

**Touches:** `src/main/resources/db/migration/V1__create_core_tables.sql`, `V2__add_indexes.sql`, JPA entities (`Movie.java`, `Genre.java`, `MovieGenre.java`, `Review.java`, `Watchlist.java`, `Follow.java`, `Activity.java`), repositories.

**Definition of Done:**
- [ ] Flyway runs on app startup with no errors
- [ ] All tables created with correct constraints
- [ ] JPA entities match schema and compile cleanly
- [ ] `ddl-auto` set to `validate` (Flyway owns the schema now)

---

### Issue #6: TMDB API Integration Service
**What:** Create `TmdbApiService` that calls TMDB's REST API to search movies, get movie details, and get trending movies.

**Why:** Shows you can integrate external APIs, handle failures gracefully, and parse JSON responses. This is the data source for your entire product.

**Implementation details:**
- Use Spring's `RestClient` (modern, synchronous, fluent API — NOT `RestTemplate` which is in maintenance mode, NOT `WebClient` which is for reactive apps)
- Bearer token auth via `application.properties` (externalized, never hardcoded)
- Separate TMDB DTOs (`TmdbMovieDto`, `TmdbSearchResponse`, `TmdbMovieDetailDto`) — Anti-Corruption Layer pattern
- **Must-have:** Configurable timeouts (connect + read) on the RestClient
- Log errors, don't silently swallow failures

**Touches:** `TmdbApiService.java`, `TmdbConfig.java` (RestClient bean), TMDB DTOs in `dto/tmdb/`, `application.properties`.

**Definition of Done:**
- [ ] Can search movies by title and get results
- [ ] Can get details for a specific movie by TMDB ID
- [ ] Can get trending movies
- [ ] Timeouts configured (e.g., 5s connect, 10s read)
- [ ] Errors logged with context (query, TMDB ID, error message)

---

### Issue #7: Movie Ingestion + Local Persistence (DB-first Cache)
**What:** When fetching a movie from TMDB, save it to your local `movie` table. Before calling TMDB, check the DB first.

**Why:** DB-first caching reduces TMDB calls, improves latency, and keeps the app working when TMDB is degraded. Your reviews and watchlists need foreign keys to local movie records.

**Fetch flow (memorize this — it's your reliability story):**
```
1. Movie in DB AND fetchedAt < 24h → return from DB (cache hit)
2. Not in DB OR stale (>24h)       → call TMDB → upsert into DB → return
3. TMDB call fails                  → return stale DB data + flag stale=true
```

**Touches:** `Movie.java` entity, `Genre.java` entity, `MovieGenre.java` join entity, `MovieRepository.java`, `MovieService.java` with upsert logic and staleness check.

**Key field:** `cached_at` (or `fetched_at`) timestamp on Movie entity — this is how you determine freshness.

**Definition of Done:**
- [ ] First TMDB fetch saves movie to local DB
- [ ] Second request for same movie returns from DB (no TMDB call)
- [ ] Stale movies (>24h) trigger a TMDB refresh
- [ ] If TMDB is down, stale data is returned (not an error)
- [ ] Genres correctly mapped via join table

---

### Issue #8: Movie Search & Discovery Endpoints (MVP)
**What:** Core REST endpoints for movie discovery:
```
GET  /api/movies/search?query=inception&page=1   → Search movies
GET  /api/movies/trending                        → Trending movies
GET  /api/movies/{tmdbId}                        → Single movie details
GET  /api/movies/genres                          → Available genres
```

**Why:** These are your core product endpoints. Recruiters will run these first. Clean REST design with proper status codes, pagination, and DTO boundaries.

**Touches:** `MovieController.java`, `MovieResponseDto.java`, service methods, pagination support.

**Definition of Done:**
- [ ] All 4 endpoints working and returning correct JSON
- [ ] Search is paginated
- [ ] Response DTOs (not raw entities) returned to client
- [ ] Public endpoints (no auth required for browsing)
- [ ] Tested via Postman

---

### Issue #9: Redis Caching for Movie Endpoints
**What:** Cache trending movies and movie details in Redis with TTL.

**Why:** Trending movies don't change every second. Caching avoids repeated DB/TMDB calls for hot data. Redis is a shared cache — in a multi-instance deployment, all instances hit the same cache (unlike in-memory caches like Caffeine).

**What to cache (keep it scoped):**
- `/movies/trending` → Redis TTL: 10 minutes
- `/movies/{tmdbId}` → Redis TTL: 1 hour

**Implementation:** Spring Cache abstraction (`@Cacheable`, `@CacheEvict`) with Redis as backing store (already configured in Issue #4).

**Interview talking point:** *"I use two caching layers: PostgreSQL as a durable cache (survives Redis restarts, backs foreign keys), and Redis as a speed cache for hot read paths. DB-first caching gives resilience; Redis gives low-latency reads."*

**Touches:** `MovieService.java` (add `@Cacheable` annotations), cache key design, per-cache TTL config.

**Definition of Done:**
- [ ] First call to `/trending` hits DB/TMDB, subsequent calls hit Redis
- [ ] Redis CLI `MONITOR` shows GET/SET commands
- [ ] Cache entries expire after configured TTL
- [ ] Cache entries are readable JSON (not binary blobs)

---

### Issue #10: Postgres Full-Text Search + EXPLAIN ANALYZE ⭐ DEPTH PILLAR
**What:** Upgrade `/search` from basic `ILIKE` to real PostgreSQL full-text search with ranking, and document the performance improvement.

**Why:** This is your **"database systems / query optimization" signal.** Most new grads never touch query planning. Having measurable before/after performance data is the kind of thing that makes interviewers pay attention.

**Implementation:**
1. Start with `ILIKE '%query%'` (baseline — works but no index, full table scan)
2. Add a `tsvector` generated column on `movie` for `title + overview`
3. Add a GIN index on the `tsvector` column
4. Use `ts_rank()` for result ordering
5. Write `docs/perf/search.md` with:
    - Before: `EXPLAIN ANALYZE` of ILIKE query (show Seq Scan)
    - After: `EXPLAIN ANALYZE` of FTS query (show Index Scan via GIN)
    - Latency comparison

**Touches:** Flyway migration `V2__add_indexes.sql` (add FTS column + GIN index), repository query, response DTO.

**Definition of Done:**
- [ ] FTS query returns ranked results
- [ ] GIN index is used (confirmed via EXPLAIN)
- [ ] `docs/perf/search.md` exists with before/after analysis
- [ ] Measurable latency improvement documented

---

### Issue #11: Rate Limiter (Redis-backed Token Bucket) ⭐ DEPTH PILLAR
**What:** Implement a Redis-backed rate limiter for endpoints that call TMDB.

**Why:** If 100 users hit your search endpoint at once, TMDB bans your IP. Rate limiting protects external dependencies and keeps your service stable under spikes.

**Implementation:**
- Token bucket algorithm per IP (anonymous) + per user (authenticated)
- Redis atomic operations (Lua script recommended for atomicity)
- Return HTTP 429 Too Many Requests + `Retry-After` header when limit exceeded
- Filter/interceptor that runs before controller logic

**Touches:** Rate limit filter/interceptor, Redis Lua script, config (e.g., 30 requests/minute for search).

**Definition of Done:**
- [ ] Exceeding limit returns 429 + `Retry-After` header
- [ ] Rate limit is per-IP for anonymous, per-user for authenticated
- [ ] Redis stores token counts (verify via Redis CLI)
- [ ] TMDB endpoints are protected

---

## Phase 3: User Interaction Features

### Issue #12: Review System (CRUD)
**What:** Full CRUD for movie reviews:
```
POST   /api/reviews                      → Create a review
GET    /api/reviews/movie/{movieId}      → All reviews for a movie
GET    /api/reviews/user/{userId}        → All reviews by a user
PUT    /api/reviews/{id}                 → Update your review
DELETE /api/reviews/{id}                 → Delete your review
```

**Why:** Core user interaction feature. Demonstrates authorization checks (users can only edit/delete their OWN reviews), write correctness, and constraint enforcement.

**Includes:** Rating (1-10), review text, spoiler flag, timestamps.  
**Authorization:** Verify `review.userId == currentUser.id` before edit/delete.  
**Constraint:** `UNIQUE(user_id, movie_id)` enforced at DB level — one review per user per movie.

**Touches:** `Review.java`, `ReviewRepository.java`, `ReviewService.java`, `ReviewController.java`, `CreateReviewDto.java`, `ReviewResponseDto.java`.

**Definition of Done:**
- [ ] All 5 endpoints working
- [ ] Cannot create duplicate reviews (409 Conflict)
- [ ] Cannot edit/delete another user's review (403 Forbidden)
- [ ] Rating validated (1-10 range, enforced in Java + DB CHECK constraint)

---

### Issue #13: Watchlist Management
**What:** Users can track movies they want to watch:
```
POST   /api/watchlist                    → Add movie to watchlist
GET    /api/watchlist                    → Get current user's watchlist
PUT    /api/watchlist/{id}/status        → Update status (PLAN_TO_WATCH → WATCHING → WATCHED)
DELETE /api/watchlist/{id}               → Remove from watchlist
```

**Why:** Core product stickiness feature. Demonstrates state machine modeling and idempotent writes.

**Status values:** `PLAN_TO_WATCH`, `WATCHING`, `WATCHED` — stored as VARCHAR, validated in Java via an enum.  
**Constraint:** `UNIQUE(user_id, movie_id)` — can't add same movie twice.

**Touches:** `Watchlist.java`, `WatchlistRepository.java`, `WatchlistService.java`, `WatchlistController.java`.

**Definition of Done:**
- [ ] All 4 endpoints working
- [ ] Cannot add duplicate movies (409 Conflict)
- [ ] Status transitions work correctly
- [ ] Only authenticated user can access their own watchlist

---

### Issue #14: User Profile Endpoints
**What:** View and update user profiles with aggregated stats:
```
GET  /api/users/{id}        → View a user's profile
PUT  /api/users/me           → Update your own profile (bio, avatar_url)
GET  /api/users/{id}/stats   → User stats (# reviews, # watched, avg rating)
```

**Why:** Aggregate queries + performance considerations. The `/stats` endpoint involves `COUNT()` and `AVG()` across reviews and watchlist — good for demonstrating efficient queries.

**Touches:** `UserController.java`, `UserService.java`, aggregate queries in repositories, `UserProfileDto.java`, `UserStatsDto.java`.

**Definition of Done:**
- [ ] Profile view returns user info (without sensitive fields)
- [ ] Can update own profile only
- [ ] Stats endpoint returns correct counts and averages
- [ ] Aggregate queries are efficient (single query, not N+1)

---

### Issue #15: Keyset/Cursor Pagination
**What:** Replace OFFSET pagination with cursor-based pagination for review lists and any list endpoints.

**Why:** OFFSET pagination scans and discards rows — `OFFSET 10000` reads 10,000 rows just to skip them. Cursor pagination (`WHERE id > :lastSeenId ORDER BY id LIMIT 20`) is O(1) regardless of how deep you paginate. This is what production systems use at scale.

**Touches:** Repository queries, API response includes `nextCursor`, response wrapper DTO.

**Definition of Done:**
- [ ] Review list and watchlist use cursor pagination
- [ ] Response includes `nextCursor` field
- [ ] Can explain OFFSET vs cursor trade-offs in interview

---

## Phase 4: Social Features (if time allows)

### Issue #16: Follow System
**What:**
```
POST   /api/social/follow/{userId}      → Follow a user
DELETE /api/social/unfollow/{userId}     → Unfollow
GET    /api/social/{userId}/followers    → List followers
GET    /api/social/{userId}/following    → List who they follow
```

**Touches:** `Follow.java`, `SocialController.java`, `SocialService.java`.  
**Constraint:** `UNIQUE(follower_id, following_id)` + `CHECK(follower_id != following_id)`.

**Definition of Done:**
- [ ] All 4 endpoints working
- [ ] Cannot follow yourself (400 Bad Request)
- [ ] Cannot follow same person twice (409 Conflict)

---

### Issue #17: Activity Feed (Pull-based)
**What:**
```
GET  /api/feed    → Personalized feed (activity from people you follow)
```

**Implementation:** Pull-based — generated at read time by querying activities from followed users. Paginated. Avoid N+1 via fetch joins or DTO projections.

**Interview point:** *"I implemented a pull-based feed where the feed is generated at read time. A push-based model (fan-out on write) would be faster at read time but adds write amplification. For this scale, pull is the right trade-off."*

**Touches:** Feed query, DTO projections, cursor pagination.

**Definition of Done:**
- [ ] Feed returns activities from followed users, newest first
- [ ] Paginated with cursor
- [ ] No N+1 queries (verified via Hibernate SQL logging)

---

### Issue #18: Recommendation Engine v1 (Rule-based)
**What:**
```
GET  /api/recommendations    → Personalized movie recommendations
```

**Implementation:** Rule-based baseline (NO LLM needed):
- Trending movies in genres the user has rated highly
- "Because you watched X" — genre matching from user's review history
- Fallback: trending movies if user has no history (cold start)

**Why:** Simple but demonstrates recommendation thinking without LLM complexity.

**Touches:** `RecommendationService.java`, caching, genre-based filtering.

**Definition of Done:**
- [ ] Returns personalized recommendations based on user's history
- [ ] Falls back to trending for new users (cold start handling)
- [ ] Can explain ranking rationale

---

## Phase 5: Reliability, Testing & CI/CD ⭐ DEPTH PILLARS

### Issue #19: Resilience4j (Circuit Breaker + Retry + Timeouts) ⭐ DEPTH PILLAR
**What:** Wrap TMDB API calls with Resilience4j patterns: Circuit Breaker + Retry + TimeLimiter.

**Why:** Real distributed systems reliability. If TMDB is down, the circuit breaker opens after N failures and the fallback serves stale data from Postgres. Prevents cascading failures.

**Fallback chain:**
```
TMDB API call
  → Retry (max 2 attempts, exponential backoff)
  → Circuit Breaker (opens after 5 failures in 60s)
  → TimeLimiter (5 second timeout)
  → Fallback: return stale data from Postgres
```

**Touches:** Resilience4j dependency, config, wrappers around `TmdbApiService` calls in `MovieService`.

**Definition of Done:**
- [ ] TMDB failures trigger retries (visible in logs)
- [ ] Circuit breaker opens after threshold (returns fallback)
- [ ] Fallback returns stale DB data (not an error)
- [ ] Can demonstrate by stopping TMDB calls and showing graceful degradation

---

### Issue #20: Unit Tests for Services
**What:** JUnit 5 + Mockito tests for core services:
- `AuthService` — duplicate user handling, invalid verification, refresh rotation
- `MovieService` — DB cache hit/miss, stale refresh, TMDB error handling
- `ReviewService` — authorization checks, duplicate review prevention

**Target:** 15-25 meaningful tests.

**Touches:** Test classes in `src/test/java/`.

**Definition of Done:**
- [ ] All tests pass
- [ ] Tests cover happy path AND edge cases
- [ ] Mocks isolate service from dependencies
- [ ] `mvn test` runs clean

---

### Issue #21: Integration Tests with Testcontainers ⭐ DEPTH PILLAR
**What:** Use Testcontainers to run integration tests against real PostgreSQL and Redis containers.

**Why:** Mocks miss real-world bugs (SQL dialect issues, serialization problems, cache TTL behavior). Testcontainers spins up real containers for tests and tears them down after. Most new grads never do this — it's a strong differentiator.

**Minimum test flows:**
- Auth: register → verify → login → refresh rotation
- Movies: ingest movie from TMDB → search → verify cache hit
- Reviews: create review → fetch → verify stats update

**Touches:** Testcontainers dependency, `@SpringBootTest` test classes.

**Definition of Done:**
- [ ] Tests run against real Postgres + Redis containers
- [ ] At least 3 end-to-end flows tested
- [ ] All pass in CI (GitHub Actions)

---

### Issue #22: GitHub Actions CI Pipeline
**What:** `.github/workflows/ci.yml` that runs on push/PR to main:
- Build with Maven
- Run all tests (including Testcontainers)
- (Optional) Build Docker image

**Why:** Green CI badge on your repo = instant credibility. Every job description mentions CI/CD.

**Touches:** `.github/workflows/ci.yml`.

**Definition of Done:**
- [ ] Pipeline runs on every push to main
- [ ] All tests pass in CI
- [ ] Green badge displayed in README

---

### Issue #23: API Documentation (SpringDoc OpenAPI)
**What:** Add SpringDoc OpenAPI dependency to auto-generate Swagger UI at `/swagger-ui.html`.

**Why:** Shows you think about developer experience. Easy for recruiters to explore your API.

**Touches:** `pom.xml` dependency + minimal annotations.

**Definition of Done:**
- [ ] Swagger UI accessible at `/swagger-ui.html`
- [ ] All endpoints documented with descriptions

---

### Issue #24: README.md Overhaul
**What:** Write a professional README that sells your project:
- Project description + screenshots
- Tech stack with **tradeoff justifications** (why Redis, why DB-first caching, why pull-based feed)
- How to run (`docker compose up`)
- API docs link (Swagger)
- Architecture diagram
- Link to `docs/perf/search.md` (FTS performance writeup)
- What you learned

**Why:** This is literally the first thing a recruiter sees. A great README with clear reasoning is often more impressive than the code itself.

**Definition of Done:**
- [ ] README exists with all sections above
- [ ] Architecture diagram included
- [ ] "How to run" actually works (tested from scratch)

---

## Phase 6: Extra Depth (ONLY if ahead of schedule)

### Issue #25: Observability (Light Version)
**What:** Add Spring Boot Actuator + Micrometer metrics. Log structured JSON. Track p95 latency, error rate, cache hit ratio.  
**Scope:** Metrics endpoint only — full Grafana dashboards are out of scope.

### Issue #26: Cloud Deployment
**What:** Deploy to a cloud provider. Minimum viable: single instance with Docker Compose on a VPS (Railway, Render, or EC2).  
**Scope:** NOT full ECS/RDS/ElastiCache unless you already know AWS.

---

## Build Order (Execution Sequence)

| Step | Issue(s) | Pillar |
|------|----------|--------|
| 1 | #5 — Flyway migrations (schema + entities) | DB |
| 2 | #6 — TMDB API client + timeouts | Core |
| 3 | #7 — Movie persistence (DB-first cache) | Caching |
| 4 | #8 — Search + trending + detail endpoints | Core |
| 5 | #10 — FTS + GIN index + EXPLAIN doc | ⭐ DB Performance |
| 6 | #9 — Redis caching for trending + details | ⭐ Caching |
| 7 | #11 — Rate limiter | ⭐ Reliability |
| 8 | #19 — Resilience4j on TMDB calls | ⭐ Reliability |
| 9 | #12 — Reviews CRUD | Core |
| 10 | #13 — Watchlist | Core |
| 11 | #14 — User profiles + stats | Core |
| 12 | #15 — Cursor pagination | Core |
| 13 | #20 — Unit tests | ⭐ Testing |
| 14 | #21 — Testcontainers integration tests | ⭐ Testing |
| 15 | #22 — GitHub Actions CI | ⭐ CI/CD |
| 16 | #23 — Swagger docs | Polish |
| 17 | #24 — README + perf writeup | Polish |
| 18 | #16-18 — Social + Feed + Recs (if time) | Bonus |

---

## The 4 Depth Pillars (Your Interview Differentiators)

1. **DB & Performance:** Postgres FTS + GIN index + EXPLAIN ANALYZE documentation
2. **Caching:** Redis (speed cache) + Postgres (durable cache) — two-tier strategy
3. **Reliability:** Resilience4j (circuit breaker + retry) + rate limiting + stale data fallback
4. **Testing/CI:** Testcontainers (real DB/Redis in tests) + GitHub Actions pipeline

---

## 14-Day Timeline

| Day | Focus | Deliverable |
|-----|-------|-------------|
| 1 | Rename project, fix response DTOs, global exception handler | Issues #1, #2 |
| 2 | Dockerize app + add Redis | Issues #3, #4 |
| 3 | TMDB API service + Movie entity | Issues #5, #6 |
| 4 | Movie search/discovery endpoints | Issue #7 |
| 5 | Redis caching for movies | Issue #8 |
| 6 | Review system CRUD | Issue #9 |
| 7 | Watchlist management | Issue #10 |
| 8 | User profile endpoints | Issue #11 |
| 9 | Follow system | Issue #12 |
| 10 | Activity feed with pagination | Issue #13 |
| 11 | AI recommendation endpoint | Issue #14 |
| 12 | Frontend: connect new endpoints, build movie browse/search page | Frontend work |
| 13 | Unit + integration tests | Issues #15, #16 |
| 14 | CI pipeline, Swagger docs, README | Issues #17, #18, #19 |

---

## Technologies You'll Learn & Why Each One Matters

| Technology | What Job Descriptions Ask For | What You'll Use It For |
|-----------|-------------------------------|----------------------|
| Spring Boot 3 | Every single one | Backend framework |
| Spring Data JPA | Visa, Revolut | Database access layer |
| Spring Security + JWT | All of them | Auth system (already done!) |
| PostgreSQL | Revolut, Visa, others | Primary database |
| Redis | Revolut, Booking | Caching TMDB responses |
| Docker + Docker Compose | Every single one | Containerize entire stack |
| REST API design | Every single one | All endpoints |
| JUnit 5 + Mockito | Revolut (TDD), Netflix | Testing |
| GitHub Actions | CI/CD requirements | Automated testing |
| External API integration | Real-world skill | TMDB API calls |
| Next.js + TypeScript | Several jobs | Frontend (already started!) |

---

## Key Interview Talking Points This Project Gives You

1. **Security**: "I implemented JWT with RSA asymmetric signing stored in HttpOnly cookies, with refresh token rotation to prevent token reuse attacks."

2. **Caching Strategy**: "I used a two-tier caching approach: Redis for frequently accessed data like trending movies (with TTL), and a local PostgreSQL cache for TMDB data to reduce external API dependency."

3. **API Design**: "I followed RESTful conventions — resources as nouns, HTTP methods for verbs, proper status codes (201 for creation, 404 for not found, 409 for conflicts), and pagination for list endpoints."

4. **Error Handling**: "I used a global exception handler with @ControllerAdvice to return consistent error responses, rather than letting Spring's default error handling leak implementation details."

5. **Testing**: "I wrote unit tests with Mockito to isolate business logic from dependencies, and integration tests with MockMvc to verify the full request-response cycle including security filters."

6. **Docker**: "The entire application runs with a single `docker compose up` — the backend, PostgreSQL, and Redis are all containerized with proper networking and volume persistence."

7. **Architecture Decisions**: "I chose a modular monolith over microservices because [see above]. I chose Redis over Caffeine because [see above]. I chose pull-based feeds over push-based because [see above]."

---

## Immediate Next Steps (What to Do Right Now)

1. **Decide on the project name** and rename everything
2. **Create GitHub issues** from the list above (number them, add labels like `backend`, `infra`, `testing`)
3. **Start with Issue #1 and #2** — these are quick wins that improve code quality immediately
4. **Get TMDB API key** from https://www.themoviedb.org/settings/api (free, takes 2 minutes)
5. **Start coding Day 1 work**

The goal is: by the end of 14 days, someone can clone your repo, run `docker compose up`, and have a fully working movie platform with search, reviews, recommendations, social features, and a CI pipeline — all documented and tested.
