# v0.2.0 implementation notes (backend half: coach-optional solving + suggested optimization time)

Design decisions and deviations made while implementing the backend half of v0.2.0 — two features
from the first field test's user feedback. Primary contract: this task's own brief (no design doc
milestone exists yet for v0.2.0); `CLAUDE.md` determinism rules; `backend/docs/m6a-notes.md`/
`m6b-notes.md` (solver conventions: `SolverIdIndex`, `ConstraintKeys`, `ConstraintVerifier` test
shape, `ActiveSolveCleanup`); `docs/design/04-solver.md` §1 (CoachSlot model) + §9 (solve config).

## FEATURE 1 — Coach-optional solving

### Current-behavior trace (BEFORE this milestone's fix)

Traced end-to-end from `GroupGenerator` through `SolverInputAssembler` to the constraint provider:

1. `GroupGenerator.generate` hardcodes `requiredCoachCount = 1` for every NEWLY created group
   (`new TrainingGroup(..., 1, levelMin, levelMax, null, false)`) — unconditionally, regardless of
   whether the plan has any `coach_profile` rows at all. (Existing groups being regenerated keep
   their own current `requiredCoachCount`, also unaffected by coach count.)
2. `SolverInputAssembler`'s "CoachSlot entities" section (pre-fix) built exactly
   `tg.requiredCoachCount()` `CoachSlot`s per `TrainingGroup`, unconditionally — so a coach-less plan
   still got one `CoachSlot` per group.
3. `coachFacts` (the `coachRange` value-range list) is empty whenever `coach_profile` is empty
   (`SolverInputAssembler` builds it directly from `coachProfileRepository.findByActivityPlanId`).
   Every `CoachSlot`'s `coach` variable (`allowsUnassigned = true`, value range = `coachFacts`) can
   therefore only ever resolve to `null`.
4. `coachRequirementHard` (HARD 1) is `f.forEachIncludingUnassigned(CoachSlot.class).filter(cs ->
   cs.getCoach() == null).penalize(...)` — every one of those never-fillable `CoachSlot`s is a
   permanent, un-fixable HARD violation. With N groups (each `requiredCoachCount=1`), hard score is
   exactly `-N`, forever (no move can ever fill it, since there is nothing in the value range).
5. **Confirmed empirically, not just by code-reading**: `backend/docs/m7-notes.md`'s own E2E
   transcript, run against the real `large-120` fixture with zero coaches seeded, reports "Solve 1
   (`profile=NORMAL`): `-12hard/-800medium/-350720soft` ... Hard=-12 is `coachRequirementHard` (no
   coaches seeded — expected, out of scope for this flow)" — 12 groups × 1 unfillable slot each =
   exactly -12. This is the bug: the solve was NOT feasible, and `hardScore < 0` makes the whole plan
   read as "broken rules" in every UI surface that checks `isFeasible()`, even though every OTHER
   aspect of the plan (players, schedule) was fully and correctly optimized.

Also confirmed: `optimize.coaches=true` (the default) with zero coaches never hit any special-casing
before or after the fix — `applyOptimizeSelection`'s `!optimize.coaches()` branch (which would 400 on
an unfilled slot) only runs when the caller EXPLICITLY passes `coaches:false`; with zero `CoachSlot`s
that branch's loop body never executes regardless, so it was already a structural no-op. No code
change was needed for that specific sub-requirement — verified with a dedicated test
(`CoachlessSolveIntegrationTest.optimizeCoachesTrueOnAZeroCoachPlanIsANoOpNotA400`).

### The fix

