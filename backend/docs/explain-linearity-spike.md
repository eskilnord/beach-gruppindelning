# M-E0 spike: is per-constraint score linear in constraint weights? (v0.6.0)

Gate for an upcoming explainability feature that assumes: for a single-player move probe, per-
constraint score delta is EXACTLY linear in constraint weights (`match score = weight × matchWeight`,
integers), so `units_k := Δscore_k / weight_k` is exact and `Δscore(w') = Σ units_k · w'_k` for any
weight vector `w'`. Four core properties were proven or disproven mechanically, with tests, against
Timefold Solver Community 1.33.0. This is a decision record, not a tutorial — see
`backend/src/test/java/se/klubb/groupplanner/explain/WeightSensitivityLinearityTest.java` for the
executable proofs (forked from `solver/WeightOverrideFlipTest`, which is untouched).

A follow-up two-reviewer pass (one decompiled the Timefold 1.33.0 jar to verify independently)
confirmed verdicts (a), (c), (d) and the signed-weight finding below as correct, but found the
original (b) test had an oracle hole and coverage gaps. Those are closed by three additional
committed tests plus a rewrite of (b)'s own prediction logic — see "(b), continued" below.

## (a) Are overrides honored per `analyze()` call, or is some kind of rebuild required?

**Honored every call. No rebuild of any kind is required, needed, or even possible.**
`ConstraintWeightOverrides` is a plain field read fresh off the `GroupPlanSolution` on every
`SolutionManager.analyze(...)` call — the same "pure read of the current object graph, recomputed
from scratch every time" behavior `MoveProbe`'s own javadoc already documents for entity mutations
(e.g. `setGroup`). Proven by calling `analyze()` three times on the exact same `SolutionManager` +
`GroupPlanSolution` instance, with nothing between calls except `solution
.setConstraintWeightOverrides(...)`: no new `GroupPlanSolution`, no new `SolverFactory`/
`SolutionManager`, no reconfigure/rebuild API (none exists to call). The third call reverts to the
first call's weight and gets the first call's result back, ruling out a one-way "warm-up" artifact.

Test: `overridesHonoredPerAnalyzeCall_onSameSolutionInstance_noFactRebuildRequired`.

## (b) Is the move's score delta exactly linear in constraint weights?

