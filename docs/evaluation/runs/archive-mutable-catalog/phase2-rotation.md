# Phase 2 — session-aware rotation

- Algorithm: `v15-session-aware-rotation`
- Catalog snapshot: `sha256:e85a5db52001e44ed29a89a608f49d669496c84a9a2c7a4ced4d5baaffffd5ce`
- Matrix per variant: 20 prompts × 4 personas × 10 rotations = 800 sessions
- Bootstrap: 2,000 paired prompt/persona cluster resamples

> Control disables session exclusion; challenger accumulates prior displayed TMDB IDs. Rule-derived mood coverage is circular and is not semantic relevance evidence.

| Metric | Control | Challenger | Paired delta | 95% paired CI |
|---|---:|---:|---:|---:|
| Full-shortlist rate | 0.1938 | 0.1938 | 0.0000 | [0.0000, 0.0000] |
| Mean slot fill | 0.3233 | 0.3233 | 0.0000 | [0.0000, 0.0000] |
| Rule-derived mood coverage | 0.5251 | 0.5251 | 0.0000 | [0.0000, 0.0000] |
| Primary-mood violation | 0.0719 | 0.0719 | 0.0000 | [0.0000, 0.0000] |
| Consecutive overlap | 0.1236 | 0.0893 | -0.0343 | [-0.0551, -0.0172] |
| Unique-result rate | 0.1751 | 0.2447 | 0.0696 | [0.0314, 0.1127] |
| Top-1 repeat rate | 0.6429 | 0.3857 | -0.1267 | [-0.1712, -0.0823] |

## Promotion result

Phase 2 passes only when fill and rule-derived mood coverage do not regress, while overlap and top-1 repetition fall and unique-result rate rises. These gates do not claim human relevance.
