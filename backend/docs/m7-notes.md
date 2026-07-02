# M7 implementation notes (explainability + what-if)

Design decisions and deviations made while implementing M7 (explainability engine, what-if,
staleness). Primary contract: `docs/design/04-solver.md` §11/§12/§14.2 as amended by
`docs/design/05-solver-verification.md`'s waitlist/union-rule fixes; `../../utkast-kravspec.txt`
§17/§18 + the "Förklarbarhet" chapter + §23.7/§23.8; this milestone's own brief (design gate M-S3).
`backend/docs/m6a-notes.md`/`m6b-notes.md` established the conventions (`SolverIdIndex`,
`ConstraintKeys`, justification records) this milestone builds on directly.

## Scope actually implemented

- **`V7__explainability.sql`**: `activity_plan.plan_revision INTEGER NOT NULL DEFAULT 0`,
  `optimization_run.plan_revision INTEGER NOT NULL DEFAULT 0`, and `explanation_record` (spec §7.18,
  design §1) with an added `created_at` column (not in the design doc's literal shape — a minor,
  justified addition so the write-through audit log is chronologically inspectable).
- **Run analysis persistence** (`OptimizationRunService.finishRun`): now takes the run's `FETCH_ALL`
  `ScoreAnalysis` (computed by `SolveCoordinator` for both a real solve and greedy) and folds a
  `constraintSummaries` array (`key, label, level, weightApplied, scoreTotal, matchCount`) into
  `result_summary_json`, alongside the M6a `hard/medium/soft/feasible/unassignedCount` fields.
- **`explain/ConstraintMetadata`**: static Swedish registry for all 34 constraint keys (32
  code-implemented + 2 satisfied-by-construction + the 1 fan-out-parent-with-no-code-of-its-own,
  `lateTimeForLowerGroups`) — label, description, level, unit (`PER_MATCH`/`PER_POINT`), direction
  (`PENALIZE`/`REWARD`).
- **`explain/Justifications` visibility**: `se.klubb.groupplanner.solver.constraints.Justifications`
  and its 18 nested records were widened from package-private to `public` — a minimal, load-bearing
  change M7 genuinely needs (the explain layer pattern-matches on the concrete justification types
  returned by `MatchAnalysis.justification()`), not a design deviation in the "changed behavior"
  sense.
- **`explain/JustificationMessages`**: the single place a `ConstraintJustification` becomes Swedish
  text, covering all 18 record types via `switch` pattern matching — includes a
  `toSwedishAsFixed` variant used only for "newlyFixed" wish matches (see Deviation 3 below).
- **`explain/SolutionIndex`**: solver-long-id → display-name/group-name/time-slot-label lookups built
  once per request from the current `GroupPlanSolution`.
- **`explain/MoveProbe`**: the shared "what-if this player moved to this group" evaluator backing
  BOTH the person-level `alternatives[]` list and `WhatIfService`'s move/why-not endpoints (see
  Deviation 1 for its mutate-and-restore performance strategy).
- **`explain/ExplanationCache`**: bounded (500-entry) LRU keyed `(runId, planRevision,
  participantProfileId)` — see Deviation 2 for the invalidation-by-key-shape design.
- **`explain/ExplanationService`**: person/group/plan levels, the ONE-PASS candidate-group probe +
  union-rule labelling (design §11.3, verifier-corrected), the waitlist branch (amendments a/c), the
  waitlisted-friend edge.
- **`explain/WhatIfService`**: `move`/`whyNot`, both thin wrappers over `MoveProbe` +
  `ExplanationService`'s shared `RunContext`/`toAlternativeView`.
- **`domain.ExplanationRecord` + `repo.ExplanationRecordRepository`**: write-through audit persistence
  (see Deviation 2).
- **REST**: `api.ExplanationController` (person/group/plan GETs), `api.WhatIfController`
  (move/why-not POSTs), `api.AssignmentController#move` (the mutating manual-move endpoint).
