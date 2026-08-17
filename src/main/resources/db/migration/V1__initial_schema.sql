CREATE SEQUENCE users_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE refresh_token_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE db_genre_counter START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE db_movie_counter START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE db_review START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE db_watchList START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE db_recommendation_impression START WITH 1 INCREMENT BY 50;

CREATE TABLE users (
    id BIGINT NOT NULL DEFAULT nextval('users_seq'),
    username VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    verification_codes VARCHAR(255),
    expiration_time_of_verification_codes TIMESTAMP(6),
    password_reset_code VARCHAR(255),
    expiration_time_of_password_reset_code TIMESTAMP(6),
    enable BOOLEAN,
    locked BOOLEAN,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE genre (
    id BIGINT NOT NULL DEFAULT nextval('db_genre_counter'),
    name VARCHAR(255) NOT NULL,
    tmdb_id INTEGER NOT NULL,
    CONSTRAINT pk_genre PRIMARY KEY (id),
    CONSTRAINT uk_genre_tmdb_id UNIQUE (tmdb_id)
);

CREATE TABLE movie (
    id BIGINT NOT NULL DEFAULT nextval('db_movie_counter'),
    movie_title VARCHAR(255) NOT NULL,
    tmdb_id INTEGER NOT NULL,
    overview TEXT,
    poster_path VARCHAR(500),
    backdrop_path VARCHAR(500),
    cached_at TIMESTAMP(6) NOT NULL,
    release_date DATE,
    runtime INTEGER,
    movie_rating DOUBLE PRECISION,
    popularity DOUBLE PRECISION,
    search_vector tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('english', COALESCE(movie_title, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(overview, '')), 'B')
    ) STORED,
    CONSTRAINT pk_movie PRIMARY KEY (id),
    CONSTRAINT uk_movie_tmdb_id UNIQUE (tmdb_id)
);

CREATE TABLE refresh_token (
    id BIGINT NOT NULL DEFAULT nextval('refresh_token_seq'),
    token VARCHAR(255) NOT NULL,
    expiry_time TIMESTAMP(6),
    user_id BIGINT NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_refresh_token PRIMARY KEY (id),
    CONSTRAINT uk_refresh_token_token UNIQUE (token),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE review (
    id BIGINT NOT NULL DEFAULT nextval('db_review'),
    user_id BIGINT NOT NULL,
    movie_id BIGINT NOT NULL,
    rating INTEGER NOT NULL,
    review_text TEXT,
    contains_spoilers BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6),
    CONSTRAINT pk_review PRIMARY KEY (id),
    CONSTRAINT review_user_movie UNIQUE (user_id, movie_id),
    CONSTRAINT fk_review_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_review_movie FOREIGN KEY (movie_id) REFERENCES movie (id)
);

CREATE TABLE watch_list (
    id BIGINT NOT NULL DEFAULT nextval('db_watchList'),
    user_id BIGINT NOT NULL,
    movie_id BIGINT NOT NULL,
    status VARCHAR(255) NOT NULL,
    added_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_watch_list PRIMARY KEY (id),
    CONSTRAINT uk_watchlist_user_movie UNIQUE (user_id, movie_id),
    CONSTRAINT fk_watch_list_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_watch_list_movie FOREIGN KEY (movie_id) REFERENCES movie (id)
);

CREATE TABLE movie_genre (
    movie_id BIGINT NOT NULL,
    genre_id BIGINT NOT NULL,
    CONSTRAINT pk_movie_genre PRIMARY KEY (movie_id, genre_id),
    CONSTRAINT fk_movie_genre_movie FOREIGN KEY (movie_id) REFERENCES movie (id),
    CONSTRAINT fk_movie_genre_genre FOREIGN KEY (genre_id) REFERENCES genre (id)
);

CREATE TABLE recommendation_impression (
    id BIGINT NOT NULL DEFAULT nextval('db_recommendation_impression'),
    user_id BIGINT NOT NULL,
    movie_id BIGINT NOT NULL,
    recommended_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_recommendation_impression PRIMARY KEY (id),
    CONSTRAINT fk_recommendation_impression_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_recommendation_impression_movie FOREIGN KEY (movie_id) REFERENCES movie (id)
);

CREATE INDEX idx_genre_name ON genre (name);
CREATE INDEX idx_movie_popularity_rating ON movie (popularity, movie_rating);
CREATE INDEX idx_movie_rating_popularity ON movie (movie_rating, popularity);
CREATE INDEX idx_movie_cached_at ON movie (cached_at);
CREATE INDEX idx_movie_search_vector ON movie USING GIN (search_vector);
CREATE INDEX idx_refresh_token_user_id ON refresh_token (user_id);
CREATE INDEX idx_review_user_id ON review (user_id);
CREATE INDEX idx_review_movie_id ON review (movie_id);
CREATE INDEX idx_watchlist_user_status_movie ON watch_list (user_id, status, movie_id);
CREATE INDEX idx_watchlist_user_movie_lookup ON watch_list (user_id, movie_id);
CREATE INDEX idx_movie_genre_movie ON movie_genre (movie_id);
CREATE INDEX idx_movie_genre_genre ON movie_genre (genre_id);
CREATE INDEX idx_recommendation_impression_user_time ON recommendation_impression (user_id, recommended_at);
CREATE INDEX idx_recommendation_impression_user_movie_time ON recommendation_impression (user_id, movie_id, recommended_at);