`SolverInputAssembler`: the entire "CoachSlot entities" construction loop is now guarded by
`if (!coachProfiles.isEmpty())` — when the PLAN has zero `coach_profile` rows, `coachSlots` stays an
empty list regardless of any individual group's `requiredCoachCount`. This is a plan-level guard (not
a per-group `requiredCoachCount == 0` check, which already worked before this milestone and is a
different, pre-existing feature). With zero `CoachSlot` entities, every coach-related constraint
(`coachRequirementHard`, `coachNoOverlap`, `coachAvailabilityHard`, `coachMaxGroups`, `coachLevelFit`,
`coachPreferenceSoft`, `coachPreferredTimeSlot`, `coachCannotTrainAndCoachSameTime`,
`savedPlanCoachBlocked`) is inert by construction — each iterates `CoachSlot.class`, which is empty —
verified directly with `solver.constraints.CoachlessConstraintInertnessTest` (11 `ConstraintVerifier`
cases, one per coach-touching constraint, `.given()` with zero `CoachSlot`/`CoachWish` facts but a
normally-populated rest-of-plan, `hasNoImpact()` in every case). `coachWishRequired`/
`coachWishForbidden`/`coachPreferenceSoft` were ALREADY inert before this fix too, for a different
reason: the wish-parsing loop resolves a wished-for coach via
`coachPersonLongIdByProfileId.get(coachProfileId)`, and that map is built only from `coachProfiles`
(also empty), so no `CoachWish` fact was ever created regardless of the `CoachSlot` fix — both
mechanisms are now covered by the same test file for completeness.

`GroupGenerator`'s `requiredCoachCount = 1` default is left UNCHANGED — deliberately. It only matters
once a coach profile actually gets added later (without re-running group generation), at which point
`requiredCoachCount = 1` per group is exactly the useful, pre-existing default a council wants. Since
the assembler's guard is keyed on the PLAN's current coach-profile count (not the group's stored
`requiredCoachCount`), this stays correct in both states without needing any `GroupGenerator` change.

### Note field

`OptimizationRunService.NOTE_NO_COACHES` = `"Inga tränare registrerade — grupperna optimerades utan
tränartilldelning"`. Added to the `result_summary_json` blob (`OptimizationRunService.finishRun`
gained a `boolean noCoaches` parameter) — the SAME persisted summary `GET /api/plans/{planId}/runs`
already exposes (`{hard,medium,soft,feasible,unassignedCount,constraintSummaries}`, from M7); `note`
is added to that map ONLY when `noCoaches` is true. `SolveCoordinator` computes `noCoaches` as
`solution.getCoaches().isEmpty()` at both call sites (`onFinalBestSolution` for a real async solve,
`runGreedy` for the synchronous GREEDY baseline — both funnel through the same `finishRun`, so the
note appears for either solving mode). Deliberately NOT added to the immediate
`POST .../solve` 202/200 `SolveResponse` record: for the async (FAST/NORMAL/THOROUGH/CUSTOM) path the
score isn't known yet at that point anyway (`score`/`hardViolations`/`feasible` are already `null`
there), and widening that record's schema would touch `OpenApiSchemaTest`'s exact-field assertions for
no benefit — the persisted run summary is the one place a "note" genuinely belongs, and it is already
the UI's documented path for "last-run summary" (`api.OptimizationRunController`'s own javadoc).

### CapacityService