**Yes, exactly, bit-for-bit — for every constraint, at any weight vector, at any score level
(including one that reclassifies a constraint's level), for both `.penalize(...)` and
`.reward(...)`.** This holds by construction of Timefold's constraint-streams scoring model:
`.penalize(weight, matchWeightFn)` / `.reward(weight, matchWeightFn)` always compute a match's score
contribution as `weight × matchWeightFn(...)`, and `matchWeightFn` has no access to `weight` (or to
which score level `weight` is declared at) — so for a FIXED pair of solution states
(baseline/moved), `Δscore_k` is an exactly-linear function of `weight_k` with no other free variable,
regardless of level, and the "proportional units" model the explainability feature needs is sound.

Verified on a hand-designed move (fork of `WeightOverrideFlipTest`'s two-group 18.00/19.30 fixture,
extended with a level imbalance and a time preference so one player move touches five constraints at
once: `groupSizeTarget`, `sameGroupSoft`, `levelBalance`, `timePreferenceSoft`, `groupOrderByLevel`).
`units_k` were derived from the move's diff under default weights (asserting the division has zero
remainder and the delta never bleeds outside the constraint's declared hard/medium/soft level), then
used to *predict* `Δscore` for seven weight vectors (all-`1`, all-`10000`, three mixed vectors, one
that reclassifies `sameGroupSoft` SOFT→HARD, and one that zeroes `timePreferenceSoft`) — both the
aggregate `Δscore` and, per constraint, `analysisOf(diff, key).score()` individually — and the
prediction matched an actual re-analysis of baseline/moved under each vector exactly, every time.

**Load-bearing gotcha discovered along the way** (see (d) below): the weight used to derive/apply
`units_k` must be read from a RAW, non-diffed `ScoreAnalysis` — never from `diff.constraintAnalyses()
.weight()`, which is always zero when both diffed sides share a weight vector (the common case).

Test: `moveDeltaIsExactlyLinearInConstraintWeights_acrossSevenWeightVectors`.

### (b), continued: closing an oracle hole and three coverage gaps

A two-reviewer pass (one independently verifying against the decompiled Timefold 1.33.0 jar) found
one blocker and several gaps in the original (b) test, all now fixed and covered by committed tests:

- **Circular oracle (blocker).** The original test predicted each vector's applied weight by reading
  it back off the very `ScoreAnalysis` (`baseV`) it was about to validate: if Timefold had silently
  ignored an override, the "prediction" and the "actual" would both have been derived from that same
  (buggy) call, and the test would still have passed. The prediction is now built ENTIRELY from each
  vector's own input (the override's positive magnitude, the fixed penalize/reward sign rule, and the
  level the vector's own weight is declared at) — with zero dependency on any Timefold analysis call —
  and a SEPARATE assertion then checks the raw applied weight against that independent prediction, so
  the "did Timefold honor this override" question is actually being asked, not assumed.
- **Level-pinning under reclassification.** The original prediction froze each constraint's score
  level at derivation time. This app allows per-plan HARD↔SOFT reclassification
  (`ConstraintWeightService.validateReclassification`; `SolverInputAssembler` reads the level from the
  DB row at solve time; `conflictsAsWarnings` already reclassifies three HARD constraints to
  `ofSoft(1)` in production) — under reclassification, a level-pinned dot product would silently sum a
  constraint's contribution into the wrong score component, exactly the user-facing lie this spike
  exists to rule out. `units_k` is now modeled as a level-free scalar; the level used to place its
  contribution is read fresh from each TARGET vector's own weight, never from the derivation. A sixth
  weight vector that reclassifies `sameGroupSoft` SOFT→HARD proves the fix.
- **Coverage: MEDIUM and HARD levels, and the REWARD sign.** The original test's one hand-designed
  move never unassigned a player and never overflowed a group, so `unassignedPlayer` (MEDIUM) and
  `groupMaxSizeHard` (HARD) were never exercised by the linearity assertion — only inferred by
  analogy. Two new probes (move a player to the waitlist; overfill a group past `maxSize`) now put a
  real MEDIUM and a real HARD delta through the same derive/predict/verify machinery. Separately, the
  REWARD sign-convention claim below previously rested on a deleted scratch test with no committed
  assertion — a new test asserts `coachPreferenceSoft`'s applied weight directly (using the fact that
  a nonzero-weight constraint's entry is present in `ScoreAnalysis` even with zero matches — see (c)).
- **Zero-weight vector.** A seventh weight vector zeroes one touched constraint's weight. Per (c), its
  entry must be entirely absent, not present-with-zero-score — the prediction logic now special-cases
  a zero target weight explicitly (asserting the entry's absence) rather than asking for that
  constraint's weight and hitting a bare "not found" assertion failure.

Tests: `unassignedPlayerMediumDeltaIsAlsoExactlyLinear_moveToWaitlist`,
`groupMaxSizeHardDeltaIsAlsoExactlyLinear_overfilledGroup`,
`coachPreferenceSoftRewardWeightStaysPositiveNotNegated_zeroMatchEntry`.

## (c) Is weight 0 reachable, and does a disabled constraint still show matches?

**Weight 0 is reachable — it's literally how "disabled" reaches the solver — and a zero-weight
constraint's entry is OMITTED ENTIRELY from `ScoreAnalysis`, not zeroed-but-present.**

`WeightLimits.MIN_WEIGHT = 1` only bounds the DB-backed `ConstraintWeightService`/
`FieldDefinitionValidator` write paths (an *enabled* constraint's user-settable weight).
`ConstraintWeightOverrides.of(...)` itself does not reject `Score.zero()`. And
`SolverInputAssembler.buildConstraintWeightOverrides` (~line 623/647) proves DISABLED constraints
reach the solver via exactly this mechanism — `def.enabled() ? def.defaultWeight() : 0` — an explicit
zero-weight map entry, never an omitted key and never a constraint dropped from
`GroupPlanConstraintProvider#defineConstraints`'s array. "Disabled" and "weight zero" are the same
mechanism in this codebase.

Empirically: the identical split-state fixture (a broken `WANT_SAME` wish) analyzed under a zero
`sameGroupSoft` weight has no `sameGroupSoft` key in `constraintMap()` at all (32 entries, pinned
explicitly in the test — instead of the normal 33, also pinned explicitly, so a constraint added to or
removed from `GroupPlanConstraintProvider#defineConstraints` makes this test fail loudly rather than
silently drift); analyzed under any nonzero weight it has one entry with `matchCount=1`. So Timefold
doesn't just zero a disabled constraint's score — it drops the constraint from the report.

Test: `zeroWeightConstraintIsOmittedFromScoreAnalysis_entirely_notJustZeroed`.

## (d) Does `ScoreAnalysis.diff()` preserve `ConstraintAnalysis.weight()`?

**No — `diff()` SUBTRACTS the weight field, the same way it subtracts score**
(`a.diff(b).weight() == a.weight().subtract(b.weight())`). Same-weight operands collapse to weight
zero (which is ambiguous by itself — could mean "subtracts" or "always reports zero"); the conclusive
experiment diffs the RECEIVER — `movedHighWeight`, the analysis of the POST-MOVE state under weight
777 — against the ARGUMENT — `baseLowWeight`, a SEPARATE re-analysis of the ORIGINAL, PRE-MOVE
baseline state under weight 1 — and gets `-777 − (−1) = −776` — neither operand's own weight, and the
reverse diff gives `+776`, confirming a genuine non-commutative subtraction rather than "preserves
either side" or some other combination. (Both operands being different *weight vectors* is the point
of the experiment; that they also happen to be different *solution states* doesn't confound it —
`ConstraintAnalysis.weight()` is a per-constraint constant of the weight vector an analysis was
computed under, not something that depends on how many times the constraint matched in that state.)

**Consequence**: `diff.constraintAnalyses()…weight()` is useless for reading "the applied weight" in
the common case (`MoveProbe` always diffs two analyses of the *same* weight vector — that diff's
weight is always zero). Anything that needs the applied weight — including this spike's own `units_k`
derivation — must read it off a RAW, non-diffed `ScoreAnalysis` operand instead.

Test: `diffSubtractsConstraintWeightJustLikeScore_soDiffWeightIsUselessAcrossSameWeightOperands`.

## A cross-cutting finding that shaped every test in this file

`ConstraintAnalysis.weight()` reports the SIGNED per-match multiplier Timefold actually uses when
scoring, not the positive magnitude passed to `ConstraintWeightOverrides.of(...)`. Overriding a
`.penalize(...)` constraint (e.g. `sameGroupSoft`) to `ofSoft(777)` makes `weight()` report
`-777soft`; a `.reward(...)` constraint (e.g. `coachPreferenceSoft`) reports the override's sign
unchanged (positive) — now backed by a committed assertion, not just inference (see (b), continued,
above). This was not assumed going in — it fell out of the debug dumps used to derive the tests above
— and every `weight_k`/`units_k` in the test file is deliberately kept in whatever sign Timefold
reports rather than re-normalized to the override's positive input convention.

**Not covered**: every constraint in `GroupPlanConstraintProvider` is either `.penalize(...)` or
`.reward(...)`. Timefold also has a third, MIXED-sign case — `.impact(...)`, where a single constraint
can both penalize and reward depending on the match — which is unused in this codebase and therefore
mechanically unverified here. A future `.impact(...)` constraint must NOT silently inherit this
spike's "sign is a static per-constraint fact" verdict; it would need its own probe.

## Design consequence

Per-constraint units derived from a single move probe (`units_k = Δscore_k / weight_k`, weight read
from a raw analysis, never a diff, and treated as level-free rather than pinned to the level observed
at derivation time) are exact and reusable: `Δscore(w') = Σ units_k · w'_k` holds bit-for-bit for any
weight Score vector, including one that reclassifies a constraint's level, so a "what would this
constraint's contribution be at weight X" UI feature can be built as a pure dot product with no
re-solve — placing each constraint's contribution into whichever score component (hard/medium/soft)
that constraint's weight has UNDER THE VECTOR BEING EVALUATED, not the component observed when the
units were derived. This has been verified for both `.penalize(...)` and `.reward(...)` constraints
and at all three score levels; `.impact(...)` (MIXED-sign) constraints are unused in this codebase and
remain unverified — a future one must not inherit this verdict silently. Zero/disabled constraints
must be handled as a distinct `unitsKnown=false` case with NO data (no matches, no units — the entry
doesn't exist in `ScoreAnalysis` at all), not as "units known but zero". And any code that needs a
constraint's applied weight — including the units derivation itself — must read it from a raw
(non-diffed) `ScoreAnalysis`, never from a diff's `ConstraintAnalysis.weight()`, which is subtracted
just like score and is zero whenever both diffed sides share a weight vector.
