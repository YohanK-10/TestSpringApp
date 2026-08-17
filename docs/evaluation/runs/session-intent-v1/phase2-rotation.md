# Phase 2 — session-aware rotation

- Algorithm: `v15-session-aware-rotation`
- Evaluation catalog: `session-intent-catalog-v1` (793 recommendation-ready movies)
- Content fingerprint: `d597118da692e5dc2dc777a942651e24a4ae1b7a9364da76ecc03a1e69116f98`
- Matrix per variant: 20 prompts × 4 personas × 10 rotations = 800 sessions
- Bootstrap: 2,000 paired prompt/persona cluster resamples

> Control disables session exclusion; challenger accumulates prior displayed TMDB IDs. Rule-derived mood coverage is circular and is not semantic relevance evidence.

| Metric | Control | Challenger | Paired delta | 95% paired CI |
|---|---:|---:|---:|---:|
| Full-shortlist rate | 0.3775 | 0.3775 | 0.0000 | [0.0000, 0.0000] |
| Mean slot fill | 0.5078 | 0.5078 | 0.0000 | [0.0000, 0.0000] |
| Rule-derived mood coverage | 0.5830 | 0.5830 | 0.0000 | [0.0000, 0.0000] |
| Primary-mood violation | 0.2171 | 0.2147 | -0.0013 | [-0.0052, 0.0021] |
| Consecutive overlap | 0.1218 | 0.0945 | -0.0273 | [-0.0402, -0.0158] |
| Unique-result rate | 0.3589 | 0.4205 | 0.0617 | [0.0336, 0.0946] |
| Top-1 repeat rate | 0.5362 | 0.2360 | -0.2097 | [-0.2634, -0.1579] |
| Runtime violation rate | 0.3146 | 0.3127 | -0.0010 | [-0.0023, 0.0000] |
| Era violation rate | 0.0000 | 0.0000 | 0.0000 | [0.0000, 0.0000] |

## Promotion result

Phase 2 passes only when fill and rule-derived mood coverage do not regress, runtime and era violations do not rise with a paired CI excluding zero, and overlap and top-1 repetition fall while unique-result rate rises. These gates do not claim human relevance.