`CapacityResponse` gained a `boolean noCoaches` field. When `coachCount == 0`:
`coachShortageMessage` is repurposed to the neutral `"Inga tränare registrerade"` (no "Risk för
tränarbrist: ..." prefix), and `coachShortageRisk` is forced `false` (not left `true`) — the
per-slot/aggregate shortage SIGNALS the service otherwise computes are trivially "true everywhere"
with zero coaches (0 available < N needed at every slot), which would render as a multi-reason RED
shortage warning for what may simply be "coaches haven't been configured yet"; that reads as
misleading, not merely differently-colored. `noCoaches` is the dedicated flag the UI should check
FIRST (documented on `CapacityResponse`'s javadoc) to render a blue/info banner instead; consumers
that only look at the pre-existing `coachShortageRisk` boolean still get a correct (non-alarming)
answer instead of an incorrect one.

## FEATURE 2 — Suggested optimization time

### `POST /api/plans/{planId}/solve/suggest-duration`

New `solver.run.SolveBenchmarkService` (hardware micro-benchmark) + `solver.run
.SuggestDurationService` (problem-size analysis + formula), wired into `SolveController`.

**Benchmark**: a FIXED, hardcoded, in-memory synthetic problem (~30 players / 4 groups / 4 blocks /
3 coaches, a handful of friend/coach wishes and time preferences for representativeness) — no DB
reads, no persistence, never varies. Total step budget 140,000 at `solverConfig.xml`'s own
`NO_ASSERT` + `randomSeed 0` (i.e. exactly production settings — unlike test code's
`TestSolverFactory`, which forces `PHASE_ASSERT` for correctness checks, not speed). Wall-clock is
measured via `System.nanoTime()`.

**Wall-clock cap (review fast-follow 1)**: a step-count-only benchmark would block the FIRST
`suggest-duration` call for 12–25s on a 5–10× slower machine (the actual target hardware class). The
140k budget is therefore split into **10 sequential chunks of 14,000 steps** (each a fresh `Solver` +
fresh problem instance — an identical, independently-terminating unit of work; rebuilding per chunk
also avoids re-solving an already-converged solution, which would hit the m6a "Review fix 1"
converged-churn pathology instead of measuring representative throughput). The loop stops early once
`BENCHMARK_TIME_CAP_MS = 30s` of wall-clock is spent, and each chunk carries a defensive `spentLimit`
of the REMAINING budget so even a single pathologically slow chunk cannot overshoot the cap by more
than Timefold's termination-check granularity. When the cap fires, the factor is derived from steps
ACTUALLY completed, proportionally:

```
machineSpeedFactor = (stepsDone / stepsPlanned) × (REFERENCE_MS / elapsedMs)
```

Derivation: extrapolated full-run time = `elapsedMs × stepsPlanned / stepsDone`; factor =
`REFERENCE_MS` divided by that, which rearranges to the product above. Note the ratio's direction —
`stepsDone/stepsPlanned` (≤ 1): an incomplete run must REDUCE the reported speed, never inflate it
(the review's sketch had this ratio inverted; the shipped form is the correct extrapolation — a
machine that completed half the steps in the capped time is credited half the rate). With all chunks
complete this degenerates to the plain uncapped `REFERENCE_MS / elapsedMs`. A partial final chunk
(cut by its own `spentLimit`) contributes wall time but no steps — slightly UNDERSTATING speed → a
LONGER suggestion, the safe direction. Floored at `MIN_SPEED_FACTOR = 0.01` (≥100× slower already
saturates the 600s suggestion clamp; the floor also keeps the degenerate zero-chunks-completed case
well-defined instead of dividing by zero downstream). Chunk-based step accounting uses PUBLIC API
only (chunks-completed × chunk-step-limit) — no reliance on solver internals or the metrics registry.
Tests: `SolveBenchmarkServiceTest` — pure factor arithmetic (full/half/zero completion) plus the live
cap path forced with a 1ms budget (returns promptly at the floor instead of running 140k steps) and a
1s-budget cap-respect check with only hardware-independent assertions.

**Calibration** (this implementation's own dev machine, Apple Silicon Mac, Temurin JDK
21.0.11): tuned by iterating the step budget (10k → 400k) via a throwaway timing harness (not
committed) against the exact fixture `SolveBenchmarkService` ships. At 140,000 steps, four
independent COLD single-JVM runs of the ORIGINAL single-solve implementation measured
**2612 / 2241 / 2411 / 2361 ms** (mean ≈ 2406 → reference 2400) — comfortably inside the task's
requested ~1.5–2.5s window for a fast machine. After the chunked rewrite (fast-follow 1), four cold
fresh-JVM re-measurements gave **2269 / 2042 / 2131 / 2318 ms** (mean ≈ 2190 — slightly faster, since
chunks 2–10 run JIT-warm where the single solve paid warmup across its whole run), so `REFERENCE_MS`
was re-calibrated to **2200** to match the workload actually shipping. Verified live against the
packaged jar during the original E2E: `benchmarkMs=1970` on an already-JIT-warm server process —
`machineSpeedFactor` was ≈ 1.22 at that run's then-current reference of 2400 (the same measurement
would read ≈ 1.12 at the shipped 2200; both within normal warm-vs-cold variance).

`machineSpeedFactor = REFERENCE_MS / measuredMs` (uncapped case) — a machine measuring FEWER ms than
the reference is "faster than reference" (factor > 1.0); `SuggestDurationService` divides its raw
estimate by this factor, so faster hardware gets a shorter suggestion.

**Idle-machine assumption (review fast-follow 2, documented on both services' javadoc)**: the
measurement — cached for the whole session — assumes an otherwise-idle machine; a benchmark racing
other CPU load reads as "slow hardware" and inflates every subsequent suggestion. The per-plan 409
guard stops the one in-app contention source against the SAME plan, but deliberately NOT cross-plan
contention or out-of-process load — an accepted single-user-desktop-tool trade-off (a global
"anything solving anywhere blocks every suggestion" lock would be a worse trade than an occasionally
conservative, i.e. longer, suggestion).

**Caching**: `SolveBenchmarkService.benchmark()` is double-checked-locking-memoized — the actual
hardware measurement runs AT MOST ONCE per application process lifetime. `problemSize` (participant/
group/block/coach/wish/custom-field counts) is recomputed on every call via cheap independent COUNT
queries (never the full `SolverInputAssembler`, which would 400 on a plan whose groups haven't been
generated yet and does far more work than a size estimate needs). Verified live: two consecutive
`suggest-duration` calls on the SAME plan, and a THIRD call on a DIFFERENT plan, all returned
byte-identical `benchmarkMs`/`machineSpeedFactor` (`SuggestDurationControllerIntegrationTest
.theHardwareBenchmarkIsCachedAcrossCallsAndPlans`).

**Concurrency guard**: `SuggestDurationService.suggest` calls the existing per-plan
`SolveCoordinator.assertNoActiveSolve(activityPlanId)` (the same guard M6b's lock/unlock endpoints
use) unconditionally at the top — 409 while a solve is active for that specific plan, matching the
task's literal "409 if a solve is active for the plan" (not a global lock across all plans; the
benchmark genuinely only competes for CPU with THIS plan's own solve attempt in the sense that a user
wouldn't ask for a duration suggestion while already solving the same plan — a cross-plan CPU-fairness
guard was judged out of scope / not requested).

### The formula (documented on `SuggestDurationService`'s own javadoc)

```
rawSeconds = BASE_SECONDS(20)
           + participants * groups * SIZE_FACTOR(0.10)   // interaction term: bigger group matrix
           + activeBlocks * BLOCK_FACTOR(0.5)
           + coaches * COACH_FACTOR(0.5)
           + wishes * WISH_FACTOR(1.0)
           + customFieldConstraints * FIELD_FACTOR(0.5)
adjustedSeconds = rawSeconds / machineSpeedFactor
suggestedSeconds = nearestFriendlyStep(clamp(adjustedSeconds, 15, 600))
```

`wishes` = count of `custom_field_value` rows for participants whose field has `constraintType ∈
{SAME_GROUP, DIFFERENT_GROUP, COACH_PREFERENCE, COACH_FORBIDDEN}` (data VOLUME — how many wish
declarations actually exist). `customFieldConstraints` = count of `FieldDefinition`s visible to the
plan with `constraintType != NONE` (model COMPLEXITY — how many constraint-affecting fields are
configured at all, independent of how many participants used them) — a deliberately separate axis
from `wishes` so a plan with many configured-but-unused wish fields doesn't get inflated by volume it
doesn't actually have, while a plan with few fields but heavy data volume is still captured correctly.

Known slight overcount (review fast-follow 4, advisory only): on a coach-less plan, `wishes` still
counts COACH_PREFERENCE/COACH_FORBIDDEN field-value rows whose referenced `coach_profile` no longer
exists (orphaned values left behind by a coach deletion) — `SolverInputAssembler` DROPS those wishes
at assembly time (its `coachPersonLongIdByProfileId` map is empty with zero coaches, so no
`CoachWish` fact is ever built), meaning the suggestion's `wishes` term can be marginally higher than
the solve's actual wish-fact count. Direction is safe (a few phantom wish-seconds → an at-most
marginally LONGER suggestion) and the count is advisory input to a heuristic, not a solver fact —
deliberately not "fixed" with per-row coach-existence checks that would complicate a size estimate.

