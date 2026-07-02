# M6b implementation notes (solver completion: soft constraints, locks, greedy, cross-plan)

Design decisions and deviations made while implementing M6b (all SOFT constraints, weights, locks,
greedy baseline, cross-plan blocking, season conflicts). Primary contract:
`docs/design/04-solver.md` as amended by `docs/design/05-solver-verification.md`; `../../utkast-kravspec.txt`
§10 (SOFT rows 10.4–10.22), §13.2 (the Anna example), §14.3–14.4 (sparade planer), §15 (locks); this
milestone's own brief (design gate M-S2). `backend/docs/m6a-notes.md` established the conventions
(SolverIdIndex, ConstraintKeys, determinism rules) this milestone builds on directly.

## Scope actually implemented

- **All SOFT constraints** (§4): `groupSizeTarget` + `groupSizeTargetEmpty`, `groupMinSizeSoft` +
  `groupMinSizeEmpty`, `levelBalance` (SAD via `LevelMath`), `groupOrderByLevel` (cross-multiplied
  adjacent-pair mean comparison), `previousGroupContinuity`, `timePreferenceSoft`, `sameGroupSoft`,
  `differentGroupSoft`, `coachLevelFit`, `coachPreferenceSoft` (the WANT/§10.21a case),
  `lateTimeTopGroups`/`lateTimeBottomGroups` (§10.22a/b — see Deviation 1), `coachPreferredTimeSlot`
  (new, V6). 15 Constraint Streams methods total, all with `justifyWith` + at least one
  `ConstraintVerifier` penalize/reward test + one `justifiesWith` test each.
  `GroupPlanConstraintProvider` now defines 32 constraints total (17 from M6a + 15 new).
- **Locks** (§5, spec §15), all three kinds, each with PUT+DELETE: `AssignmentLockController`
  (`/api/plans/{planId}/assignments/{participantProfileId}/lock`, §15.1),
  `GroupController#lockBlock/unlockBlock` (`/api/groups/{groupId}/lock-block`, §15.2),
  `GroupController#lockCoach/unlockCoach` (`/api/groups/{groupId}/lock-coach`, §15.3). New
  repository methods: `PlayerAssignmentRepository.lockToGroup/unlock`,
  `TrainingGroupRepository.lockToBlock/unlockBlock`, `CoachAssignmentRepository
  .lockToGroup/unlockForCoachAndGroup/findByGroupIdAndCoachProfileId`.
