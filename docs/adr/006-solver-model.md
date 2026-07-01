# ADR-006: Three planning entities, HardMediumSoftLongScore, integer-only math

**Status:** Accepted (2026-07-01). Full design: `../design/04-solver.md` + amendments in `05-solver-verification.md`.

## Decision
- Entities: `PlayerAssignment` (var `group`, `allowsUnassigned=true`), `GroupSchedule` (var `trainingBlock`), `CoachSlot` (var `coach`, `allowsUnassigned=true`); `Group` is a problem fact. Since `@PlanningPin` is entity-level in 1.x, this yields independent locking of player/tid+bana/tränare (spec §15.2–15.3) with no custom move code.
- `HardMediumSoftLongScore`: hard = feasibility only; **medium reserved for the unassignedPlayer (waitlist) penalty** scaled by Prioritet — guardrails: not disableable, level locked, weight floor ≥ 1; MVP UI reclassification limited to HARD/SOFT.
- All constraint math in fixed-point ×100 integers; LevelBalance = sum of absolute deviations from floorDiv-mean; group ordering compared by cross-multiplication; ArchUnit bans float/double/BigDecimal in solver packages.
- Dynamic weights via `ConstraintWeightOverrides` (zero weight = constraint removed; level reclassification = `ofHard(w)`/`ofSoft(w)`).