- **Revision-bump hooks** — the COMPLETE invalidation surface as of the M7 review round (review fix
  M2 closed the gaps the initial implementation left; every placement-relevant mutating endpoint now
  routes through `ActivityPlanRepository#bumpRevision`, resolving the owning plan first for
  entity-scoped paths like `/api/coaches/{id}`):

  | Call site | Trigger |
  |---|---|
  | `SolveCoordinator#persistResult` | every solve/greedy writeback (see Deviation 6) |
  | `AssignmentController#move` | manual move (skipped for the m5 same-group no-op) |
  | `AssignmentLockController` PUT/DELETE lock | player lock/unlock |
  | `GroupController` lock-block/unlock-block/lock-coach/unlock-coach | group-level locks |
  | `GroupController#generate` | group (re)generation *(M2)* |
  | `ParticipantProfileController#create`/`#update`/`#delete` | participant CRUD *(create/delete: M2)* |
  | `ParticipantProfileController#recomputeLevels` | level recompute *(M2)* |
  | `ParticipantFieldValueController#put` | participant wishes/priority/times |
  | `ImportController#commit` | import commit *(M2)* |
  | `TrainingBlockController#setCourts`/`#updateActive` | block generation + manual (de)activation *(M2)* |
  | `TimeSlotController` POST/PATCH/DELETE | time-slot CRUD *(M2)* |
  | `CoachController` create/PATCH/delete/`putAvailability` | coach CRUD + tri-state availability *(M2)* |
  | `CoachFieldValueController#put` | coach field values *(M2)* |
  | `ConstraintWeightController#update` | per-plan weight overrides (design §11.6's explicit "weight change") |

  Deliberately NOT bumping: pure reads; import-session staging steps before commit (nothing persisted
  to plan data until commit); season/venue/court CRUD (not referenced by any explanation content —
  block-level effects of court-count changes arrive via `setCourts`, which does bump). Known remaining
  gap: `FieldDefinitionController` PATCH of a field's `hardOrSoft`/`weight` also feeds
  `SolverInputAssembler`'s wish-type/weight derivation, but a GLOBAL field row (activityPlanId NULL)
  affects every plan at once and per-plan bumping is ill-defined there — documented in "Known gaps"
  rather than half-fixed.

## Deviations from `docs/design/04-solver.md` (each with reasoning)

### 1. `MoveProbe` mutates-and-restores the same solution instead of rebuilding fresh per probe
Design §12.1/§12.2's literal text describes each what-if as `moved = assembleFromRun(runId);
moved.find(p).setGroup(targetGroup)` — implying a FRESH `SolverInputAssembler.assemble` call per
probe. A person-level explanation needs up to 12 probes (every other group, or all 12 for a
waitlisted player), and `assemble()` re-runs a dozen repository queries (participants, groups,
blocks, coaches, fields, every participant's custom field values) — measured to add real, avoidable
latency at 120-player scale. Since `SolutionManager.analyze` is a pure read of the CURRENT object
graph (it recomputes the whole score fresh every call, never incrementally, and never mutates genuine
planning variables itself), mutating `target.setGroup(candidate)`, analyzing, then restoring the
original group in a `finally` block is behaviorally identical to rebuilding a fresh solution each
time, at a fraction of the cost: one `assemble()` per REQUEST (not per probe), reused across all
probes. Measured: 147 ms cold for the FULL person-level pipeline (assemble + 11 probes + message
formatting) on `large-120` (130 participants, 12 groups) — see `ExplanationLatencyTest`, well inside
the <1 s M-S3 gate with margin to spare.

### 2. `explanation_record` is a write-through AUDIT log, not the read-through serving cache
Design §11.4's own decision is "lazy-computation-with-cache": compute once, cache. The task brief asks
for BOTH an in-memory LRU (item 4: "Cache per (runId, planRevision, participantId) in-memory LRU;
invalidate on revision bump") AND the `explanation_record` table (item 1, matching design §1's
schema). Rather than round-tripping every person-level DTO tree through JSON serialize/deserialize on
every cache miss (adding real complexity and a second source-of-truth-drift risk for zero measured
benefit — the compute itself is already ~10 ms/probe), the two are split: `ExplanationCache`
(in-memory, `LinkedHashMap` access-order + `removeEldestEntry`, bound 500) is the ACTUAL serving path,
and `ExplanationRecordRepository.upsert` writes a fire-and-forget audit copy to `explanation_record`
on every fresh compute (best-effort — a write failure is logged, never surfaces to the caller). The
persisted rows are never read back into the cache in this milestone (no cross-process cache-warm
continuity yet); a future milestone could add that read-back path without touching the write side.

**Invalidation-by-key-shape**: the cache key embeds the plan's revision AT COMPUTE TIME. Since
`ExplanationService.loadContext` always looks up with the plan's CURRENT revision, a revision bump
makes every future lookup use a never-before-seen key — old entries become unreachable without an
explicit sweep. Directly tested in `ExplanationCacheTest`.

### 3. `newlyFixed` rephrases wish-family justifications instead of reusing the "broken" text verbatim
The design's own §12.2 example shows `"newlyFixed":[{"key":"sameGroupSoft","messageSv":"Kompisönskemål
med Lisa uppfylls"}]` — a DIFFERENT phrasing from the broken-direction text ("...brutet"). A
`ConstraintJustification` instance only ever exists for the PENALIZE case (Timefold has no "satisfied"
justification — satisfaction is the ABSENCE of a match), so reusing the same formatter for both
directions would literally print "...brutet" inside a list whose whole point is "this just got fixed"
— misleading. `JustificationMessages.toSwedishAsFixed` special-cases the pair-wish/coach-wish family
(the only justification types whose broken-vs-fixed distinction actually changes the sentence's
truth value; every other type's message is a neutral data statement — a level-spread number, a group
size — equally valid read in either direction) to say "...uppfylls"/"...får tränare X" instead.

### 4. "Grupp X är full: N/N" is a computed narrative, not the raw `GroupOverMaxJustification` text
kravspec §17.2's own worked example uses "Grupp C var redan full: 12/12" as the headline reason a
rejected alternative wasn't chosen, while design §14.2's own `newlyBroken` example uses "Grupp C blir
12, max är 11" (the literal `GroupOverMaxJustification` wording, describing the state AFTER the
hypothetical move). Both are correct, for different fields: `AlternativeGroupView.newlyBroken`
verbatim-preserves the justification's own "blir N, max är N" phrasing (so the raw constraint-match
data is always traceable), while `AlternativeGroupView.narrativeSv` — the human headline — is computed
separately from the group's CURRENT (pre-move) size/max whenever the blocking violation is
`groupMaxSizeHard`, matching the spec's "är full: N/N" phrasing exactly. The canonical-question test
asserts both shapes are present simultaneously.

### 5. Waitlist priority narrative is a straight comparison, not a swap probe
Amendment (a) requires the priority story to come "directly from data... compare p.priority against
min(member.priority) of each full candidate group", with an actual swap-simulation (insert p, remove
the lowest-priority unpinned member, re-analyze) called out explicitly as OPTIONAL ("optionally backed
by a swap probe"). Implemented: the mandatory data comparison
(`ExplanationService#hardBlockerNarrative`, full-group branch), computing the full group's current
minimum member priority and phrasing "Alla platser har prioritet ≥ din" or the inverted "Du har högre
prioritet... men gruppen är full" case. The optional swap-probe simulation (an actual second
`MoveProbe` evaluating a combined insert+evict) was NOT implemented — genuinely optional per the
amendment's own wording, and the mandatory comparison already answers the required question ("why
didn't my priority get me in") without it. A concrete, scoped follow-up if product feedback wants the
exact swap-consequence numbers shown too.