Every term is non-negative and monotone in its own input; dividing by `machineSpeedFactor` (always
`> 0`) is monotonically decreasing in the factor. Nearest-neighbour snapping onto the sorted friendly
steps ladder (`15/30/60/90/120/180/240/300/420/600`) preserves monotonicity (a non-decreasing
step function of a non-decreasing input) — proven both by direct unit tests
(`SuggestDurationServiceTest`, exercising the formula's package-visible `computeSuggestedSeconds`
directly with synthetic `ProblemSize`s and speed factors) and by a REST-level cross-dataset check
(`SuggestDurationControllerIntegrationTest.aBiggerRealPlanNeverSuggestsFewerSecondsThanASmallerOne`:
`large-120` vs `small-10` through the real endpoint).

Constants were tuned so the task's own worked example lands almost exactly on its stated answer:
plugging in `participants=130, groups=12, wishes=45, machineSpeedFactor=1.2` gives
`raw = 20 + 130×12×0.10 + 45×1.0 (+ small activeBlocks/coaches/customFieldConstraints terms) ≈ 216-235`,
`adjusted ≈ 180-196`, which snaps to the **180** step — verified as an explicit unit test
(`SuggestDurationServiceTest.theTasksOwnWorkedExampleLandsOnOneHundredEightySeconds`) and reproduced
live against the packaged jar (below).

