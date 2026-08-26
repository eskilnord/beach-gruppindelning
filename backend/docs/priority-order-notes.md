# Priority order default weights (v0.6.0 milestone B6)

Design decisions and rationale for the new default-priority-order weight ladder shipped in
milestone B6: `se.klubb.groupplanner.fields.PriorityOrder` (the SPI), migration
`V13__priority_order_default_weights.sql` (the DB seed), and the matching `ofSoft(N)` constants in
`GroupPlanConstraintProvider`. Primary contract: this task's own brief (no design-doc milestone
predates this); `docs/design/04-solver.md` §4 (the original per-constraint weight table);
`backend/docs/m6b-notes.md` "Weight reconciliation vs design §4" (the PREVIOUS reconciliation this
milestone supersedes — see the SUPERSESSION section below).

## What changed

Thirteen `constraint_definition.default_weight` rows moved (old → new):

| Key | Old | New | Priority bucket |
|---|---|---|---|
| `sameGroupSoft` | 80 | 2400 | TRAIN_TOGETHER, rank 1 of `UNIT_LADDER` |
| `differentGroupSoft` | 60 | 1800 | TRAIN_TOGETHER, rank 1 of `AVOID_LADDER` (0.75× `UNIT_LADDER`, rounded to nearest 5) |
| `previousGroupContinuity` | 30 | 1500 | PREVIOUS_GROUP, rank 2 of `UNIT_LADDER` |
| `timePreferenceSoft` | 40 | 950 | PREFERRED_TIME, rank 3 of `UNIT_LADDER` |
| `levelBalance` | 100 | 85 | LEVEL, rank 4 of `LEVEL_LADDER` |
| `groupOrderByLevel` | 50 | 42 | LEVEL, rank 4 of `ORDER_LADDER` (`LEVEL_LADDER` halved — see "LEVEL-family unit coherence" below) |
| `groupSizeTarget` | 50 | 800 | not in the 4-priority ladder (see "Size discipline" below) |
| `groupMinSizeSoft` | 50 | 2000 | not in the 4-priority ladder |
| `coachLevelFit` | 50 | 42 | not in the 4-priority ladder (0.5× `levelBalance` per spread unit) |
| `coachPreferenceSoft` | 50 | 600 | not in the 4-priority ladder |
| `coachPreferredTimeSlot` | 20 | 250 | not in the 4-priority ladder |
| `coachUnknownTimeSlot` | 20 | 250 | not in the 4-priority ladder |
| `lateTimeForLowerGroups` | 30 | 300 | not in the 4-priority ladder |

## LEVEL-family unit coherence (2026-08-26 review fix)

Before this fix, `groupOrderByLevel` and `coachLevelFit` computed their matchWeight in **whole level
points** (`floorDiv(diff, 100)`-style), while `levelBalance` had already moved to **spread units**
(`LevelMath.SPREAD_UNIT_SCALED` = 1000 scaled = 10 level points/unit) back in milestone B2. The three
constraints looked coherent on paper (they share the LEVEL bucket, or ride the same 0.5× relative
scale) but actually disagreed by a factor of ~10 in what their matchWeight *meant* — an inversion
worth "70 level points" scored a matchWeight of ~71 under the old `groupOrderByLevel` formula but
only ~7 under the unit `levelBalance` already used for the same 70-point gap. This silently
over-weighted ordering/coach-fit penalties by roughly 10× relative to spread, an unintended
consequence of B2's unit change never being propagated to its two siblings.

The fix: `groupOrderByLevel`'s and `coachLevelFit`'s matchWeight formulas both moved to the same
`LevelMath.SPREAD_UNIT_SCALED` basis levelBalance already uses (`GroupPlanConstraintProvider
.meanDiffPoints`/`coachDistancePoints`). Their DEFAULT weights were retempered alongside the unit
change to preserve each constraint's *relative* strength against `levelBalance`, not its raw number:
`groupOrderByLevel` gets its own `ORDER_LADDER` (`LEVEL_LADDER` halved by integer floor division:
`{170, 107, 67, 42}`), restoring the original design's ≈0.5× relative strength of ordering vs. spread
per unit (design §4: `groupOrderByLevel` SOFT 5/point vs. `levelBalance` SOFT 2/point ≈ the same
0.5–2.5× ballpark once units are shared) — at the default order a 70-point inversion now costs 7
spread units × 42 = 294, about 0.56× one `levelBalance` band-cost (7 × 85 = 595), matching pre-v0.6.0
relative semantics. `coachLevelFit`'s fixed (non-ladder) default moved 85 → 42 for the same reason
(0.5× `levelBalance` per spread unit, the original relative intent).

