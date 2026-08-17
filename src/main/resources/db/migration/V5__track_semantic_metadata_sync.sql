ALTER TABLE movie
    ADD COLUMN IF NOT EXISTS semantic_metadata_synced_at TIMESTAMP;

-- Existing keyword mappings prove that a detail response was fetched. Movies
-- with a successful zero-keyword response remain unknown and are fetched once
-- by the resumable backfill.
UPDATE movie m
SET semantic_metadata_synced_at = m.cached_at
WHERE m.semantic_metadata_synced_at IS NULL
  AND EXISTS (
      SELECT 1
      FROM movie_keyword mk
      WHERE mk.movie_id = m.id
  );

CREATE INDEX IF NOT EXISTS idx_movie_semantic_metadata_synced_at
    ON movie (semantic_metadata_synced_at);