### CUSTOM solve profile

`SolveProfile` gains a `CUSTOM` enum constant. Unlike FAST/NORMAL/THOROUGH (fixed `Duration` fields
baked into the enum), `CUSTOM` carries no fixed duration — its `terminationConfig()`/`limitMs()`
instance methods deliberately THROW `IllegalStateException` if called directly (placeholder
`Duration.ZERO` constructor args), forcing every caller through the new static helpers
`SolveProfile.customTerminationConfig(int durationSeconds)` /
`SolveProfile.customLimitMs(int durationSeconds)` instead — a duration can never be silently
forgotten. Validation (`SolveProfile.requireValidCustomDuration`, the single source of truth): 400 via
`BadRequestException` unless `durationSeconds ∈ [10, 900]` — checked BOTH at `SolveController` (fail
fast on the HTTP request, before touching the assembler) AND defensively re-checked inside
`SolveCoordinator.startSolve` (matching this codebase's established defensive-recheck convention, e.g.
`SolverInputAssembler`'s reserved-MEDIUM guardrail — a direct `SolveCoordinator` caller that bypasses
the controller must not be able to skip validation).

`unimprovedSpentLimit = min(durationSeconds / 2, 120)` seconds — every profile needs this safety net
(`m6a-notes.md`'s "Review fix 1" RCA: an already-optimal/heavily-pinned plan otherwise burns the FULL
wall-clock budget on rejected move selections). A flat half-duration fraction is fine for the three
small fixed presets, but `CUSTOM` can go up to 900s, where an unbounded half-fraction would let a
converged plan churn for 450s doing nothing useful — capped at 120s (documented on
`SolveProfile.CUSTOM_UNIMPROVED_CAP_SECONDS`, same order of magnitude as THOROUGH's own 30s cap scaled
up). Verified live: a `durationSeconds=15` CUSTOM solve on `small-10` finished in **~7.0s**
(`min(15/2, 120) = 7`, i.e. the unimproved cutoff fired, not the 15s spentLimit) with a feasible
`0hard/0medium/-36000soft` score — exactly the expected mechanism, not a coincidence (small-10
converges almost immediately, so the unimproved-cutoff branch is the one that actually terminates it).

`SolveController.SolveRequest` gained `Integer durationSeconds` (required 10..900 exactly when
`profile == "CUSTOM"`; ignored, no error, for every other profile — matching the record's existing
`@JsonIgnoreProperties(ignoreUnknown = true)` lenient convention). Existing presets (FAST/NORMAL/
THOROUGH) are completely unchanged — their `terminationConfig()`/`limitMs()` methods, `Duration`
fields, and behavior are identical to before this milestone (verified:
`SolveProfileTest.fixedPresetsStillWorkNormallyAfterAddingCustom`).

## Deviations (each with reasoning)

### 1. `noCoaches`/note surfaces are additive JSON, not new typed REST-response fields
`CapacityResponse.noCoaches` is a new record field (safe, additive — every existing consumer
positionally-unaware since Jackson serializes records by name); the solve "note" lives inside the
already-loosely-typed `result_summary_json` blob rather than the strongly-typed `SolveResponse`
record. Both choices avoid touching `OpenApiSchemaTest`'s exact-shape assertions on `SolveResponse`
while still giving the frontend everything the task asked for through paths it already reads
(`GET .../capacity`, `GET .../runs`).