## Size discipline (2026-08-26 review fix)

`groupSizeTarget` (400 → 800) and `groupMinSizeSoft` (800 → 2000) were retempered together so that
pulling a single player below a group's `minSize` now costs `groupSizeTarget + groupMinSizeSoft` =
800 + 2000 = **2800**, strictly more than the strongest `UNIT_LADDER`/`AVOID_LADDER` rank (`sameGroupSoft`
at rank 1 = 2400) — i.e. **no single wish can ever drain a group below its minimum**, and by the same
arithmetic no single wish can ever empty a group either (emptying costs at least as much as
under-minning, since `groupSizeTargetEmpty`/`groupMinSizeEmpty` apply the SAME weights to the full
`targetSize`/`minSize` deviation of an empty group). A wish CAN still push up to 3 players' worth of
target-size deviation (3 × 800 = 2400 ≤ `sameGroupSoft`) before the group's HARD `maxSize` cap takes
over — deliberately still permissive enough that "unite everyone the friend wish asks for" remains
reachable in the common case, just never at the cost of starving another group below its floor. At
the ×4 UI preset ceiling (`WeightLimits.MAX_WEIGHT / 4` = 2500), the largest possible per-row value
for these two constraints is 3200/8000 — both ≤ `WeightLimits.MAX_WEIGHT` (10 000), so the discipline
holds even at the most extreme user-configured weights, not just the shipped defaults.

## Continuity becomes binary (2026-08-26 review fix)

`previousGroupContinuity`'s matchWeight was `Math.abs(order - prevOrder)` (linear in the number of
group-order steps moved) — this broke the ladder's rank-ordering promise at every magnitude past the
first: at `UNIT_LADDER` rank 2 (1500), just 2 steps of drift (3000) already outweighs a rank-1 friend
wish (2400), silently inverting the priority the ranking was supposed to express. The fix caps the
matchWeight to a binary 0/1 (`min(abs(diff), 1)`): a player either landed back with their previous
group's order or they didn't — "how far" is no longer part of this constraint's job. The weight itself
is unchanged (`UNIT_LADDER` at whatever rank PREVIOUS_GROUP is given, 1500 at the default order's rank
2). A nearness *gradient* is not lost by this — it is already supplied by the LEVEL-bucket constraints:
`previousGroupLevel` feeds a player's `estimatedLevel`, which `levelBalance`/`groupOrderByLevel`
already optimize for, so continuity only needs to say "kept together or not."

`groupSizeTargetEmpty`/`groupMinSizeEmpty` have no `constraint_definition` row of their own — they
fan out from `groupSizeTarget`'s/`groupMinSizeSoft`'s ONE row (`ConstraintKeys.COMPLEMENTS_OF`) and
move automatically with it. `unassignedPlayer` (MEDIUM, ADR-006) and every HARD weight are untouched.

## The four-priority ladder

`PriorityOrder.Priority` is TRAIN_TOGETHER / PREVIOUS_GROUP / PREFERRED_TIME / LEVEL. A user's
ranking of these four families maps to concrete weights via three rank-indexed ladders:

```
UNIT_LADDER  = { 2400, 1500,  950,  600 }   // sameGroupSoft, previousGroupContinuity, timePreferenceSoft
LEVEL_LADDER = {  340,  215,  135,   85 }   // levelBalance (≈ UNIT_LADDER / 7)
ORDER_LADDER = {  170,  107,   67,   42 }   // groupOrderByLevel (= LEVEL_LADDER halved, integer floor)
AVOID_LADDER = { 1800, 1125,  715,  450 }   // differentGroupSoft (0.75 × UNIT_LADDER, rounded to nearest 5)
```

`levelBalance` and `groupOrderByLevel` share the LEVEL bucket rank but each has its OWN ladder since
the 2026-08-26 review fix (see "LEVEL-family unit coherence" above) — before that fix they wrongly
shared `LEVEL_LADDER` even though their matchWeight units disagreed by ~10×.

