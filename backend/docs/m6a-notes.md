# M6a implementation notes (Timefold solver core)

Design decisions and deviations made while implementing M6a (solver planning model, hard
constraints, waitlist, solve lifecycle). Primary contract: `docs/design/04-solver.md` ("THE
implementation contract" per the milestone brief) as amended by
`docs/design/05-solver-verification.md`'s APPROVE-WITH-FIXES findings; `../../utkast-kravspec.txt`
§10 (24 standard constraints), §15 (locks), §16 (solve lifecycle). Frontend (Optimeringsvy) is a
separate, later task.

## Scope actually implemented

- `common.time.TimeKey` + exhaustive overlap tests (§6.1).
- `solver.domain`: `GroupPlanSolution`, `PlayerAssignment`, `GroupSchedule`, `CoachSlot` (planning
  entities) + `Group`, `TrainingBlock`, `CoachFact`, `PersonPairWish`, `CoachWish`,
  `SavedPlanResourceUsage`, `LateTimePolicy` (facts) + `WishType`/`CoachWishType`/`UsageType` enums,
  exactly per design §1.2's class shapes.
- `solver.constraints.GroupPlanConstraintProvider`: all 16 HARD constraints implementable as
  Constraint Streams code + `unassignedPlayer` (MEDIUM) = 17 constraints, each with `justifyWith`;
  `LevelMath` (pure-integer SAD/floorDiv, ready for M6b's `levelBalance`); `ConstraintKeys` registry.
  Two HARD rows (`groupRequiresTrainingBlock`, `lockedAssignmentHard`) are satisfied by construction
  per design §1.4/§5 and covered by model-level tests instead of `ConstraintVerifier`.
- `solver.assemble`: `SolverInputAssembler` (DB → `GroupPlanSolution`, deterministic sort +
  `SolverIdIndex` id mapping + `ConstraintWeightOverrides` construction), `GroupGenerator` (§7),
  `AssembledProblem` (solution + reverse id maps for post-solve writeback), `SolverIdIndex`.
- `solver.run`: `SolveCoordinator` (start/status/cancel via `SolverManager`), `ProgressRegistry`,
  `OptimizationRunService`, `SolveProfile` (FAST/NORMAL/THOROUGH), `SolveStatus`.
- `api.SolveController` (`POST .../solve`, `GET .../solve/status`, `POST .../solve/cancel`) and
  `api.GroupController` (`POST .../groups/generate`, `GET .../groups` — see deviation below).
- `solverConfig.xml` (`NO_ASSERT`, `randomSeed 0`, entity/solution wiring, no phase override).
- `V5__solver_runs.sql`: `optimization_run` table + 7 new `constraint_definition` rows +
  `mustNotPlayWith` standard field (see "Schema reconciliation" below).
- Domain/repo additions: `TrainingGroup`/`CoachAssignment`/`OptimizationRun` records + repositories;
  `PlayerAssignmentRepository`/`CoachAssignmentRepository` writeback methods.
- Not implemented (out of M6a scope per the brief): `LevelService` (already done in M4),
  `GreedyBaselineService` (M6b), all SOFT constraints (M6b), explainability/what-if (M7),
  `saved_plan`/`ConflictService` (M8) — `SavedPlanResourceUsage` is a placeholder type only.

## Deviations from `docs/design/04-solver.md` (each with reasoning, per the milestone brief's
## "strong reasons" requirement)

### 1. Solver-domain ids are derived `long`s, not the DB's own ids
Design §1.2 says "All IDs are `long` (stable DB ids)". The actual schema (ADR-004, CLAUDE.md "SQLite
rules") uses **TEXT UUIDv7** primary keys everywhere, not numeric ids — a real mismatch the design
doc didn't anticipate (it was written assuming a hypothetical numeric-id schema). `SolverIdIndex`
(`solver.assemble`) resolves this: every entity/fact list is sorted by its own stable UUID string
(lexicographic, which for UUIDv7 also happens to be creation-time order) and assigned sequential
`long`s 1..N. This is a pure function of the DB's own stable content — never of retrieval/iteration
order — so it satisfies the design's own determinism invariant ("stable IDs come from the DB, never
from row order or iteration order", §9.3) even though the literal mechanism differs.
**Group is the one exception**: `Group.id` = `groupOrder` directly (already a stable, plan-unique,
deterministic integer from `GroupGenerator`), avoiding a redundant indirection and doubling as a
human-readable id in logs/justifications.
`GroupPlanSolution.activityPlanId` is `String` (not `long`) for the same underlying reason — it is
purely informational (never a `@PlanningId`/value-range member), so forcing it through the same
mapping buys nothing.
`AssembledProblem` (new type, not in the design doc) bundles the `GroupPlanSolution` with the reverse
(`long solver id -> DB TEXT id`) maps `SolveCoordinator` needs to write results back after an async
solve completes on a different thread than the one that assembled the problem.

### 2. `@PlanningId` fields are boxed `Long` (verifier's own correction)
Applied as specified in `05-solver-verification.md`'s minor finding — done, not a further deviation.

### 3. `CoachSlot` synthetic id = `groupId * 100 + slotIndex` (verifier's own correction)
Applied as specified — done, not a further deviation. `SolverInputAssembler` validates
`requiredCoachCount <= 99` and throws `BadRequestException` otherwise.

### 4. Constraint keys reuse the already-seeded V1/V2 strings, not the design table's shorthand
The design's §4 table uses short names (`groupMaxSize`, `groupMinSize`, `lockedAssignment`,
`savedPlanResourceBlock` as ONE key). V2 (seeded in M1, before the solver design was finalized) used
`groupMaxSizeHard`, `groupMinSizeSoft`, `lockedAssignmentHard` — and `ConstraintWeightService`'s
`NEVER_DISABLEABLE_KEYS` set (M4) and `FlywayMigrationTest` already reference those exact strings.
`GroupPlanConstraintProvider`/`ConstraintKeys` reuse the seeded names verbatim rather than renaming —
renaming would churn already-tested M1/M4 code for a cosmetic gain. `savedPlanResourceBlock` (V2, one
key for all of §10.24) is a special case: the finalized design splits person/coach/court blocking
into three independently-weighted constraints (§10.24a/b/c). Rather than retrofit the M1 seed,
V5 adds `savedPlanPersonBlocked`/`savedPlanCoachBlocked`/`savedPlanCourtBlocked` as three NEW rows and
extends `NEVER_DISABLEABLE_KEYS` to cover them; the original `savedPlanResourceBlock` row is left in
place, unused — a harmless orphan (still referenced by the pre-existing `FlywayMigrationTest`
assertion) until a future migration retires it.

### 5. Seven new `constraint_definition` rows + `ConstraintWeightService` guardrail extension
`coachMaxGroups` (spec §13.1, never had a seeded row), `coachWishRequired`/`coachWishForbidden`
(§10.21b/c — the seeded `coachPreferenceSoft` only covers the SOFT/WANT case), `savedPlanPersonBlocked`/
`CoachBlocked`/`CourtBlocked` (§10.24a/b/c, see above), `unassignedPlayer` (MEDIUM, §2.1, ADR-006's
reserved waitlist penalty). `ConstraintWeightService.NEVER_DISABLEABLE_KEYS` gains `coachMaxGroups`
(a resource-physics cap, same rationale class as the existing `groupMaxSizeHard`/`coachNoOverlap`
entries) and the three `savedPlan*` keys; `coachWishRequired`/`coachWishForbidden` are deliberately
**not** added — like `sameGroupHard`/`differentGroupHard`, they are data-driven personal wishes, not
physics, matching M4's own established distinction. `unassignedPlayer`'s MEDIUM guardrail needed
**zero** code changes — M4's generic `hard_or_soft == 'MEDIUM'` branch in
`ConstraintWeightService.validateReclassification` already covers it, confirming the design's own
claim.

### 6. One new standard field, `mustNotPlayWith` (personRelation, DIFFERENT_GROUP, HARD)
Spec §9.2's 19-field list has no HARD "vill inte spela med" field (only the SOFT `avoidPlayWith` ->
`differentGroupSoft`) — yet §10.13 `differentGroupHard` was already seeded as a HARD constraint in
V2 with no data source, and the design's `WishType` enum (§1.2) defines `MUST_DIFFERENT` as a
first-class wish type, and the committed anonymized datasets' `not_with_ids` columns are documented
("hard, symmetric") assuming this exists. V5 adds `mustNotPlayWith`, mirroring `mustPlayWith`'s shape
exactly. `FlywayMigrationTest`'s 19-field assertions are updated to 20.

### 7. `timeRelation` custom fields (`cannotTimes`/`preferTimes`) now store real `TimeSlot` ids, not
### free text
M4 (built before M5's structured `TimeSlot` CRUD existed) validated `timeRelation` values with
`SwedishTimeParser`'s free-text grammar — m4-notes.md's own words: "a follow-up could... migrate this
field type to reference real TimeSlot ids... under discussion with the product owner" once M5 landed.
The design's own §1.2 comment ("`unavailableTimeSlotIds`; from 'Kan inte tider'") confirms this is
exactly what the finalized design expects: `cannotTimes`'s Swedish label IS "Kan inte tider". M6a
finishes that migration: `FieldValueService`'s `TIME_RELATION` case now validates against real
`time_slot` ids scoped to the plan (mirroring the existing `personRelation`/`coachRelation`
existence-check pattern) instead of `SwedishTimeParser`. The raw imported "Tid" reference text (spec
§2.2's "never auto-interpreted" free text) is untouched by this change — it was never stored in these
three standard fields to begin with; only their *validation grammar* changed.
`canTimes` (the whitelist sibling) is **not** consumed by the solver at all — the design's own
`PlayerAssignment.canAttend` sketch is blacklist-only; there is no whitelist concept in the finalized
model. Any custom field a council creates with `constraintType = TIME_AVAILABILITY` other than the
seeded `canTimes` is treated as a blacklist source by the assembler (a conservative default — nothing
in the M4 field builder distinguishes whitelist vs blacklist intent by `constraintType` alone; a
genuine gap inherited from M4, not introduced here).

### 8. `SolverRegressionTest`'s `stepCountLimit` is 20 000, not the literal 2000 in the design/brief
Measured empirically (three independent runs, fully deterministic — see "Determinism bug found and
fixed" below): with every HARD constraint already independently verified correct via 44
`ConstraintVerifier` tests, `large-120` (130 participants, 12 groups, 8 coaches, all HARD constraints
active) does **not** reach `hardScore == 0` within 2000 steps. The score deficit shrinks
monotonically and deterministically as steps increase — `-7hard@500`, `-5hard@1000`, `-5hard@2000`,
`-1hard@4000`, `-1hard@8000`, `0hard@16000` — the textbook signature of a step-budget/search-
throughput limit, not a stuck local optimum or a correctness bug. Root cause: with only 12
`GroupSchedule` and ~12 `CoachSlot` entities against 127 `PlayerAssignment` entities, Timefold's
default (unconfigured) local search samples moves roughly in proportion to entity population, so the
comparatively rare block-reassignment/coach-reassignment moves needed to resolve the last few
`trainingBlockCapacity`/`timeAvailabilityHard` matches take disproportionately more steps to be
sampled than for `small-10`/`coach-overlap-20` (which already converge well inside 2000 steps).
`stepCountLimit = 20 000` (~25% headroom over the observed 16 000-step convergence point) is used
uniformly for all three datasets — same `PHASE_ASSERT` + `randomSeed 0` + step-count-only
termination, so every determinism property this gate exists to enforce is unchanged; measured cost
is ~4 s wall-clock for `large-120` alone, well inside the CI job's 20-minute budget (confirmed: the
whole `SolverRegressionTest` class runs in ~3.6 s). Hand-tuning `solverConfig.xml`'s move selectors
to fix the underlying throughput imbalance was deliberately **not** done — that is real solver
tuning, out of this "faithful implementation, not re-design" milestone's scope, and a legitimate
M6b/M7 follow-up if dataset sizes grow further.

### 9. `groupSizeTargetEmpty`/`groupMinSizeEmpty` are name-reserved as documented constants, not DB rows
The verifier's minor finding wants these empty-group complement keys "explicit, pre-agreed" so a
future weight-override fan-out lands on the right pair. Since the underlying SOFT constraints
(`groupSizeTarget`/`groupMinSizeSoft`) aren't implemented until M6b, adding `constraint_definition`
rows for these two now — with no implementing Constraint Streams code — was judged too risky:
`SolverInputAssembler` builds `ConstraintWeightOverrides` from `constraint_definition`, and Timefold
validates override keys against the provider's actual constraint set at solve time; an unimplemented
key in that map could throw "unknown constraint" and break every solve. Reservation is instead a pair
of documented `public static final String` constants in `ConstraintKeys`
(`GROUP_SIZE_TARGET_EMPTY_RESERVED`/`GROUP_MIN_SIZE_EMPTY_RESERVED`), with `ConstraintKeys.IMPLEMENTED`
explicitly excluding them — `SolverInputAssembler` filters every weight map to `IMPLEMENTED` keys
defensively, so no reserved/unimplemented/future key can ever reach `ConstraintWeightOverrides`
regardless of what a future `constraint_definition` migration adds.

### 10. `coachMaxGroups` uses `maxGroupsPerWeek`, falling back to `maxGroupsPerDay`, not a day/week
### split
`CoachFact.maxGroups` is a single scalar, matching the design's own `coachMaxGroups` sketch
(`groupBy(CoachSlot::getCoach, count()).filter((c, n) -> n > c.maxGroups())` — one cap, not two).
Every committed fixture (and the real club's actual schedule) puts all blocks on a single day, so
`maxGroupsPerWeek` (used preferentially, falling back to `maxGroupsPerDay`, then unlimited) is the
correct effective cap for this MVP's single-evening-per-plan reality; a true day/week-split pair of
constraints is a straightforward, localized M6b addition if a plan ever spans multiple days.

### 11. `GroupController` (`POST/GET .../groups`) is a new endpoint, not in design §14.2's list
§14.2 only lists solve/explain/what-if endpoints. Without a way to create `training_group` rows,
`SolverInputAssembler` has nothing to assign players into and the whole solve loop is untestable
end-to-end via the API. Necessary, minimal addition (generate + list), mirroring the existing
`PUT .../time-slots/{slotId}/courts` generation-endpoint shape. No group-editing (rename/resize/
lock/`requiredCoachCount`) endpoint exists yet — out of scope; the E2E transcript below manually
zeroes `required_coach_count` via direct SQL for the coach-scarce scenario as a result (see the E2E
section).

## Determinism bug found and fixed during this milestone (not a deviation — a correctness fix)

`SolverInputAssembler` built `personPairWishes`/`coachWishes` by iterating
`CustomFieldValueRepository.findByEntity(...)`, which had **no `ORDER BY`** — SQL gives no row-order
guarantee without one. This was invisible on `small-10`/`coach-overlap-20` (few/no wish rows) but
caused genuine **run-to-run nondeterminism** on `large-120` (15+ wish rows): two otherwise-identical
`stepCountLimit`-terminated solves of the same data occasionally produced different scores, discovered
while first generating the golden file (one run passed at `stepCountLimit=2000`, the next three failed
identically at `-5hard` — the "pass" was luck, not correctness, since without a fix the list order
depended on unspecified SQLite row-return order). Fixed two ways, per the design's own §9.3 rule
("wish facts sorted by (fieldDefinitionId, aId, bId)"), which the original implementation had
missed: (1) added `ORDER BY id` to `CustomFieldValueRepository.findByEntity`; (2) `personPairWishes`/
`coachWishes` are explicitly sorted by `(fieldDefinitionId, aId/participantId, bId/coachPersonId)`
before being placed on `GroupPlanSolution`, making the ordering invariant self-evident regardless of
any future change upstream. Verified deterministic across 3+ independent JVM runs after the fix.

## M4 carryover fix (task item 8): `constraintType` lock on the reserved MEDIUM `priority` field

`FieldDefinitionValidator.validateReservedMediumPatch` (M4) locked `hardOrSoft` to `MEDIUM` and
`affectsOptimization` to `true`, but only checked the requested `constraintType` for
fieldType-*compatibility* — not that it equals the field's *existing* `constraintType`. Since
`NUMBER` fields are compatible with both `PRIORITY` and `LEVEL_BALANCE_INPUT`
(`ConstraintTypes.COMPATIBLE_BY_FIELD_TYPE`), a PATCH like `{"constraintType":"LEVEL_BALANCE_INPUT"}`
would have passed validation and silently retargeted the reserved field, WITHOUT ever touching
`hardOrSoft`. This mattered concretely because `SolverInputAssembler` routes wish/priority facts by
`constraintType` (not by field `key`) — a successful retarget would have made every participant
default to priority 3 (the field simply stops being read as `PRIORITY`), silently breaking §2's
"shed lowest-priority-first" waitlist guarantee with no error ever surfacing. Fixed:
`validateReservedMediumPatch` gained an `existingConstraintType` parameter and now rejects any
mismatch; `FieldDefinitionController.update` passes `existing.constraintType()`. Test:
`FieldDefinitionCrudControllerTest.reservedMediumPriorityFieldCannotHaveItsConstraintTypeChanged`.

## Locking (design §5) — "minimal in M6a" per the brief

`player_assignment.locked` -> `PlayerAssignment.pinned`, `training_group.locked` ->
`GroupSchedule.pinned`, `coach_assignment.locked` -> the corresponding `CoachSlot.pinned` (assigned
to the lowest-index slot with an existing assignment row, deterministically, per design §5's own
"pins the lowest-index free slot" rule). `SolverInputAssembler` validates every pin resolves to a
value actually present in the current value range (deleted group/block/coach) and throws
`BadRequestException` (400, not 422 — see below) otherwise. No §15.5 "optimera endast X" class-level
pinning mode — explicitly out of scope per the brief.

**400 vs 422**: the design doc's illustrative REST shape says "422 on invalid pins/weights". This
codebase has **no 422 usage anywhere** (M4's own precedent: `ConstraintWeightService`'s guardrail
returns 400 "matching every other validation failure in this codebase"). `SolverInputAssembler`
throwing `BadRequestException` maps to 400 through the existing `ApiExceptionHandler` with zero
special-casing — consistent, not a new pattern.

## Solve lifecycle

`SolveCoordinator` uses the plan's own `activity_plan.id` (TEXT UUIDv7) directly as Timefold's
`ProblemId_` type parameter (`SolverManager<GroupPlanSolution, String>`) — simpler than forcing it
through the `long` id mapping every solver-domain fact uses, since `ProblemId_` is purely a
`SolverManager` lookup key, never a value the constraint model reasons about. Verified this autowires
correctly through the Spring Boot starter's dynamic bean registration (`SolveControllerIntegrationTest`
is the proof).

`withBestSolutionEventConsumer`/`withFinalBestSolutionEventConsumer` + `new SolverConfigOverride<>()`
instance-method form used throughout, per the verifier's corrections — confirmed via `javap` against
the actual 1.33.0 jars before writing any code (not just trusted from the design doc).

Post-solve writeback (`SolveCoordinator.persistResult`, `@Transactional`): `player_assignment`
UPDATE scoped to `locked = 0` (locked rows untouched by construction); `training_group.
assigned_training_block_id` always rewritten (idempotent even for pinned groups, since the solver
never changes a pinned value anyway); `coach_assignment` — unlocked rows for a group are deleted
(`deleteUnlockedByGroupId`, new repository method) then every unlocked, coach-filled `CoachSlot`
gets a fresh `source=solver` row; a pre-existing locked row is never touched (never deleted, never
re-inserted, matching its `CoachSlot.pinned=true` provenance).

## Tests

`./mvnw clean verify`: **324 tests, 0 failures, 0 errors**. (The initial M6a report said 319 —
a stale surefire report from a renamed test inflated the aggregate; the real pre-review count was
318: 66 new versus the M5 baseline of 252, incl. +1 from the M4 carryover fix. The review round
below then added 6 more: concurrent-race, startup-recovery, 2x weight-ceiling, 2x assembler pin
validation.) New files:
- `common.time.TimeKeyTest` (10) — exhaustive overlap boundary cases.
- `solver.arch.NoFloatingPointArchTest` (3) — ArchUnit field/method-signature scan +
  `java.math.BigDecimal` dependency ban over `solver.domain`/`solver.constraints`.
- `solver.ScoreHeadroomTest` (2) — analytic worst-case (design §3.4 numbers verbatim, full envelope
  incl. unimplemented SOFT constraints so it stays meaningful for M6b).
- `solver.constraints.LevelMathTest` (6), `.CapacityConstraintsTest` (5), `.CoachConstraintsTest`
  (10), `.OverlapConstraintsTest` (6), `.PersonWishConstraintsTest` (5),
  `.TimeAvailabilityConstraintTest` (3), `.SavedPlanConstraintsTest` (5),
  `.UnassignedPlayerConstraintTest` (4, incl. one `justifiesWith` assertion) — 44 `ConstraintVerifier`
  tests total, one file per constraint-family, covering all 17 code constraints with at least one
  penalize + one no-impact case each.
- `solver.assemble.GroupGeneratorTest` (6) — count formula, naming, level bands, regeneration id
  preservation, both lock-refusal branches (group-level and player-assignment-level).
- `solver.WaitlistContractTest` (3), `.PinnedOverflowTest` (2, verifier-rewritten contract),
  `.LockRespectTest` (3, all three pin kinds, each built so an unpinned solve would prefer a
  different value) — full solves via `TestSolverFactory` (`PHASE_ASSERT` + step-count termination).
- `solver.regression.SolverRegressionTest` (2) — the golden gate (see above) + same-JVM double-solve
  equality from independent assemblies.
- `api.SolveControllerIntegrationTest` (4) — full lifecycle incl. 409/400/cancel, against the real
  `small-10` fixture loaded through `TestDatasetLoader` (promoted to `public`, reused from the
  regression package).
- `solver.run.OptimizationRunSnapshotLeakTest` (1) — extends the comment-leak protection surface
  (no single pre-existing "the leak test" file to literally extend; this is the M6a-specific new
  attack surface, `optimization_run.*_json`, following `CommentPrivacyControllerTest`'s
  raw-DB-column-assertion pattern).
- Extended `FieldDefinitionCrudControllerTest` (+1): the M4 carryover `constraintType` lock test.
- Extended `FlywayMigrationTest`, `ConstraintDefinitionControllerTest`, `ConstraintWeightControllerTest`,
  `FieldDefinitionControllerTest`: updated hardcoded 24/19 counts to 31/20 (pre-existing M1/M4 tests
  that legitimately needed updating for the V5 schema additions — not new bugs, expected fallout).
- Extended `ParticipantFieldValueControllerTest`: the `timeRelation` test now exercises real
  `TimeSlot` ids instead of free text (deviation #7 above).

## jdeps / jlink drift gate

`ai.timefold.solver:timefold-solver-core:1.33.0` and its own transitive compile dependency
`io.micrometer:micrometer-core:1.15.12` (pulled in by Timefold itself for solver metrics — not
something this app added directly) both require `jdk.management` (the extended
`com.sun.management.*` MXBeans, not covered by the base `java.management` module already pinned).
`scripts/jre.env`'s `JLINK_MODULES` gained `jdk.management` with a rationale comment; re-ran
`scripts/check-jre-modules.sh backend/target/backend.jar` after the fix — clean (`OK — pinned module
list covers every module jdeps detected`).

## M6a review fixes (Opus review round, "MERGE WITH FIXES")

### Review fix 1 (MAJOR) — pinned-to-waitlist solver pathology: root cause analysis

**Reproduced**: `WaitlistContractTest.pinnedToWaitlistStaysUnassignedAcrossAFullSolve` took ~106 s
at `stepCountLimit=1000` on a 2-player/1-group problem (~1.3e8 move evaluations at ~1.2M/s).

**Instrumentation**: reran the exact fixture with `ai.timefold.solver` DEBUG step logging
(`stepCountLimit=5`). Per-step `accepted/selected move count`:

```
LS step (0)  ... accepted/selected move count (0/240353), picked move (Anna {Grupp 1 -> null})
LS step (1)  ... accepted/selected move count (1/1),      picked move (Anna {null -> Grupp 1})
LS step (2)  ... accepted/selected move count (0/411614), picked move (Anna {Grupp 1 -> null})
LS step (3)  ... accepted/selected move count (1/1),      picked move (Anna {null -> Grupp 1})
LS step (4)  ... accepted/selected move count (0/879323), picked move (Anna {Grupp 1 -> null})
```

**Root cause** (each mechanism verified against the v1.33.0 source/bytecode):

1. **Pins are handled correctly** — no move touching the pinned entity is ever generated or
   evaluated (every logged move targets only the unpinned player). The pathology is NOT a
   pin-exclusion bug; the pin+null fixture is merely the smallest instance of the general class
   "problem already at its optimum with zero acceptable moves".
2. After the Construction Heuristic, the fixture is at its global optimum (`0hard/-500medium`).
   The ONLY doable move left (unpinned player group -> null) strictly worsens medium.
   `LateAcceptanceAcceptor.isAccepted` (verified v1.33.0 source) accepts only `moveScore >=
   lateScore` or (hill-climbing) `>= lastStepScore` — the worsening move fails both forever, and
   no restart/stuck-criterion mechanism exists anywhere in the 1.33 LA/local-search stack (checked
   `LateAcceptanceAcceptor`, `DefaultLocalSearchPhase`).
3. The decider loop (`LocalSearchDecider.decideNextStep`, verified) only exits on (a)
   `forager.isQuitEarly()` — requires an ACCEPTED move (`AcceptedLocalSearchForager`,
   `acceptedCountLimit=1`), (b) phase termination — `stepCountLimit` never fires mid-step, or (c)
   move-iterator exhaustion. With no acceptable move, only (c) remains, and for the default random
   union(change, swap) selector that takes hundreds of thousands of selections (the observed
   240k/411k/879k, growing run lengths under seed 0; `UniformRandomUnionMoveIterator` — verified
   bytecode — only ends once every child iterator reports no upcoming selection).
4. When the iterator finally exhausts with zero accepted moves, `HighestScoreFinalistPodium`
   (verified: rejected moves fill the podium until any move is accepted) makes the forager pick the
   best REJECTED move — the worsening one. The next step trivially (1/1) reverts it, refreshing
   the acceptor's short-term memory with the optimum score, so the following step is stuck again:
   a sawtooth where every second step costs 10^5-10^6 selections, bounded only by termination.

**Why nothing else was affected**: every other fixture/dataset retains acceptable (improving or
sideways-equal) moves at all times, so steps stay cheap; `large-120` (20000 steps) solves in ~4 s.

**Fixes applied** (no move-selector tuning — that would change golden scores and is genuine
solver re-design, out of milestone scope):
- `TestSolverFactory` gained `unimprovedStepCountLimit` overloads (still purely step-based —
  ADR-007 determinism unaffected). `WaitlistContractTest` uses 200 (fixtures with real
  optimization work) / 10 (the converged pinned fixture); the pinned test also asserts wall-clock
  < 10 s (measured: the whole 3-test class now runs in 2.6 s, down from ~110 s). `PinnedOverflowTest`/
  `LockRespectTest` left unchanged — measured fast (0.33 s/0.08 s; their fixtures always retain
  acceptable moves). `SolverRegressionTest` deliberately unchanged (golden scores pinned to plain
  `stepCountLimit=20000`; verified still green against the committed golden file).
- **Every** `SolveProfile` now carries an `unimprovedSpentLimit` (FAST PT5S, NORMAL PT20S as
  designed, THOROUGH PT30S) — deviation from design §9.3's table (only NORMAL had one) with this
  RCA as the strong reason: an already-optimal or heavily pinned plan otherwise burns the full
  wall-clock budget churning rejected selections. Measured with NO_ASSERT + FAST-profile
  termination on the pathological fixture: terminates at ~5.0 s via the unimproved limit (was the
  full 10 s), correct result (`0hard/-500medium`, pin honored).
- `solverConfig.xml`'s safety-net termination gained `unimprovedSpentLimit PT30S` (only relevant
  for a Solver built directly from the XML; both tests and SolverManager jobs replace the whole
  termination block).

### Review fixes 2-8 (minors)

2. **Determinism regression on the right dataset**: `SolverRegressionTest.doubleSolveFrom...` now
   loads `large-120` (the only dataset with enough wish rows to have exposed the ORDER BY bug —
   `small-10` would have passed even with the bug present). Dataset loaded once, assembled twice
   independently, solved twice: score-identical. Runtime ~8 s for both solves.
3. **TOCTOU on concurrent solve start**: `SolveCoordinator.startSolve` now takes a per-plan
   `putIfAbsent` guard (`startingByActivityPlanId`) around the whole critical section, releasing it
   in `finally` — a racing second POST gets a clean 409 instead of both threads passing the
   `getSolverStatus == NOT_SOLVING` check (the loser previously inserted a SOLVING run row and then
   blew up inside SolverManager, stranding the row). If `solveBuilder().run()` itself throws after
   the run row exists, the row is now marked FAILED instead of orphaned. Test:
   `SolveControllerIntegrationTest.twoConcurrentStartRequestsYieldExactlyOneSolveAndNoOrphanRunRow`
   (two latch-synchronized threads; asserts exactly one success + one ConflictException + exactly
   one run row).
4. **Startup recovery**: new `SolverRunStartupRecovery` (`@EventListener(ApplicationReadyEvent)`)
   sweeps `optimization_run` rows stuck in `SOLVING`/`QUEUED` to `FAILED` with
   `result_summary_json = {"error":"avbruten av omstart"}` and a `finished_at` — solver jobs live
   purely in memory, so a non-terminal row at boot can only be a crash leftover. Test:
   `SolverRunStartupRecoveryTest` (sweeps SOLVING + QUEUED, leaves FINISHED untouched).
5. **Weight ceiling enforced**: new `fields.WeightLimits` (`MIN_WEIGHT=1`, `MAX_WEIGHT=10000`);
   `ConstraintWeightService.validateReclassification` and `FieldDefinitionValidator`
   (both `validate` and `validateReservedMediumPatch`) reject weights above the ceiling with 400.
   `ScoreHeadroomTest.MAX_UI_WEIGHT` now references `WeightLimits.MAX_WEIGHT` directly so the
   overflow analysis and the enforcement can never silently drift apart. Tests: weight 10001
   rejected / 10000 accepted (`ConstraintWeightControllerTest`), field weight 10001 rejected
   (`FieldDefinitionCrudControllerTest`).
6. **Locked group with null block rejected pre-solve**: `SolverInputAssembler` now 400s a LOCKED
   `training_group` whose `assignedTrainingBlockId` is null (design §5 pre-solve pin validation:
   `GroupSchedule.trainingBlock` is not unassignable per §1.4, so a pinned null is structurally
   invalid — `@PlanningPin` would freeze it and CH could never initialize the variable). Test:
   `SolverInputAssemblerValidationTest` (locked+null rejected; unlocked+null assembles fine).
7. **Writeback consistency**: `TrainingGroupRepository.updateAssignedTrainingBlock` is now scoped
   to `locked = 0`, matching the player (`updateGroupAndSource ... AND locked = 0`) and coach
   (`deleteUnlockedByGroupId`) writebacks.
8. **Cleanup**: removed the dead `groupByLongId` map in `SolverInputAssembler`;
   `POST .../solve` now reports the ACTUAL `SolverManager` status (`SOLVING_SCHEDULED` until a
   solver thread picks the job up, then `SOLVING_ACTIVE`) instead of a hardcoded string — the
   lifecycle test accepts either.

## Known gaps / deferred to M6b+

- All SOFT constraints (§10.4-10.8, 10.10, 10.12, 10.14, 10.20, 10.21a, 10.22a/b) — M6b.
- `ConstraintWeightOverrides` end-to-end from the UI (weights already flow through
  `SolverInputAssembler`, but no per-plan override has ever been exercised against the solver in a
  test yet, since M4's `PUT .../constraint-weights` predates the solver — M6b should add a
  weight-override-flips-a-tradeoff golden test per its own gate (d)).
- §15.5 "optimera endast X" class-level pinning modes.
- `SavedPlanResourceUsage` real population (`collectSavedPlanUsages`, §6.2) — needs `saved_plan`
  tables (M8); the three `savedPlan*` constraints are implemented, justified and unit-tested now so
  M6b/M8 only need to wire real facts.
- `GreedyBaselineService`.
- No `training_group` PATCH endpoint (rename/resize/`requiredCoachCount`) — the E2E transcript below
  uses direct SQL for the one scenario that needed it.
- `canTimes` (whitelist time field) is parsed by nothing; a future milestone should either wire it as
  a genuine whitelist-intersected-with-blacklist model or formally deprecate it in the field builder.

## E2E verification (packaged jar, dev profile, isolated `--app.data-dir`, port 4999)

Full trimmed transcript in the final report. Summary: season+plan created via the real API
(`defaultGroupTargetSize=10/min=8/max=12`); 4 time slots + `PUT .../courts` (3/4/3/2) -> 12 active
`training_block` rows via the real API; 2 coaches (available all 4 slots) via the real API; 130
participants seeded directly into `participant_profile`/`person`/`player_assignment`/
`custom_field_value(priority)` via SQL from `test-data/datasets/large-120/participants.csv` (task
explicitly allows "import API or direct SQL seed" — no confidential file involved, this is the same
committed anonymized fixture the automated tests use); `POST .../participants/recompute-levels`
(130 recomputed) and `POST .../groups/generate` (12 groups) via the real API; `required_coach_count`
zeroed via SQL for 10 of the 12 groups (kept 2, matching the 2 seeded coaches — no group-editing
endpoint exists yet, see deviation #11).

First solve (`profile=NORMAL`, no time constraints seeded yet): converged `0hard/0medium/0soft`,
`unassignedCount=0` (130 <= 144 max-capacity, so nobody NEEDS to be shed on capacity grounds alone —
matches the golden dataset's own documented invariant that max-capacity alone isn't what produces its
waitlist). Then seeded `cannotTimes` for the 25 participants the fixture documents as having partial/
full time restrictions (mapping the dataset's `t01..t04` short ids to the real created `time_slot`
ids) and re-solved: converged `0hard/-800medium/0soft` in ~20 s wall-clock (profile's
`unimprovedSpentLimit=PT20S` fired before the `PT60S` cap), `unassignedCount=3` — exactly the 3
participants (`p019`/`p026`/`p055`, by email) the fixture documents as permanently unavailable at all
4 slots. `409` confirmed on a concurrent solve attempt.

DB verification after the second solve: `optimization_run` — 2 rows, both `FINISHED`, scores
`0hard/0medium/0soft` (20203 ms) and `0hard/-800medium/0soft` (20278 ms); `player_assignment` — 130
total, all `source=solver`, 127 with a `group_id`, 3 `NULL` (the exact 3 expected waitlisted
participants); every `training_group.assigned_training_block_id` set (all 12); `coach_assignment` —
2 rows (`source=solver`), both coaching-slot groups assigned to the same coach (Anna Åkesson,
`canCoachMaxLevel` fits both groups' level bands — `coachLevelFit` is SOFT/M6b so this was the
search's free choice, not a constraint), confirmed non-overlapping blocks (18.00–19.30 Bana 1 /
19.30–21.00 Bana 2) so `coachNoOverlap` holds; group sizes ranged 8–12 (within the 8/12 min/max
bounds configured). `POST /api/system/shutdown` cleanly stopped the server.
