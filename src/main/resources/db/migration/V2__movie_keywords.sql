CREATE SEQUENCE db_keyword_counter START WITH 1 INCREMENT BY 50;

CREATE TABLE keyword (
    id BIGINT NOT NULL DEFAULT nextval('db_keyword_counter'),
    name VARCHAR(255) NOT NULL,
    tmdb_id INTEGER NOT NULL,
    CONSTRAINT pk_keyword PRIMARY KEY (id),
    CONSTRAINT uk_keyword_tmdb_id UNIQUE (tmdb_id)
);

CREATE TABLE movie_keyword (
    movie_id BIGINT NOT NULL,
    keyword_id BIGINT NOT NULL,
    CONSTRAINT pk_movie_keyword PRIMARY KEY (movie_id, keyword_id),
    CONSTRAINT fk_movie_keyword_movie FOREIGN KEY (movie_id) REFERENCES movie (id),
    CONSTRAINT fk_movie_keyword_keyword FOREIGN KEY (keyword_id) REFERENCES keyword (id)
);

CREATE INDEX idx_keyword_name ON keyword (name);
CREATE INDEX idx_movie_keyword_movie ON movie_keyword (movie_id);
CREATE INDEX idx_movie_keyword_keyword ON movie_keyword (keyword_id);
