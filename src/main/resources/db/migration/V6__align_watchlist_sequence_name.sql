-- V1 declares the watchlist sequence as `db_watchList`. PostgreSQL folds
-- unquoted identifiers, so a database built by Flyway receives `db_watchlist`,
-- while Hibernate's physical naming strategy resolves the entity's
-- `db_watchList` to `db_watch_list`. The long-lived database was created by
-- ddl-auto and baselined into Flyway at V1, so V1 had never actually executed
-- anywhere and the mismatch stayed invisible until a schema was built from
-- scratch for hermetic evaluation.
--
-- Converge both shapes on `db_watch_list`. Every statement here is a no-op on
-- a database that was baselined from the Hibernate-generated schema.

CREATE SEQUENCE IF NOT EXISTS db_watch_list START WITH 1 INCREMENT BY 50;

DO $$
DECLARE
    highest_id BIGINT;
    current_value BIGINT;
BEGIN
    SELECT COALESCE(MAX(id), 0) INTO highest_id FROM watch_list;
    SELECT last_value INTO current_value FROM db_watch_list;

    -- Only advance when the sequence could still hand out an existing id.
    -- A baselined database is already ahead of its rows and must not move
    -- backwards or skip a block for no reason.
    IF highest_id > 0 AND current_value <= highest_id THEN
        PERFORM setval('db_watch_list', highest_id + 50, true);
    END IF;
END
$$;

-- Drop the divergent default and the folded sequence so a freshly migrated
-- database matches the baselined production shape exactly. Rows are always
-- inserted with an explicit generated id, so no writer depends on either.
ALTER TABLE watch_list ALTER COLUMN id DROP DEFAULT;
DROP SEQUENCE IF EXISTS db_watchlist;