- **§15.5 "Optimera endast X"**: `solver.assemble.OptimizeSelection` record + `SolverInputAssembler
  .applyOptimizeSelection` — pins a whole entity class regardless of individual `locked` flags,
  400s (via `BadRequestException`) if the class isn't fully assigned yet ("requires a
  previously-solved plan", per the design's own note). `SolveController.SolveRequest` gained an
  `optimize` field (`{players,schedule,coaches}`, all default `true` = M6a's exact behavior).
- **Cross-plan blocking** (§6, spec §14.3/§14.4): V6 migration adds `saved_plan`/
  `saved_plan_resource_usage` (schema only — full "spara plan" snapshot flow is M8);
  `savedplan.SavedPlanService.materialize` (minimal internal service, not a REST endpoint yet) turns
  an activity plan's CURRENT assignments into usage rows. `SolverInputAssembler
  .collectSavedPlanUsages` loads every LOCKED saved plan in the same season (excluding the plan
  being solved) and turns its usage rows into `SavedPlanResourceUsage` facts per the §14.4
  checkboxes (`solver.assemble.BlockingOptions`, threaded through `SolveController.SolveRequest
  .blocking`). `conflictsAsWarnings=true` downgrades the three `savedPlan*` HARD constraints to
  `ofSoft(1)` for that one solve via a final override step (never persisted).
- **`ConflictService`** (§6.3, spec §19.2): `GET /api/seasons/{seasonId}/conflicts`
  (`SeasonConflictController`) — a plain reporting service (no Timefold) reading LIVE current
  assignments across every plan in a season regardless of status, sweeping for
  `PERSON_DOUBLE_BOOKED` / `COACH_PLAYS_WHILE_COACHING` / `COURT_DOUBLE_BOOKED`.
- **Greedy baseline** (§9.5, spec §16.7): `solver.run.GreedyBaselineService` — plain Java, no
  Timefold, with the verifier's tie-break fixes (blocks sorted `(epochDay, dayOfWeek, startMinute,
  courtId, blockId)`; coach ties broken `(distance, coachProfileId)`). `POST
  /api/plans/{planId}/solve {"profile":"GREEDY"}` runs it synchronously and writes results back via
  the SAME `SolveCoordinator.persistResult` a real solve uses. Golden scores extended to
  `{dataset: {"solver": ..., "greedy": ...}}` and asserted exactly (greedy is plain
  sorts/comparators, no RNG, so it is exactly as deterministic cross-OS as the solver's own steps).
- Migration `V6__soft_constraints_locks_saved_plan.sql`: one new `constraint_definition` row
  (`coachPreferredTimeSlot`, SOFT, default weight 20) + `saved_plan`/`saved_plan_resource_usage`
  tables. 32 constraints, 20 standard fields (no new fields this milestone).

## Deviations from `docs/design/04-solver.md` (each with reasoning)

### 1. §10.22 (`lateTimeForLowerGroups`) is TWO Constraint Streams methods, not one signed-matchWeight constraint
First attempt implemented design §4 row 10.22's two directions (10.22a penalize top-groups-late,
10.22b reward bottom-groups-late) as ONE constraint sharing the single seeded
`constraint_definition` row, using a SIGNED matchWeight (+1 for the penalize case, -1 for the
reward case) — arithmetically sound (`score = -(weight × matchWeight)` either way) and would have
kept a strict 1:1 db-row↔code-key mapping. **This does not compile-safe at runtime**: Timefold's
`AbstractConstraint.assertCorrectImpact` throws `IllegalStateException("Negative match weight (-1)
for constraint... Check constraint provider implementation")` for ANY negative matchWeight passed
to `.penalize(...)`, verified empirically (a `ConstraintVerifier` test reproduced it immediately).
Fix: two separate methods, `lateTimeTopGroups` (`.penalize`) and `lateTimeBottomGroups`
(`.reward`), both fed from the SAME db row's weight via `ConstraintKeys.COMPLEMENTS_OF`'s fan-out
mechanism, generalized beyond its original empty-group-complement purpose:
`LATE_TIME_FOR_LOWER_GROUPS` is a key that exists in the fan-out map's KEYS but NOT in
`ConstraintKeys.IMPLEMENTED` (no code calls `.asConstraint` with it) — `SolverInputAssembler
.buildConstraintWeightOverrides` was generalized to process a `constraint_definition` row if it is
EITHER directly implemented OR a fan-out parent (previously it required `IMPLEMENTED.contains`,
which would have silently dropped this row's weight entirely).

### 2. Complement-key fan-out generalized to a two-children, no-self-implementation case
`ConstraintKeys.COMPLEMENTS_OF` (docs/design/05-solver-verification.md's minor finding, only
covering the empty-group case in M6a's reservation) now also maps
`lateTimeForLowerGroups → {lateTimeTopGroups, lateTimeBottomGroups}`. Verified with a dedicated
`ConstraintWeightFanOutTest` covering all three fan-out shapes (parent-with-own-code
`groupSizeTarget`/`groupMinSizeSoft`, and parent-without-own-code `lateTimeForLowerGroups`),
including the surprising-but-correct sign flip: `ConstraintAnalysis.weight()` is the DIRECTIONAL
per-match factor (negative for `.penalize()`, positive for `.reward()`), which differs from
`ConstraintWeightOverrides.getConstraintWeight()`'s unsigned configured magnitude — both are
legitimate, differently-scoped parts of the Timefold API surface; the test asserts both.

### 3. `moveCountLimit` backstop added to two hand-built full-solve tests (not to the shared `TestSolverFactory`)
`WeightOverrideFlipTest` and `CrossPlanBlockingTest` build tiny synthetic `GroupPlanSolution`s where,
once the intended optimum is reached, EVERY remaining move strictly worsens the score — exactly
m6a-notes.md's "Review fix 1" pathology (`UniformRandomUnionMoveIterator` churning hundreds of
thousands to millions of rejected move selections per non-improving step). Unlike M6a's
`WaitlistContractTest` fixture, `unimprovedStepCountLimit` alone (even the "tight" value of 10) was
NOT sufficient here — reproduced with `jstack` on a hung run: the thread was parked inside
`LocalSearchDecider.decideNextStep` → `UniformRandomUnionMoveIterator.next()`, 17.7s CPU and
climbing, having not yet completed even ONE non-improving step (per m6a's own RCA, "phase
termination — stepCountLimit never fires mid-step" — and neither does `unimprovedStepCountLimit`,
since it is also a step-boundary check). `moveCountLimit` DOES intervene mid-step (it counts
individual move evaluations, not completed steps), so both files build their own `SolverFactory`
(bypassing `TestSolverFactory`) with `stepCountLimit + moveCountLimit(200_000)`. Deliberately NOT
added to the shared `TestSolverFactory`: `large-120`'s golden regression legitimately evaluates far
more than 200k moves over its full `stepCountLimit=20000` budget, so a global cap would silently
truncate that solve before it reaches feasibility. Verified safe: Timefold tracks best-solution
separately from the churning working solution, so an interrupted-mid-churn solve still returns the
already-found optimum (confirmed: both fixtures' construction-heuristic-phase logs already showed
the correct optimal score BEFORE local search even started).

### 4. `ActivityPlanRepository.update()` cannot move a plan between seasons (not a bug — discovered while testing)
`activity_plan.season_plan_id` is set once at creation and deliberately excluded from `update()`'s
SET clause (a plan belongs to one season permanently, matching the domain model — nothing in spec
§7.6 suggests season migration). Discovered this while writing `SavedPlanUsageAssemblyTest`: an
initial `moveIntoSameSeason` test helper that called `update()` after loading a plan was a silent
no-op. Fixed at the TEST-INFRASTRUCTURE level, not the production code: `TestDatasetLoader` gained
`load(String datasetName, String seasonPlanId)` (null = fresh season, matching `load(String)`'s
existing behavior) so cross-plan-in-the-same-season tests load both plans into the right season
from the start.

### 5. Cross-plan blocking source set is `saved_plan.status == 'locked'` only, not design §6.2's `{saved, locked, published}`
The milestone brief explicitly says "OTHER plans' `saved_plan` rows with `status='locked'`", which
also matches spec §14.3's literal text ("**En låst plan** ska kunna användas som hard constraint för
andra planer") more precisely than the design doc's broader three-status set. Implemented as
specified in the brief: `SavedPlanRepository.findLockedInSeasonExcludingPlan` filters to
`status = 'locked'` exactly. A future milestone widening this to `saved`/`published` is a one-line
repository change if the product decision changes.

### 6. `coachLockCoach`'s `slotIndex` is validated but not structurally enforced
`coach_assignment` has no `slot_index` column (spec §7.12's schema, unchanged by this milestone);
`SolverInputAssembler` has always mapped `CoachSlot` slot N to the Nth `coach_assignment` row (by
id order) for a group — a positional convention, not an explicit column. `PUT
.../lock-coach {coachProfileId, slotIndex}` accepts `slotIndex` and validates it against the
group's `requiredCoachCount` (400 if out of range) but does not — cannot, without a schema change —
force a *specific* numbered slot; the actual slot a locked coach lands on remains positional,
exactly matching design §5's own "pins the lowest-index free slot, deterministically" rule for a
group with no prior coach assignment. Documented in `CoachAssignmentRepository.lockToGroup`'s
javadoc; a genuine schema change (adding `slot_index`) is a legitimate follow-up if multi-coach
groups with independently-lockable slots become a real requirement.

### 7. `GreedyBaselineService` ignores `pinned`/locks entirely (a documented simplification, not a bug)
Plain Java, no `@PlanningPin` mechanism to respect. Safety comes from the DB layer instead: every
`SolveCoordinator.persistResult` repository call (`updateGroupAndSource`,
`updateAssignedTrainingBlock`, `deleteUnlockedByGroupId`) is already scoped to `locked = 0` — the
SAME writeback method a real solve uses — so a locked entity's IN-MEMORY value may be overwritten
by greedy's plain-Java pass, but the DB write for it is always a no-op. Verified via
`LockAndResolveIntegrationTest`-style reasoning is unnecessary for greedy specifically since the
guarantee lives entirely in the repository layer, already covered by the lock+resolve test using
the real solver; `GreedySolveIntegrationTest` only asserts greedy's own synchronous
run/writeback/response shape.

### 8. `timePreferenceSoft` implemented as a single monotone penalty, not reward+penalty
This is the design doc's OWN explicit choice (§4 row 10.10: "single monotone penalty... instead of
mixed reward/penalty"), not a new deviation introduced here — flagged because the milestone brief's
paraphrase ("preferred slots reward / allowed-but-unpreferred penalty") reads like the raw spec
text (kravspec §10.10), which literally asks for both. Followed the design doc (the actual
implementation contract, already reasoned through and verifier-approved) over the brief's
restatement of the spec: penalizing every unpreferred placement for a player who expressed ANY
preference is trade-off-equivalent to reward+penalty for ranking purposes, while keeping the score
direction uniformly monotone.

## Weight reconciliation vs design §4 (M6b review fix F1 — decision recorded)

**Decision: the V2/V5/V6 seed weights SUPERSEDE the design §4 table's per-constraint defaults.**
Rationale: kravspec §17.2's own worked example is the authority on intended priority ordering —
*"Kompisönskemål var soft 60, medan nivåbalans var soft 100 och maxstorlek var hard"* — i.e. level
balance (100) dominates friend wishes (60–80), which is exactly the ordering the M1-seeded V2 rows
encode (`levelBalance` 100 > `sameGroupSoft` 80 > `differentGroupSoft` 60). The design §4 table's
per-point rescaling (`levelBalance` 2/pt, `coachLevelFit` 80/pt, `groupOrderByLevel` 5/pt) was the
outlier: written against a hypothetical rebalanced scale that no seed, no spec example, and no
already-tested M1/M4 code ever used. Every weight remains user-overridable per plan (spec §9.4), so
this is only about DEFAULTS. Full divergence table (constraint code defaults in
`GroupPlanConstraintProvider` match the seeds, so "seeded" = "applied" for a fresh install):

| DB key (V2/V5/V6 seed) | Design §4 default | Seeded default | Divergence | Unit semantics |
|---|---|---|---|---|
| `groupSizeTarget` (+`groupSizeTargetEmpty`) | 50/spelare | 50 | same | per player of deviation from targetSize (per-point) |
| `groupMinSizeSoft` (+`groupMinSizeEmpty`) | 100/saknad | 50 | **diverges** | per missing player below minSize (per-point) |
| `levelBalance` | 2/nivåpoäng | 100 | **diverges** (spec §17.2's exact 100) | per whole level point of SAD spread (per-point) |
| `groupOrderByLevel` | 5/nivåpoäng | 50 | **diverges** | per whole level point of adjacent-pair mean inversion, +1 floor (per-point) |
| `previousGroupContinuity` | 30/steg | 30 | same | per groupOrder step of distance from previous group (per-point) |
| `timePreferenceSoft` | 40 | 40 | same | per placement on an allowed-but-unpreferred time (per-match) |
| `sameGroupSoft` | 60 | 80 | **diverges** | per broken WANT_SAME wish pair (per-match) |
| `differentGroupSoft` | 60 | 60 | same (spec §17.2's exact 60) | per violated WANT_DIFFERENT wish pair (per-match) |
| `coachLevelFit` | 80/nivåpoäng | 50 | **diverges** | per whole level point the group mean sits outside the coach's band (per-point) |
| `coachPreferenceSoft` (design: `coachWishWanted`) | 40 reward | 50 | **diverges** | per fulfilled WANT coach wish (per-match, reward) |
| `lateTimeForLowerGroups` → `lateTimeTopGroups`/`lateTimeBottomGroups` | 20 penalize / 10 reward | 30 / 30 (one row) | **diverges** (see F2 note below) | per top group scheduled late (per-match, penalty) / per bottom group scheduled late (per-match, reward) |
| `coachPreferredTimeSlot` (V6, new) | — (not in design table) | 20 | n/a | per coach slot landing on a PREFERRED time (per-match, reward) |
| `unassignedPlayer` | MEDIUM 100 × prioritet | 100 | same | per waitlisted player, matchWeight = priority 1–5 (per-match × priority) |

`docs/design/04-solver.md` §4 carries a header note pointing here since this fix.

### F2 note — symmetric late-time weighting (documented future option)
Design §4 wanted an ASYMMETRIC late-time pair (top groups penalized at 20, bottom groups rewarded
at 10). The implementation fans BOTH directions out of the single seeded `lateTimeForLowerGroups`
row (weight 30), so they are necessarily SYMMETRIC (30/30). If the asymmetry is ever wanted, the
localized change is: a migration adding a second `constraint_definition` row (e.g.
`lateTimeBottomGroups`, weight 10, retuning the existing row to 20 for the top direction), moving
`LATE_TIME_BOTTOM_GROUPS` out of `ConstraintKeys.COMPLEMENTS_OF` and into `IMPLEMENTED`-with-own-row
— no constraint code changes needed. Not done now: no product signal favors the asymmetry, and one
user-visible knob ("Sen tid för lägre grupper") is simpler than two.

### F4 note — unlock leaves `source='locked'` (intentional display behavior)
`DELETE .../lock` (all three kinds) flips ONLY the `locked` flag; the row's `source` column keeps
saying `locked` until the next solve rewrites it (`source='solver'`) or a manual move sets
`manual`. Intentional: `source` records the PROVENANCE of the current value ("this placement exists
because someone locked it there"), which remains true after unlocking — the placement hasn't
changed, only its future mutability has. A UI should read the `locked` flag (not `source`) for the
padlock state.

## New bug found and fixed during this milestone (via manual E2E verification, not a design deviation)

`ConflictService.courtConflicts` originally swept the SAME per-person usage list built for
`personConflicts` — but a court is occupied by a GROUP, and that list has one row PER PLAYER/COACH.
On the E2E large-120 fixture (12-player groups sharing a season-wide "Bana 1" with another plan),
this produced **11 duplicate `COURT_DOUBLE_BOOKED` entries for one real conflict** (one per
opposing-player pairing). Fixed with a dedicated `collectGroupCourtUsages` (one row per
plan+group-with-an-assigned-block, independent of player/coach count) used only by the court sweep;
`personConflicts` keeps using the per-person list, which is correct for its own purpose. Regression
test added to `ConflictServiceTest` (asserts exactly 2 court conflicts, not up to 50, for a
multi-player-group fixture) — this is exactly the kind of bug automated tests checking only
*presence* of a conflict type (not its count) would never catch; the E2E pass against the packaged
jar is what surfaced it.

## Tests

`./mvnw clean verify`: **408 tests, 0 failures, 0 errors** (M6a baseline: 324 → +84 net). New/
extended files:
- `solver.constraints.SizeSoftConstraintsTest` (10), `.LevelSoftConstraintsTest` (10),
  `.TimeAndCoachSoftConstraintsTest` (26) — every SOFT constraint's `ConstraintVerifier` coverage:
  one penalize/reward-with-exact-value case, one no-impact case, one `justifiesWith` case each, plus
  adjacency/direction/empty-group edge cases (`groupOrderByLevel`'s non-adjacent-pairs-never-compared
  case, `lateTime*`'s middle-group/disabled-policy cases).
- `solver.assemble.ConstraintWeightFanOutTest` (3) — the verifier-mandated fan-out test, both
  fan-out shapes.
- `solver.WeightOverrideFlipTest` (1) — design gate (d): hand-built two-group/two-friend fixture
  where `groupSizeTarget` (cost 0 vs 100) and `sameGroupSoft` (cost 80 vs 0) trade off exactly at
  weight 80 vs 100; cranking `sameGroupSoft` to 150 deterministically flips the solver's choice,
  asserted both via the resulting group placement AND via `ScoreAnalysis` per-constraint scores.
- `solver.CrossPlanBlockingTest` (2) — THE Anna example (spec §13.2 verbatim) at the
  constraint/solver level: Anna cannot land in the 18.00 Dam group (hard-blocked by her Herr-plan
  coaching commitment, naturally avoided by the solver) but freely lands in 19.30; a companion test
  forces her into the blocked slot via an explicit lock and confirms the violation is real (not a
  false negative).
- `solver.assemble.SavedPlanUsageAssemblyTest` (4), `.OptimizeSelectionTest` (7) — the real DB→
  assembler wiring for cross-plan blocking and §15.5 class-pinning respectively.
- `solver.LockAndResolveIntegrationTest` (1) — design gate (b): locks all three kinds via the same
  repository methods the REST endpoints use, runs a real async solve end-to-end, asserts every
  locked value survived while the rest of the plan was still optimized.
- `api.LockEndpointIntegrationTest` (7), `.GreedySolveIntegrationTest` (2),
  `.SeasonConflictControllerTest` (3) — REST-layer coverage for the new endpoints.
- `season.ConflictServiceTest` (4, incl. the court-deduplication regression above).
- `solver.run.GreedyBaselineServiceTest` (4) — deterministic tie-break coverage (day-first block
  sort, coach-id tie-break, fit-before-id ranking).
- Extended `solver.regression.SolverRegressionTest`, `.TestDatasetLoader`, `db.FlywayMigrationTest`,
  `api.ConstraintDefinitionControllerTest`, `api.ConstraintWeightControllerTest`,
  `solver.constraints.{CoachConstraintsTest,OverlapConstraintsTest,SavedPlanConstraintsTest}` (the
  latter three only for the `CoachFact` constructor signature change — new `preferredTimeSlotIds`
  field), `solver.LockRespectTest` (same reason).

## Golden scores (regenerated, `-DupdateGoldenScores=true`, fresh `clean verify` run)

```json
{
  "small-10":         {"solver": "0hard/0medium/-83600soft",   "greedy": "0hard/0medium/-88600soft"},
  "coach-overlap-20":  {"solver": "0hard/0medium/-95050soft",   "greedy": "-3hard/0medium/-93300soft"},
  "large-120":         {"solver": "0hard/-1200medium/-2344940soft", "greedy": "-17hard/0medium/-212100soft"}
}
```

Hard stays 0 for every dataset's real solve (unchanged from M6a — SOFT constraints never touch
feasibility). `small-10`'s large soft magnitude (-83600, almost entirely `levelBalance`) is NOT a
bug: this 10-player "Torsdag Nybörjare" fixture's `ranking_points` genuinely span 325–920 (the
fixture's own README documents no such invariant either way) across only 2 groups of 5, so a
natural minimum spread of several hundred level-points, at `levelBalance` weight 100/point, is
real and unavoidable — verified by hand-computing the exact SAD for the two natural level-sorted
slices before trusting the number. Greedy is expected to be worse-or-infeasible relative to the
real solve (`coach-overlap-20`/`large-120` both show `hard < 0` for greedy) — it is a naive
baseline, not a solver, exactly per spec §16.7's own framing ("enklast möjligt").

## E2E verification (packaged jar, isolated `--app.data-dir`, port 4999)

Full flow against `target/backend.jar` (produced by `./mvnw clean verify`), reusing M6a's own
large-120 setup steps (season+plan via the real API, 4 time slots + `PUT .../courts` (3/4/3/2) → 12
blocks, 2 coaches, 130 participants SQL-seeded from `test-data/datasets/large-120/participants.csv`
— same anonymized fixture the automated tests use, no confidential file involved — including this
time its full wish/preference/coach-wish columns so the new SOFT constraints have real data to act
on), `POST .../recompute-levels` (130), `POST .../groups/generate` (12 groups),
`required_coach_count` zeroed via SQL for 10 of 12 groups (kept groups 1 and 7, matching the 2
seeded coaches).

**Solve NORMAL**: `0hard/-800medium/-337920soft`, 28.1s (unimproved-spent cutoff fired). Medium
-800 = 3 unassigned × priority-scaled cost — the SAME permanently-unavailable participants M6a's
own transcript documented (p019/p026/p055-equivalent), confirming determinism carries across
milestones on identical seed data. Soft is clearly nonzero, proving the new constraints are live.
Group order (mean `estimated_level` per group, by `groupOrder`): `953→872→698→621→661→454→414→276→
340→250→779→509`. Mostly descending as `groupOrderByLevel` intends, with two visible inversions
(group 5 vs 6 is fine but 8→9 and 10→11 invert) — reported HONESTLY, not smoothed over: this is the
design's own documented, accepted limitation (adjacent-pairs-only comparison, §4 row 10.7's risk
note) colliding with strong data-driven anchors on a real 130-player fixture with genuine
`mustPlayWith`/`sameGroupSoft` pressure (3 HARD must-pairs + 15 friend wishes in the dataset) that
can force specific players away from their pure level-sorted slot within a single `NORMAL`-profile
time budget — not a bug, and consistent with the constraint's SAD/adjacent-mean-comparison design
never claiming a total order, only a local monotonicity pressure.

**Lock + re-solve**: `PUT .../assignments/{id}/lock {groupId: group12}` on a participant whose
`NORMAL`-solved placement was group 10 → `player_assignment.locked=1`. Re-solved `FAST` (5.4s,
`0hard/-800medium/-377270soft` — same 3 unplaceable, slightly worse soft as expected from a smaller
time budget plus the constrained lock). Confirmed via direct DB read: `group_id` still `group12`,
`locked=1` — unchanged across the full async solve lifecycle.

**GREEDY**: `POST .../solve {"profile":"GREEDY"}` → `200 {"status":"FINISHED"}` immediately
(synchronous). `optimization_run` row: `-17hard/0medium/-204120soft`, `duration_ms=6`. Hard
violations are expected (greedy has no capacity/overlap awareness, spec §16.7's own framing);
medium=0 confirms greedy places EVERYONE (no waitlist logic, per `GreedyBaselineService`'s
javadoc). The previously-locked participant's row was untouched (`source=locked`, group12) while
all 129 others got `source=solver` — confirming greedy's writeback respects locks exactly like a
real solve, via the shared `persistResult` path.

**Conflicts**: created a second "Dam torsdag" plan in the same season, reused one Herr participant's
`person_id` as a Dam participant scheduled at the SAME wall-clock time (Torsdag 21.00–22.30) their
Herr group already occupied. `GET /api/seasons/{id}/conflicts` correctly reported exactly one
`PERSON_DOUBLE_BOOKED` (that shared person, both plans/groups/times listed) and exactly one
`COURT_DOUBLE_BOOKED` (a different, incidentally-shared physical court at the same time) — this is
what surfaced and confirmed the fix for the duplicate-court-conflict bug documented above (the
FIRST run of this exact scenario, before the fix, produced 11 duplicate `COURT_DOUBLE_BOOKED`
entries).

`POST /api/system/shutdown` cleanly stopped the server both before and after the fix/rebuild cycle.

## M6b review fixes (Opus review round, "MERGE WITH FIXES")

1. **F1 — weight reconciliation**: decision + divergence table recorded above ("Weight
   reconciliation vs design §4"); `docs/design/04-solver.md` §4 gained the superseded-weights header
   note. Documentation only — no score-affecting change.
2. **F2 — symmetric late-time weighting**: documented above (F2 note) as an intentional consequence
   of the one-seeded-row fan-out, with the exact future migration path if 20/10 asymmetry is ever
   wanted. Documentation only.
3. **F3 — symmetric unassignedPlayer guardrail**: `SolverInputAssembler.buildConstraintWeightOverrides`
   now applies the same MEDIUM/enabled/weight≥1 fail-closed check to the `constraint_definition`
   seed row (reachable only by rogue migration/manual SQL) that M6a already applied to the per-plan
   `constraint_weight_config` override. Test:
   `SolverInputAssemblerValidationTest.corruptedUnassignedPlayerDefinitionRowIsRejectedBeforeSolve`
   corrupts the row via direct SQL (all three variants: reclassified, disabled, zero-weighted),
   asserts the assembler throws, restores the seed in `finally` (the test class shares one DB).
4. **F4 — unlock leaves `source='locked'`**: documented above (F4 note) as intentional
   provenance-vs-mutability semantics. Documentation only.
5. **F5 — 409 on lock/unlock while solving**: new `SolveCoordinator.assertNoActiveSolve(planId)`
   (covers SOLVING_SCHEDULED, SOLVING_ACTIVE and the racing-start window via the M6a TOCTOU guard
   map) called first in all six lock/unlock endpoints (`AssignmentLockController` PUT+DELETE,
   `GroupController` lock-block/lock-coach PUT+DELETE). Design §9.4's "mutating endpoints return 409
   while SOLVING_ACTIVE" — a lock flipped mid-solve would be invisible to the running solve's
   already-assembled snapshot AND could be overwritten by its writeback. Test:
   `LockEndpointIntegrationTest.lockingAPlayerWhileASolveIsRunningReturns409` (starts a THOROUGH
   solve, asserts 409 + untouched DB row, cancels and awaits NOT_SOLVING in `finally`).
6. **F6 — honest greedy response**: `SolveCoordinator.runGreedy` now returns a `GreedyResult`
   record (runId + score); the synchronous `POST .../solve {profile:GREEDY}` response is
   `{runId, status:"FINISHED", score, hardViolations, feasible}` (`GreedySolveResponse`).
   `hardViolations = max(0, -hardScore)` — the exact violation count at the seeded weight-1 HARD
   defaults, an upper-bound proxy if a HARD weight was raised. Tests: the existing happy-path test
   now asserts `hardViolations=0`/`feasible=true`;
   `GreedySolveIntegrationTest.greedyOnAConstrainedPlanSurfacesHardViolationsInTheResponse` gives a
   participant a `cannotTimes` covering EVERY slot (greedy has no availability awareness, so a
   `timeAvailabilityHard` violation is guaranteed) and asserts `feasible:false`,
   `hardViolations >= 1`, and a hard-negative score string.

None of F3/F5/F6 touch scoring; golden scores verified unchanged after the fixes (same
`expected-scores.json` byte-for-byte).

## Known gaps / deferred to M7+

- `saved_plan`'s full "spara plan" flow (spec §14.1: snapshot_json of groups/players/coaches/blocks/
  weights/locks/score, status-transition UI, restore) — M8 per docs/plan.md.
  `savedplan.SavedPlanService.materialize` is deliberately minimal (usage-row materialization only,
  `snapshot_json`/`score`/`optimization_run_id` left `null`), matching the milestone brief's
  explicit "you need the tables + a minimal internal service... for testing" scope.
- No REST endpoint for `SavedPlanService.materialize` yet (`saved_plan` creation is internal-only
  this milestone, exercised directly by tests) — M8 wires the real "spara/lås plan" UI action.
- `LateTimePolicy` real per-plan configuration (§10.22's actual data source: which slot-minute
  counts as "late", which group orders are "top"/"bottom") is still never assembled from the DB —
  `SolverInputAssembler` supplies `LateTimePolicy.DISABLED` unconditionally, exactly as M6a left it.
  The constraint code, justifications, and tests are complete and ready; only the (currently
  nonexistent) per-plan policy configuration + its assembly wiring remain, a natural, small M7
  follow-up once a UI surface for it exists.
- Explainability/what-if (`ScoreAnalysis`-backed per-player/group/plan explanations, `WhatIfService`)
  — M7 per the original 3-milestone split; M6b's `ConstraintWeightFanOutTest`/`WeightOverrideFlipTest`
  already exercise the `SolutionManager.analyze` pipeline enough to confirm it works end-to-end for
  M7 to build on directly.
- `coachLockCoach`'s slot-index limitation (Deviation 6) — a real schema change if ever needed.
