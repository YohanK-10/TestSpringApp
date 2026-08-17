# AtlasWatch contributor instructions

These instructions apply to the entire repository.

Before changing code, read the relevant sections of:

- `CHANGELOG.md` for recent behavior changes and fixes
- `DECISIONS.md` for architectural choices and their trade-offs
- `FLOW.md` for request, data, authentication, and deployment flows

For every bug fix or developer-facing feature:

1. Diagnose before editing. Record the symptom, evidence, root cause, and verification in `CHANGELOG.md`.
2. Update `DECISIONS.md` when the change introduces or revises a meaningful design choice. Include alternatives and trade-offs.
3. Update `FLOW.md` whenever control flow, data flow, authentication, persistence, infrastructure, or an important user journey changes.
4. Add or update automated tests when practical. Always run verification proportional to the risk and record the commands/results in `CHANGELOG.md`.
5. Do not document guesses as facts. Clearly label unresolved hypotheses and replace them with evidence once confirmed.
6. Never include passwords, API keys, JWTs, reset codes, verification codes, or other secrets in these documents.

Small cosmetic or comment-only edits need only a concise changelog entry when they materially affect users or developers.