### 6. Explanations/what-ifs are computed against CURRENT persisted state, not a true historical
### per-run snapshot — and WHY that stays correct
`SolverInputAssembler.assemble(planId)` (the ONLY reconstruction path this codebase has, per
`m6a-notes.md`) always reads whatever is CURRENTLY in `player_assignment`/`training_group`/
`coach_assignment` — there is no "replay exactly as of run R" capability, and design §12.1 itself
anticipated this ambiguity ("or current working state + planRevision"). This creates a real
correctness risk the design doesn't spell out: if plan_revision were bumped ONLY by manual
moves/locks/field edits (the task brief's literal list) and NOT by a fresh solve's own writeback, then
querying an OLDER run's explanation for a participant nobody had looked at yet, after a NEWER solve
already overwrote the DB, would silently compute and cache data that's actually the NEW run's
placement — mislabeled as the old run, with `stale=false` (since nothing "invalidating" had
technically happened per the narrower reading). **Fix**: `SolveCoordinator#persistResult` (used by
BOTH a real solve and greedy) also bumps `plan_revision`, and the finishing run's OWN
`optimization_run.plan_revision` is stamped with the value immediately AFTER that bump — i.e. every
run's `basedOnRevision` equals `currentRevision` at the instant it finishes, and it only drifts once
something (including a LATER solve) changes the DB again. This is why the E2E transcript below shows
the old run becoming `stale=true` again after a re-solve, even with no further manual edits — that is
the intended, correctness-preserving behavior, not a bug: "stale" honestly means "the DB has moved on
since this run", which is exactly true. Documented here since it is the single most important
semantic decision in this milestone's staleness design and isn't spelled out anywhere in the design
doc at this level of detail.

### 7. Apply-move endpoint refuses a currently-locked assignment (400), doesn't silently no-op
`PlayerAssignmentRepository.updateGroupAndSource` is already scoped to `locked = 0` (M6a convention),
so a naive implementation would return 200 while silently changing nothing for a locked participant —
a confusing UX for a "flytta ändå" action explicitly triggered by an explanation/what-if flow. Added
an explicit lock check in `AssignmentController#move` that 400s with a clear message ("unlock
before moving manually") instead.

### 8. Canonical-question / waitlist test fixtures hand-place `player_assignment` rows directly,
### rather than running an actual Timefold solve
`se.klubb.groupplanner.explain.ExplanationTestFixture` (test-only) builds season/plan/blocks/groups/
participants via the real repositories (same pattern as `TestDatasetLoader`), then writes the DESIRED
FINAL placement directly and inserts a plain `FINISHED` `optimization_run` row stamped with the plan's
current revision. This is deliberate, not a shortcut around "real" testing: `ExplanationService`/
`WhatIfService` only ever read persisted state (Deviation 6) — a hand-placed fixture and a genuine
solve's writeback are indistinguishable from the read side, and hand-placing gives EXACT, reproducible
control over scenarios like "group C is full at maxSize with every member's priority ≥ Kalle's",
which is difficult to force out of a real (correctly-behaving) solver without extensive pinning
boilerplate that would obscure the actual assertions. The E2E section below additionally validates the
full pipeline against a REAL Timefold-produced solve on the full `large-120` dataset, closing the gap.

## M7 review fixes (Opus review round, "MERGE WITH FIXES")

1. **M1 (MAJOR) — level-band positive factor lied.** `addLevelMatchFactor` emitted "nivåscore X
   matchar nivåspann A–B" whenever a band existed, without checking membership (the review caught it
   firing falsely in this file's own E2E transcript: 971,0 "matchar" 850,0–913,0). Fixed: the
   "matchar" sentence is a positive factor ONLY when `levelScaled ∈ [bandMin, bandMax]`; outside, an
   honest "ligger över/under gruppens nivåspann A–B" goes to `negativeFactors` instead. The bandless
   fallback ("ligger nära nivåsnitt") had the same unverifiable-claim shape and was neutralized to a
   plain two-number statement ("nivåscore är X; nivåsnitt är Y"). Tests: `ReviewFixesTest` (in-band
   → matchar positive; above-band → ligger över negative, never matchar; below-band → ligger under).
2. **M2 (MAJOR) — incomplete revision-bump surface → false-fresh explanations.** The initial
   implementation only bumped on manual moves/locks/participant edits; group generation, level
   recompute, participant create/delete, import commit, block/slot CRUD, coach CRUD/availability and
   coach field values all mutated placement-relevant data WITHOUT bumping — an old run's explanation
   could report `stale=false` while describing a world that no longer exists. Fixed: complete bump
   table above (plus `ConstraintWeightController#update`, which the review didn't list but design
   §11.6 does and the same false-fresh logic applies — the assembler reads CURRENT weight config into
   every recomputation). Tests: `StalenessAndApplyMoveTest#recomputeLevelsBumpsRevisionAndMarksPriorRunStale`
   and `#groupGenerateBumpsRevisionAndMarksPriorRunStale`.
3. **m3** — group `warnings` no longer leak raw `HARD/MEDIUM/SOFT` enum strings into Swedish display
   text: mapped to "Hård regel"/"Systemregel"/"Mjuk regel" (`ExplanationService#levelLabelSv`).
   `ConstraintSummaryView.level` (a machine-readable FIELD, not display text) keeps the raw value.
4. **m4** — an exactly-zero what-if delta was labelled `WORSE` ("skulle försämra totalpoängen") — a
   false statement. Added a `NEUTRAL` verdict ("påverkar inte totalpoängen"); the UI-facing enum is
   now `WOULD_BREAK_HARD|BETTER|NEUTRAL|WORSE` (documented on `AlternativeGroupView`'s javadoc).
   Test: `ReviewFixesTest#exactlyZeroDeltaGetsNeutralVerdictNotWorse` (symmetric groupSizeTarget
   shift, identical levels — provably zero-delta fixture).
5. **m5** — `AssignmentController#move` is now `@Transactional` (insert-if-absent + update + bump as
   one atomic unit), and moving a participant to the group they are ALREADY in short-circuits to a
   200 no-op: no revision bump (nothing to invalidate) and no `source` flip to `manual` (provenance
   stays honest). Test: `StalenessAndApplyMoveTest#applyMoveToCurrentGroupIsANoOpWithoutBumpOrSourceFlip`.
6. **m6** — `MoveProbe` restore-verify: after the FIRST probe's restore for each request's solution
   instance, one `FETCH_SHALLOW` re-analysis asserts the baseline score is re-attained (throws
   `IllegalStateException` on mismatch — a failed restore would silently poison every subsequent
   probe). Once per solution instance (weak-key set), not per probe, so the check costs one cheap
   score-only analysis per request. Exercised implicitly by every existing probe test.
7. **m7** — the waitlist "förbättring möjlig" warning now aggregates ALL feasible group names into
   one message (was last-wins, dropping all but the final group), and the solver-quality WARN log is
   deduped per `(runId, participantId)` instead of firing once per feasible group per request. Test:
   `ReviewFixesTest#qualityWarningAggregatesAllFeasibleGroupNames`.

## Tests

`./mvnw clean verify`: **443 tests, 0 failures, 0 errors** (M6b baseline: 408 → +27 in the initial
M7 round across 9 new files, +8 more in the review-fix round: `explain.ReviewFixesTest` (5) and three
additions to `api.StalenessAndApplyMoveTest`). Golden scores
(`test-data/regression/expected-scores.json`) byte-for-byte unchanged — nothing in M7 touches
constraint code, weights, or solver configuration.

- `explain.CanonicalQuestionTest` (2) — **the M-S3 gate (b) canonical question test**: Kalle has a
  friend-wish for Lisa; Lisa's group C is full (maxSize) with every member's priority ≥ Kalle's;
  asserts selected group + positive factors, the broken wish, an alternative for group C with origin
  `FRIEND_WISH`, verdict `WOULD_BREAK_HARD`, `newlyBroken` containing the `groupMaxSizeHard` message,
  `narrativeSv` in the "Grupp C är full: N/N" shape, and non-empty `appliedWeights`; PLUS the
  waitlisted-friend variant (Lisa herself unassigned → "Lisa är oplacerad (kölista)" +
  `unassignedFriendParticipantProfileId` link, verified to resolve to her own waitlist explanation).
- `explain.Section23Point7Test` (1) — all five §23.7 bullets scripted explicitly.
- `explain.WhatIfServiceTest` (3) — §18.1 output-fields (sizes, spread deltas, score delta,
  `wouldBreakHard`), an explicit hard-overflow case, and why-not on a group with NO relationship to
  the player at all (would never appear in the automatic candidate set).
- `explain.WaitlistExplanationTest` (2) — concrete per-group hard blocker (full-with-priority-context,
  time-unavailable) and the "förbättring möjlig" solver-quality-warning branch (deliberately
  constructed: a waitlisted player with an obviously open, unblocked group — simulating an
  undersolved run), asserting it is NEVER phrased as a priority story.
- `explain.GroupAndPlanExplanationTest` (2) — group level (size/target/spread/coach/block/warnings/
  members-with-broken-wishes) and plan level (score/constraintSummaries/waitlist/manualReview).
- `explain.ExplanationCacheTest` (2) — LRU key-shape invalidation on revision bump, and bounded
  eviction.
- `explain.ExplanationLatencyTest` (1) — **<1 s cold per-player latency on `large-120`**, measured
  (147 ms observed; asserted `< 1200 ms` for CI margin per the task's own "assert with margin" note).
- `explain.ExplanationRecordLeakTest` (1) — privacy leak assertion extended to `explanation_record`'s
  five JSON columns and `optimization_run.result_summary_json`'s new `constraintSummaries` field.
- `api.StalenessAndApplyMoveTest` (7) — **the staleness flip test** (manual move → `stale=true`,
  re-solve → fresh, old run stays/becomes stale), apply-move 409 while solving + revision bump on
  success, move-to-waitlist, the locked-assignment 400, the m5 same-group no-op, and the M2
  recompute-levels/group-generate staleness flips.
- `explain.ReviewFixesTest` (5) — review fixes M1 (three band-truthfulness cases), m4 (NEUTRAL
  verdict), m7 (aggregated quality warning).
- `db.FlywayMigrationTest` — extended for the new migration name/table.

## Endpoints (all under `/api`, per docs/design/04-solver.md §14.2)

- `GET /api/plans/{planId}/runs/{runId}/explanations/plan`
- `GET /api/plans/{planId}/runs/{runId}/explanations/groups/{groupId}`
- `GET /api/plans/{planId}/runs/{runId}/explanations/players/{participantProfileId}`
- `POST /api/plans/{planId}/whatif/move` `{participantProfileId, targetGroupId, runId}`
- `POST /api/plans/{planId}/whatif/why-not` `{participantProfileId, groupId, runId}`
- `POST /api/plans/{planId}/assignments/{participantProfileId}/move` `{groupId}` — manual move
  (source=manual), 409 while solving, 400 if currently locked, bumps `plan_revision`.

## E2E verification (packaged jar, dev profile, isolated `--app.data-dir`, port 4999)

`./mvnw package -DskipTests` → `target/backend.jar`, launched `--spring.profiles.active=dev
--server.port=4999 --app.data-dir=/tmp/gp-e2e-m7-data` (token `dev`).

Season + plan created via the real API (`defaultGroupTargetSize=10/min=8/max=12`); 4 time slots
matching `large-120`'s own README shape (16.30–18.00×3, 18.00–19.30×4, 19.30–21.00×3, 21.00–22.30×2 →
12 blocks) via the real API. 130 participants seeded directly into `person`/`participant_profile`/
`player_assignment`/`custom_field_value` (priority, cannotTimes, preferTimes, playWith, mustPlayWith,
mustNotPlayWith) from `test-data/datasets/large-120/participants.csv` — same anonymized fixture the
automated tests use, no confidential file involved (task explicitly allows "direct SQL seed").
`POST .../participants/recompute-levels` (130) and `POST .../groups/generate` (12 groups) via the
real API.

**Solve 1** (`profile=NORMAL`): `-12hard/-800medium/-350720soft`, 27.7 s. Hard=-12 is
`coachRequirementHard` (no coaches seeded — expected, out of scope for this flow); medium=-800 is the
3 permanently-unavailable participants the dataset's own README documents (p019/p026/p055-equivalent
by construction). `optimization_run.result_summary_json.constraintSummaries` confirmed populated for
all 32 keys (task item 2, live proof).

**Player explanation** — picked Tuva Nilsson (`p048`, ranking 971), one of the dataset's documented
"impossible" mutual friend pairs (Tuva ↔ Örjan Hansson/`p129`, ranking far apart). Her explanation
correctly showed:
```json
"selectedGroup": {"name": "Torsdag Herr 2", "size": 12, "targetSize": 10, "maxSize": 12,
                   "levelMeanSv": "955,3", "levelSpread": 256},
"positiveFactors": ["Tuva Nilsson:s nivåscore 971,0 matchar Torsdag Herr 2:s nivåspann 850,0–913,0",
                     "Torsdag Herr 2 hade plats: 12/12 spelare",
                     "Tuva Nilsson kunde träna på Torsdag Herr 2:s tid (Torsdag 18.00–19.30)"],
"brokenWishes": [{"key": "sameGroupSoft", "withPerson": "Örjan Hansson", "weightApplied": 80,
                   "messageSv": "Kompisönskemål Tuva Nilsson–Örjan Hansson brutet"}],
"alternatives": [{"name": "Torsdag Herr 8", "origin": ["FRIEND_WISH"], "verdict": "WOULD_BREAK_HARD",
                   "newlyBroken": [..., {"key": "timeAvailabilityHard",
                     "messageSv": "Tuva Nilsson kan inte träna på Torsdag 16.30–18.00 (grupp Torsdag Herr 8)"}],
                   "narrativeSv": "Torsdag Herr 1 har inte högre nivå än Torsdag Herr 2 (skillnad 161 poäng)"}, ...]
```
— the REAL blocker turned out to be `timeAvailabilityHard` (Tuva can't attend Örjan's group's time),
not just the level gap the dataset README frames it around; this is exactly the point of computing
from data rather than assuming.

> **Note (post-review):** the first `positiveFactors` line in this transcript — "971,0 matchar ...
> nivåspann 850,0–913,0" — was itself a FALSE claim (971 is outside 850–913) and is exactly what the
> Opus review's M1 finding caught. Under the fixed code this line renders as a NEGATIVE factor:
> "Tuva Nilsson:s nivåscore 971,0 ligger över Torsdag Herr 2:s nivåspann 850,0–913,0" (asserted in
> `ReviewFixesTest#outOfBandPlayerNeverGetsMatcharPositiveButAnHonestNegative`, which reproduces this
> exact fixture shape). The transcript is preserved unedited as the evidence trail for the finding.

**Why-not** (`Torsdag Herr 8` explicitly) reproduced the identical `verdict`/`newlyBroken`/`newlyFixed`
as the automatic alternative — same underlying `MoveProbe`, confirmed byte-identical except for
`origin` (empty for why-not, since it isn't tagged by the union rule).

**What-if move** (Tuva → Torsdag Herr 8) reproduced the same consequence report plus
`groupSizeChanges`/`levelSpreadChanges` (Herr 2: 12→11, spread 256→236; Herr 8: 8→9, spread 93→1285 —
a genuine large spread increase, the spec's own "82 → 141"-style example shape) and
`suggestedActions: [KEEP, MOVE_ANYWAY, LOCK_AND_RESOLVE]`.

**Apply move**: `POST .../assignments/{tuvaId}/move {"groupId": "<Herr8>"}` → `200`, `source: manual`.
Immediately re-fetched Tuva's explanation under the ORIGINAL runId → `stale: true` (`basedOnRevision:
1, currentRevision: 2`).

**Re-solve** (`profile=FAST`) → new `runId2`. Its own explanation: `stale: false` (`basedOnRevision: 3
== currentRevision: 3`). The OLD `runId`'s explanation, re-checked: now `stale: true` with
`currentRevision: 3` — confirming Deviation 6's "a later solve also invalidates older runs" behavior
live, not just in a unit test.

`POST /api/system/shutdown` cleanly stopped the server (`{"status":"SHUTTING_DOWN"}`, confirmed
unreachable afterward).

## Known gaps / deferred

- The optional swap-probe simulation for the waitlist priority narrative (Deviation 5).
- Cross-process cache-warm continuity from `explanation_record` back into `ExplanationCache`
  (Deviation 2) — the audit table exists and is populated, just not read back yet.
- `recommendAssignment` "Föreslå grupper" panel (design §12.3) — explicitly marked post-MVP-optional
  stretch in the design doc; §11.3's alternatives already answer the required questions.
- `FieldDefinitionController` PATCH of a field's `hardOrSoft`/`weight` does not bump `plan_revision`:
  a GLOBAL field row (activityPlanId NULL) affects every plan at once, so per-plan bumping is
  ill-defined there (would need a bump-all-plans sweep or a global revision counter). Per-plan custom
  field edits could bump cheaply — a scoped follow-up once the product decides how global-field edits
  should invalidate. Recorded per the M2 review round's "document the now-complete bump surface"
  requirement so the one known residual is explicit, not silent.
- Frontend (`ExplainDrawer`/`WhatIfDialog`) — out of scope for this backend-half milestone; note the
  m4 review fix means frontends must handle the fourth `NEUTRAL` verdict value.
