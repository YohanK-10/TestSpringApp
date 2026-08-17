ALTER TABLE movie
    ADD COLUMN IF NOT EXISTS vote_count INTEGER;

ALTER TABLE movie
    DROP CONSTRAINT IF EXISTS ck_movie_vote_count_non_negative;

ALTER TABLE movie
    ADD CONSTRAINT ck_movie_vote_count_non_negative
        CHECK (vote_count IS NULL OR vote_count >= 0);

CREATE INDEX IF NOT EXISTS idx_movie_vote_count_rating
    ON movie (vote_count DESC, movie_rating DESC);