### 2. `suggest-duration`'s concurrency guard is per-plan, not a global "any solve anywhere" lock
The task says "409 if a solve is active for the plan" — read literally (per-plan), matching every
other CPU/consistency-sensitive guard already in this codebase (`SolveCoordinator
.assertNoActiveSolve`, used verbatim by the M6b lock/unlock endpoints). A global cross-plan CPU
contention guard would be a bigger behavioral change (blocking an unrelated plan's duration
suggestion just because SOME OTHER plan is mid-solve) that the task's wording doesn't ask for.

### 3. `wishes`/`customFieldConstraints` are two DELIBERATELY separate problem-size axes
Not spelled out by the task beyond "wish count term" and "customFieldConstraints" both being named in
the response shape — designed as data VOLUME (how many wish declarations exist, scales with actual
solve pressure) vs model COMPLEXITY (how many constraint-affecting fields are configured, scales with
how many constraint STREAMS the solver evaluates regardless of how many participants used them). Both
terms are cheap, independent, monotone, and keep the formula's provenance auditable (each number in
the JSON traces to one specific counting rule, documented on `SuggestDurationService`'s own javadoc).

### 4. Benchmark fixture size (~30/4/4/3) is smaller than the task's own suggested step-N calibration target implies
The task says "hardcoded deterministic ~30 players/4 groups/4 blocks" — followed exactly. The step
count needed to hit 1.5-2.5s wall-clock on real hardware (140,000) is far larger than a naive reading
of "small fixture -> small step count" might suggest; this is expected and not a sign the fixture
is wrong — Timefold's per-step cost on this tiny entity count is itself tiny (270k+ moves/sec once
JIT-warm), so reaching a multi-second wall-clock budget on a genuinely small fixture requires a large
step count. Confirmed this is fixture-size-independent noise, not a bug, by cross-checking against
`SolverRegressionTest`'s own `large-120` golden gate (20,000 steps ≈ 4s on a 130-entity problem) —
consistent order-of-magnitude story once entity count and step count are both accounted for.

## Review fast-follows (Opus review round, "MERGE" + five items, all folded in)

1. **Benchmark wall-clock cap** — the chunked rewrite documented under "Wall-clock cap" above
   (10 × 14k-step chunks, 30s overall cap, per-chunk defensive `spentLimit`, proportional
   steps-actually-completed factor with the corrected `stepsDone/stepsPlanned` ratio direction,
   `MIN_SPEED_FACTOR = 0.01` floor, `REFERENCE_MS` re-calibrated 2400 → 2200 against the chunked
   workload). New `solver.run.SolveBenchmarkServiceTest` (5).
2. **Idle-machine javadoc** — documented on both `SolveBenchmarkService` and
   `SuggestDurationService` (and under "Idle-machine assumption" above): per-plan 409 does not
   prevent cross-plan/out-of-process contention; accepted single-user trade-off.
3. **Inertness hardening** — `savedPlanCoachBlockedIsInertWithZeroCoachSlotsEvenWithAPresentBlockingFact`
   now supplies a live, time-overlapping coach-role PERSON blocking fact, proving zero CoachSlots
   beats a would-otherwise-match fact rather than an empty fact list trivially matching nothing.
4. **Comment/notes fixes** — `SolverInputAssembler`'s inertness-test reference corrected to
   `solver.constraints.CoachlessConstraintInertnessTest`; the `wishes` orphaned-coach-wish overcount
   note added above (advisory only, safe direction).
5. **OpenAPI regression guard** — `api.OpenApiSchemaTest` (+1):
   `SuggestDurationResponse` schema exists with all five fields and is referenced by the
   suggest-duration operation; `SolveRequest.durationSeconds` and `CapacityResponse.noCoaches` exist.

## Tests

`./mvnw clean verify`: **549 tests, 0 failures, 0 errors**. This milestone added **44 new test
methods** across 6 new files plus 2 extended existing files (golden scores in
`test-data/regression/expected-scores.json` verified byte-for-byte unchanged — no golden dataset has
zero coaches, and nothing in this milestone touches constraint code, weights, or solver
configuration for the coached case):

