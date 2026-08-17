# Superseded: pre-freeze evaluation artifacts

Archived: 2026-08-16

These are the original Phase 1 baseline and Phase 2 rotation artifacts. They were produced against the live `Travel` database while the Docker backend continued scheduled ingestion into it, so **the catalog moved between and during the runs**.

Concretely:

- The two reports record different catalog checksums (`sha256:e3227a3a…` and `sha256:e85a5db5…`).
- Five prompts that returned results in the baseline returned none in the Phase 2 control (`p001`, `p002`, `p003`, `p004`, `p017`); nine prompts returned nothing at all.
- Total returned items fell from 2,055 to 1,293 between the runs.
- The recorded checksum hashed `cached_at`, so it changed on any ingestion tick regardless of whether ranking-relevant content moved.

The Phase 2 comparison inside `phase2-rotation.json` remains internally valid, because its control and challenger were interleaved in one process against one state. Nothing here should be compared with the frozen artifacts in `../session-intent-v1/`, and no number in this directory should be quoted as a current result.

The replacement runs against the versioned `session-intent-catalog-v1` dataset in a disposable Testcontainers database, with the content fingerprint asserted before and after the request loop. See `DECISIONS.md` entry D-016.
