# Catalog semantic-metadata backfill v1

Captured: 2026-08-16

## Why this preceded the evaluation baseline

AtlasWatch's ambiguous moods use TMDB keywords and overview phrases as direct semantic evidence. A baseline collected while recommendation-ready rows had never completed a detail-metadata fetch would measure catalog incompleteness as though it were ranking quality.

The durable completion marker is `movie.semantic_metadata_synced_at`. It is written after a successful TMDB detail response even when TMDB returns zero keywords. Keyword presence alone is not a valid completion marker because a legitimate response can have an empty keyword list.

## Frozen Phase 0 snapshot

| Measure | Before | After |
|---|---:|---:|
| Total local movie rows | 934 | 934 |
| Recommendation-ready rows | 770 | 770 |
| Recommendation-ready rows with completed semantic sync | 742 | 770 |
| Recommendation-ready rows with at least one keyword | 742 | 742 |
| Backfill attempts | — | 28 |
| Successful detail refreshes | — | 28 |
| Failed detail refreshes | — | 0 |

After the run, 770/770 recommendation-ready rows had a completed semantic fetch. Keyword coverage remained 742/770 (96.36%): the remaining 28 are completed zero-keyword responses, not silently skipped rows. Across the full 934-row local table, 835 rows have a completed semantic sync and 807 have at least one keyword; non-ready rows were intentionally outside this baseline gate.

Normal scheduled ingestion continued afterward and, before the first Phase 1 snapshot, grew the catalog to 940 total rows and 775 recommendation-ready rows. All 775 were semantically complete and 747 had keywords. This is expected catalog growth rather than Phase 0 drift: normal detail ingestion uses the same completion contract.

That growth is also why a checksum of the live database was never a usable evaluation input. Evaluation is now pinned to the versioned `session-intent-catalog-v1` dataset — 793 recommendation-ready movies, content fingerprint `d597118da692e5dc2dc777a942651e24a4ae1b7a9364da76ecc03a1e69116f98`, exported after a later catalog-stability audit and seeded into a disposable database. See `DECISIONS.md` entry D-016.

## Execution behavior

- Flyway V5 adds the nullable completion timestamp and initializes it for rows that already have keyword relationships.
- The opt-in startup runner repeatedly selects the first incomplete recommendation-ready batch. Completed rows disappear from the next query, making the operation resumable after interruption.
- Each movie failure is isolated and recorded; one TMDB error does not discard the batch.
- The configured delay bounds request rate.
- Recommendation caches are invalidated once after a run that changes catalog metadata.
- Normal detail ingestion now writes the same completion marker, so new rows follow the same contract.

The runner is disabled by default. It is enabled only for a deliberate maintenance run with `ATLASWATCH_METADATA_BACKFILL_RUN_ON_STARTUP=true`; batch size, maximum attempts, and delay are controlled by the adjacent `ATLASWATCH_METADATA_BACKFILL_*` settings.

## Evidence boundary

This phase proves that every recommendation-ready movie received a successful detail response. It does not claim that TMDB's keyword vocabulary is complete or that the Java mood rules are semantically correct. Those are ranking and labeling questions handled by later evaluation phases.