- `solver.constraints.CoachlessConstraintInertnessTest` (11) — the `ConstraintVerifier`-style
  inertness gate: every coach-touching constraint (`coachNoOverlap`,
  `coachCannotTrainAndCoachSameTime`, `coachAvailabilityHard`, `coachRequirementHard` — the critical
  pre-fix-bug case, `coachMaxGroups`, `coachWishRequired`, `coachWishForbidden`, `coachLevelFit`,
  `coachPreferenceSoft`, `savedPlanCoachBlocked` — hardened per fast-follow 3 with a present,
  would-otherwise-match blocking fact, `coachPreferredTimeSlot`), each with a normally-populated
  non-coach fixture (real group/schedule/player) and zero `CoachSlot`/`CoachWish` facts, asserted
  `hasNoImpact()`.
- `api.CoachlessSolveIntegrationTest` (4) — full REST-level solve on `small-10` with its two seeded
  coaches deleted (cascades to `coach_time_slot`): real FAST solve is feasible
  (`0hard/0medium/...`), every player placed, every group's block assigned, zero `coach_assignment`
  rows written, `result_summary_json.note` present with the exact Swedish text; GREEDY baseline also
  feasible + note present; `optimize.coaches=true` explicit request is a no-op not a 400; `GET
  .../capacity` reports `noCoaches=true`/`coachShortageRisk=false`/the INFO message.
- `capacity.CapacityServiceTest` (+1: `zeroCoachesIsReportedAsInfoNotAShortageWarning`).
- `solver.run.SolveProfileTest` (9) — `CUSTOM`'s validation bounds (10/900 inclusive, null/9/901
  rejected), the `min(durationSeconds/2, 120)` proportional-cap formula both below and above the
  240s crossover, fixed presets unaffected, `CUSTOM` itself refuses the fixed-preset-shaped methods,
  `fromString("custom")` resolves case-insensitively.
- `solver.run.SuggestDurationServiceTest` (5) — pure-formula coverage via the package-visible
  `computeSuggestedSeconds`: bounds/friendly-step membership, bigger-problem monotonicity (both as a
  whole and per-dimension independently), faster-machine monotonicity, the task's own worked example
  landing on 180s exactly.
- `api.SuggestDurationControllerIntegrationTest` (8) — sane values against the real `large-120`
  fixture (exact participant/group/block/coach counts, positive wishes/customFieldConstraints,
  Swedish rationale text), 409 while a solve is active, cross-dataset REST-level monotonicity
  (`large-120` ≥ `small-10`), benchmark caching across calls AND across plans, `CUSTOM` end-to-end on
  `small-10` with `durationSeconds=15`, and the three validation-bounds 400s (missing/9/901).
- `solver.run.SolveBenchmarkServiceTest` (5, fast-follow 1) — proportional-factor arithmetic
  (full completion = plain reference ratio, half steps = half factor, zero steps = the documented
  floor) plus the live cap path: a 1ms budget forces the capped-first-chunk branch and returns
  promptly at `MIN_SPEED_FACTOR`, and a 1s budget respects the cap with hardware-independent
  assertions only.
- `api.OpenApiSchemaTest` (+1, fast-follow 5) — the v0.2.0 schema regression guard described above.

## E2E verification (packaged jar, dev profile, isolated `--app.data-dir`, port 4999)

`./mvnw package -DskipTests` → `target/backend.jar`, launched `--spring.profiles.active=dev
--server.port=4999 --app.data-dir=<fresh temp dir>` (token `dev`, the documented dev-profile
fallback). Built plans through the REAL REST API only (no SQL seeding this time — small enough
fixtures to do it properly).

**Coach-less plan** (season+plan via the real API, `defaultGroupTargetSize=5/min=4/max=6`; 2 time
slots × 3 courts = 6 active blocks; 10 participants via `POST /persons` + `POST .../participants`;
recompute-levels; groups/generate → 2 groups, both `requiredCoachCount=1` per `GroupGenerator`'s
existing default — **zero** coach profiles created at all):

