# Catalog stability audit v1

- Database: `atlaswatch_catalog_audit_phase12` (isolated clone; the test refuses any other database name)
- Controlled cycles: **1**
- Catalog before: 953 total / 784 recommendation-ready
- Catalog after: 964 total / 793 recommendation-ready
- Ready-pool delta: +9
- Entrants: 9
- Exits: 0
- Destructive required-field losses: 0
- Frozen dataset: `session-intent-catalog-v1`, 793 movies, fingerprint `d597118da692e5dc2dc777a942651e24a4ae1b7a9364da76ecc03a1e69116f98`

## Interpretation

`eligibility-diff.csv` lists every movie that entered or left the ready pool and the exact reason codes.
A rating that crosses 5.5 is classified separately from a required field becoming blank. The audit fails if
ingestion erases a previously populated required field, after writing all evidence to disk.

This is a networked forensic audit, not the normal CI evaluation. The frozen post-cycle JSON dataset is the
immutable input used by the hermetic Testcontainers evaluation path.