The v0.6.0 **default order** is `TRAIN_TOGETHER, PREVIOUS_GROUP, PREFERRED_TIME, LEVEL` — i.e.
"train with your friends" outranks "keep last term's level group", which outranks "get your
preferred time slot", which outranks "keep the group level-balanced". This is a deliberate product
choice (a friend-group's cohesion matters more than a perfectly level-sorted group), not an
accident of implementation.

### The r≈1.6 rationale

Each ladder's ratio between adjacent ranks is ≈1.6 (`WeightCalibrationTest` pins the exact bounds:
every ratio ∈ [1.5, 1.7]). Why 1.6, not linear or a much steeper geometric ratio:

- **Linear** (e.g. 100/75/50/25) makes rank 2 barely distinguishable from rank 3 in practice —
  whichever constraint happens to fire more matches at solve time can flip the EFFECTIVE ordering
  even though the nominal rank order says otherwise.
- **Very steep** (ratio ≥ 3) makes every rank but the first practically inert — rank 4 would never
  win a single trade-off against rank 1, making the whole "rank all four" exercise pointless for
  three quarters of the list.
- **≈1.6** keeps every rank meaningfully dominant over the next while still letting a low-ranked
  constraint occasionally win a small, cheap trade-off against a high-ranked one that would cost it
  dearly — see the worked example below.

### Worked example: how far one friend wish drags a player

At the default order, `sameGroupSoft` = 2400. One "band" of level imbalance costs `LevelMath
.spreadUnits`-sized matchWeight units × `levelBalance`'s weight: a typical level band (~70 level
points) is 7 spread units (`LevelMath.SPREAD_UNIT_SCALED` = 1000 scaled = 10 level points/unit), so
one band costs 7 × 85 = **595**.

`k* = sameGroupSoft / oneBandCost = 2400 / 595 ≈ 4.03`

**`k*` is W-DEPENDENT, not a universal constant** (review fix, 2026-08-26 — an earlier version of
this document and `WeightCalibrationTest` wrongly treated `k* ∈ [3, 6]` as width-independent, backed
by a test assertion that was an algebraic tautology and verified nothing). Computed exactly for three
band widths `W` (level points), `bandUnits = W / 10`, `oneBandCost = bandUnits × 85`, `k*(W) =
2400 / oneBandCost`:

| `W` (level points) | `bandUnits` | `oneBandCost` | `k*(W)` |
|---|---|---|---|
| 40 | 4 | 340 | ≈ 7.06 (**already > 6** — outside the old, wrongly-universal [3, 6] claim) |
| 70 | 7 | 595 | ≈ 4.03 |
| 100 | 10 | 850 | ≈ 2.82 |

A narrower "band" definition buys MORE bands per wish (smaller `oneBandCost`), a wider one buys
FEWER — `k*` genuinely shrinks as `W` grows. What IS width-independent is a DIFFERENT invariant:
`k*(W) × W` — the total level-POINT displacement a wish can win — collapses algebraically to
`sameGroupSoft × 10 / levelBalance = 2400 × 10 / 85 ≈ 282.4`, regardless of `W`:

**a friend wish drags ≈280 level points of displacement, regardless of how wide a "band" is taken to
be.** `WeightCalibrationTest` pins this invariant directly (`k*(W) × W ∈ [270, 290]` for all three
swept widths, via pure-integer cross-multiplication), which is the honest replacement for the old
(vacuous) per-width sweep.