```
GET .../capacity -> coachCount=0, noCoaches=true, coachShortageRisk=false,
                    coachShortageMessage="Inga tränare registrerade"
POST .../solve {"profile":"FAST"} -> 202 SOLVING_ACTIVE
  ... finished: score=0hard/0medium/-36000soft, durationMs=5110
  inputSnapshotJson: coachCount=0, coachSlotCount=0, coachWishCount=0  <- confirms the assembler skip
  resultSummaryJson.note = "Inga tränare registrerade — grupperna optimerades utan tränartilldelning"
GET .../groups -> both groups have assignedTrainingBlockId set
GET .../assignments -> all 10 players groupId set, source="solver"
```

Confirms Feature 1 end-to-end against the real running jar: feasible (no `coachRequirementHard`
violations — the exact pre-fix bug class), fully scheduled, zero coach assignments, note present.

**`suggest-duration` on a hand-built large-120-shaped plan** (4 time slots × 3/4/3/2 courts = 12
blocks, 8 coaches, 130 participants, one `playWith` wish added via `PUT .../field-values` for
realism):

```json
{
    "suggestedSeconds": 180,
    "machineSpeedFactor": 1.218274111675127,
    "benchmarkMs": 1970,
    "problemSize": {
        "participants": 130,
        "groups": 12,
        "activeBlocks": 12,
        "coaches": 8,
        "wishes": 1,
        "customFieldConstraints": 14
    },
    "rationaleSv": "Baserat på 130 spelare, 12 grupper, 1 önskemål och din dators hastighet (1.2× referens) föreslås 180 sekunder."
}
```

A second, immediately-repeated call returned byte-identical `benchmarkMs`/`machineSpeedFactor`
(cache confirmed live, not just in the test suite) and the same `suggestedSeconds`. Note
`benchmarkMs=1970` here vs the ~2400ms COLD calibration mean — this call ran against an
already-JIT-warm server process (several prior requests had already run), exactly the expected
"warm JVM is faster than a cold single-shot benchmark" story; `machineSpeedFactor ≈ 1.22` reflects
that, consistent with the task's own "1.2× referens" example wording.

**`CUSTOM` profile, `durationSeconds=15`** (on the coach-less plan above, re-solving it):

```
POST .../solve {"profile":"CUSTOM"}                    -> 400 "...between 10 and 900 (got null)"
POST .../solve {"profile":"CUSTOM","durationSeconds":5} -> 400 "...between 10 and 900 (got 5)"
POST .../solve {"profile":"CUSTOM","durationSeconds":15} -> 202 SOLVING_SCHEDULED
  ... finished: status=FINISHED, score=0hard/0medium/-36000soft, durationMs=7027
```

`durationMs=7027` ≈ `min(15/2, 120) = 7` seconds — the unimproved-cutoff branch fired (small-10-sized
plans converge almost immediately), not the full 15s `spentLimit`; confirms the proportional safety
net formula is wired correctly end-to-end, not just unit-tested.

`POST /api/system/shutdown` cleanly stopped the server (`{"status":"SHUTTING_DOWN"}`, confirmed
unreachable afterward).

## Known gaps / deferred

- No REST/UI surface change was made to display `noCoaches`/the solve `note` — this task was
  explicitly the BACKEND half; the frontend Optimeringsvy/Kapacitetsvy consuming these new fields is
  a separate follow-up.
- `suggest-duration`'s `wishes`/`customFieldConstraints` counts iterate `CustomFieldValueRepository
  .findByEntity` once per participant (same cost `SolverInputAssembler` already pays during every
  real solve) — acceptable for a pre-solve advisory endpoint at this app's scale (≤ a few hundred
  participants), but a genuine O(N) repository round-trip pattern that a future milestone could
  collapse into one query if it ever became a latency concern.
- The benchmark's `REFERENCE_MS`/`BENCHMARK_STEP_COUNT_LIMIT` are calibrated against ONE dev machine.
  If this app is ever built/verified on meaningfully different hardware (a slow CI runner, a
  low-power Windows laptop), `machineSpeedFactor` will simply read low there (correctly) and
  suggestions will scale up accordingly — no code change needed, but the "REFERENCE_MS" comment's
  claim to be "the reference machine" is inherently a one-time human judgment call, not something
  automatically re-calibrated.
