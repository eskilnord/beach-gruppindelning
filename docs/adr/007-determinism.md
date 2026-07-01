# ADR-007: Determinism strategy

**Status:** Accepted (2026-07-01)

## Decision
- User solves: `environmentMode=NO_ASSERT` (the fast reproducible mode; `REPRODUCIBLE` is deprecated since 1.20), `randomSeed=0`, deterministically sorted input, wall-clock presets (10/60/120 s) via per-job `SolverConfigOverride`.
- Tests/CI: `PHASE_ASSERT`/`STEP_ASSERT` with **step-count termination** (`stepCountLimit`/`unimprovedStepCountLimit`) — never wall-clock, which cannot reproduce across differing hardware. Golden scores for committed anonymized datasets asserted **exactly equal** on macOS + Windows runners; build-breaking from milestone M6a. Greedy baseline included in the golden matrix.
- Local search: Late Acceptance (1.33 default; no time-gradient dependence). Same Temurin patch on all platforms; UTC timezone; UTF-8 everywhere.