**One-sided-model caveat**: ≈280 level points is a LOOSE UPPER BOUND, not a typical real drag. This
worked example only weighs `sameGroupSoft` against `levelBalance` in isolation — a real solve also
pays the SOURCE group's own SAD change (moving a player out of their level-balanced group usually
also disturbs that group's spread) and `groupSizeTarget`/`groupMinSizeSoft`'s size costs (a player
leaving/joining shifts both groups' sizes away from target), both of which make the ACTUAL cost of
dragging a player higher than this single-constraint estimate — so the real achievable drag is
shorter than 280 points in practice, not longer. `PriorityOutcomeCalibrationTest` is the executable,
outcome-level check that the product claim ("friend wishes CAN unite players across level bands")
actually holds on a realistic fixture, rather than relying solely on this one-sided weight arithmetic.

### `differentGroupSoft` rides the TRAIN_TOGETHER bucket at 0.75×

`differentGroupSoft` (avoiding an unwanted pairing) does not get its own independent rank. It is
real, but — per product judgment — a notch less important than satisfying a positive "want to play
with" wish at the SAME priority level, so it uses `AVOID_LADDER` = 0.75 × `UNIT_LADDER` at whatever
rank the user gave TRAIN_TOGETHER (rounded to the nearest 5, since a plain 0.75× gives a
non-integer at rank 3 — `712.5` — `WeightCalibrationTest` pins every rank within ±5 of the exact
0.75× value), rather than being ranked separately.

### Reversal check: level dominates a wish per band under reversal

Ranking LEVEL first and TRAIN_TOGETHER last (`LEVEL, PREFERRED_TIME, PREVIOUS_GROUP,
TRAIN_TOGETHER`) gives `levelBalance` = 340 (`LEVEL_LADDER[0]`) and `sameGroupSoft` = 600
(`UNIT_LADDER[3]`). One band now costs 7 × 340 = **2380**, so **level dominates a single wish per
band under reversal (340×7=2380 > 600)** — `WeightCalibrationTest`'s reversal-sanity case pins this
exactly. This is the SAME per-band dominance relationship the default order gives friend wishes over
level balance, just with the two families' ranks swapped; it is not a claim that every OTHER aspect
of pre-v0.6.0 scoring behavior (unrelated constraints, the ladder's shape, `groupOrderByLevel`'s now
UNIT-COHERENT relationship to `levelBalance`, etc.) is reproduced unconditionally — only the one
worked relationship above is verified.

### `k*≈4` (at the 70-point convention) as an accepted, mitigated trade-off

A single friend wish being able to drag a placement several level bands (≈280 level points at any
band-width convention — see the invariant above) is a REAL, ACCEPTED cost of this design, not an
oversight:

- **Accepted**: product intent is that a friend-group wish should be able to meaningfully override
  level-balance when the two conflict — that is the entire point of ranking TRAIN_TOGETHER above
  LEVEL by default.
- **Mitigated**: the ladder ratio (≈1.6, not steeper) keeps the drag self-limiting to a
  width-independent ≈280-point invariant, never unbounded — a friend wish can win a moderate
  trade-off, never an arbitrarily large one. `groupOrderByLevel` (also LEVEL-bucketed, and — since
  the 2026-08-26 unit-coherence fix — sharing `levelBalance`'s matchWeight UNIT, not just its rank)
  still applies, so wildly inverted group ordering remains penalized even when an individual
  placement drifts a few bands; before that fix the two constraints' units silently disagreed, which
  is a false-parity risk this milestone specifically closes, not a pre-existing guarantee.
  A user who wants the OLD (pre-v0.6.0) unconditional level-balance priority can always rank LEVEL
  first (the reversal case above) or set an explicit per-plan constraint-weight override (spec §9.4)
  — this milestone changes DEFAULTS only, never removes the override mechanism.

## SUPERSESSION notice

**kravspec §17.2's own worked example ("Kompisönskemål var soft 60, medan nivåbalans var soft 100
och maxstorlek var hard" — i.e. level balance dominates friend wishes) and `m6b-notes.md`'s "Weight
reconciliation vs design §4" decision (which explicitly cited that same §17.2 example as "the
authority on intended priority ordering") are BOTH INVERTED by this v0.6.0 milestone's design.**
This is a deliberate product-direction change, not a correction of a prior mistake — the pre-v0.6.0
defaults were a reasonable reading of the original spec text at the time `m6b-notes.md` was written;
v0.6.0 supersedes that reading with the new priority-order product design (this document). Anywhere
else in the codebase or design docs that still quotes kravspec §17.2's old weight numbers as the
current behavior is now describing the LEVEL-first reversal case above, not the default order.

Real production use of these new defaults starts with season VT27 (see the V13 migration's own
header comment) — existing VT26-era plans are unaffected unless re-solved, and any plan that has
already set its own per-constraint overrides keeps them exactly as before; only a plan's UN-overridden
defaults change.

## Outcome evidence: `PriorityOutcomeCalibrationTest` vs. the large-120 dataset (2026-08-26)

The product claim this whole milestone rests on — "ranking TRAIN_TOGETHER above LEVEL lets friend
wishes unite players who would otherwise be split by level balance" — needs to be shown on an actual
solve outcome, not just weight arithmetic (the worked example above is a loose one-sided upper bound,
not a guarantee). Two datasets were used to check this, with very different results:

- **`large-120` (real regression dataset, A/B'd old-vs-new defaults)**: the 22 broken `WANT_SAME`
  wishes on this dataset are **UNCHANGED** between old and new default weights — the new priority
  order does NOT unite any of them. This is NOT a sign the design doesn't work; on inspection, all 22
  are either HARD-blocked (the two participants are structurally incompatible — different required
  time availability, coach exclusivity, etc. — no weight change can unite a HARD-infeasible pair) or
  unreachable under the current LOCAL SEARCH MOVE SELECTOR's known imbalance (see the design doc's
  own documented move-selection coverage gap — some pair-swap moves the solver would need to try to
  unite a specific split pair are underrepresented in the move selector's sampling on a dataset this
  large, a pre-existing, separately-tracked issue, not something this milestone's weight changes could
  fix). `large-120` is simply the wrong instrument for proving THIS claim: its 22 broken wishes were
  never going to move on a weight change alone.
- **`PriorityOutcomeCalibrationTest` (new, purpose-built fixture)**: a small (24–30 player), 3-level-
  band, 3–4 group, deterministic fixture (seed 0, `WeightOverrideFlipTest`-style construction) with
  3+ `WANT_SAME` pairs whose members sit 1–2 level bands apart and face NO hard blockers — i.e. a
  fixture actually designed to be sensitive to this milestone's weight change, unlike `large-120`.
  Solved twice via `ConstraintWeightOverrides` (once at the OLD pre-v0.6.0 defaults, once at the NEW
  ones), it shows the NEW defaults unite strictly MORE of the eligible pairs than the OLD ones on the
  SAME fixture — this is the executable evidence for the product claim, isolated from `large-120`'s
  confounding HARD-blockers and move-selector coverage gap.

**Milestone H's golden-review follow-up**: `large-120`'s outcome quality (including whether any of
those 22 broken wishes become reachable) needs re-checking after milestone B5's continuity wiring
lands — B5 changes what moves the solver considers for previously-grouped players, which may close
some of the move-selector coverage gap cited above. Until then, `large-120`'s unchanged wish count is
an expected, explained result of this milestone, not a regression.

## See also

- `se.klubb.groupplanner.fields.PriorityOrder` — the SPI (class javadoc carries the same rationale
  in code-adjacent form).
- `fields.WeightCalibrationTest` — the executable form of this document's arithmetic (per-band-width
  `k*`, the width-independent `k*(W)×W` invariant, and the `AVOID_LADDER` rounding rule).
- `fields.PriorityLadderSeedConsistencyTest` — proves the DB seed (V13) and `PriorityOrder` agree.
- `solver.PriorityOutcomeCalibrationTest` — the outcome-level evidence for the product claim (see
  "Outcome evidence" above), independent of `large-120`'s confounders.
- `backend/docs/m6b-notes.md` §"Weight reconciliation vs design §4" — the reconciliation this
  document supersedes (dated pointer added there, history not rewritten).
- `docs/design/04-solver.md` §4 — the original per-constraint weight table (header note points here).

**Milestone H re-check (2026-08-26, after B5)** — the "22 broken, unchanged" result above is
SUPERSEDED. Re-measured on `large-120` at `stepCountLimit=20000`, old-weights-vs-new-weights on
identical code: `WANT_SAME` broken records 23 → 15 (4 pairs newly united), continuity kept
3/125 → 25/125 (85% at convergence), `timePreferenceSoft` misses 30 → 17, and no group left below
`minSize` (the old weights drained one to 7). `large-120` is therefore no longer "the wrong
instrument" — B5's continuity wiring made it sensitive to the weight change, and it now corroborates
`PriorityOutcomeCalibrationTest` rather than being neutral. Two caveats stand: the A/B reverts
WEIGHTS only (B6's `LevelMath.SPREAD_UNIT_SCALED` matchWeight change is in the provider and not
revertible, so soft TOTALS are not comparable — only the outcome counts), and the remaining broken
records are dominated by structurally-incompatible pairs. Golden step budget raised 20000 → 200000
at the same gate (the old budget pinned a 0.7 s pre-convergence snapshot; see
`SolverRegressionTest`'s javadoc). Known follow-up (separate ticket, pre-existing): the
`groupOrderByLevel` ordinal-permutation plateau on `coach-overlap-20` — repairing a group-ordinal
inversion needs a simultaneous membership permutation that single-entity change/swap moves cannot
reach at any step budget; a move-selector tuning task.
