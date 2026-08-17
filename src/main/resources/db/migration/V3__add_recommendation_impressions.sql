CREATE SEQUENCE IF NOT EXISTS db_recommendation_impression START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS recommendation_impression (
    id BIGINT NOT NULL DEFAULT nextval('db_recommendation_impression'),
    user_id BIGINT NOT NULL,
    movie_id BIGINT NOT NULL,
    recommended_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_recommendation_impression PRIMARY KEY (id),
    CONSTRAINT fk_recommendation_impression_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_recommendation_impression_movie FOREIGN KEY (movie_id) REFERENCES movie (id)
);

CREATE INDEX IF NOT EXISTS idx_recommendation_impression_user_time
    ON recommendation_impression (user_id, recommended_at);

CREATE INDEX IF NOT EXISTS idx_recommendation_impression_user_movie_time
    ON recommendation_impression (user_id, movie_id, recommended_at);
