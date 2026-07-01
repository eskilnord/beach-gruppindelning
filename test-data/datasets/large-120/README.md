# large-120

The stress-test fixture (spec §22.2: *"120 spelare, 12 grupper, flera tidspreferenser"*): 130 participants against 12 group-blocks of capacity, rich friend/coach wishes, varied confidence in level data, and a handful of participants who can never be placed. The directory keeps the historical `large-120` name even though it now generates 130 participants — the extra 10 create real waitlist/target-size pressure without making the dataset infeasible.

## Shape

- 130 participants (`p001`..`p130`), 8 coaches (`c01`..`c08`), 4 timeslots (Torsdag 16.30–18.00 courts=3, Torsdag 18.00–19.30 courts=4, Torsdag 19.30–21.00 courts=3, Torsdag 21.00–22.30 courts=2) = 12 group-blocks.
- Group config: category `Torsdag Herr`, target 10, min 8, max 12.
- Capacity: 12 × target 10 = 120 < 130 (target-size pressure by design); 12 × max 12 = 144 ≥ 130 (hard-feasible in principle, modulo the permanently-unavailable participants below).

## Invariants tests can rely on

- **Waitlist certainties**: p019, p026, p055 have `unavailable_time_slot_ids` covering all 4 timeslots. They can never be placed in any feasible solve — a correct solver leaves them unassigned (medium-score cost only, never a hard violation).
- **Partial time constraints**: 22 further participants have 1–3 (never 4) `unavailable_time_slot_ids` entries, for a total of 25 participants with any time restriction.
- **Missing ranking, has previous level**: p009, p017, p034, p036, p039, p047, p050, p068, p116, p123 have empty `ranking_points` but a populated `previous_group_name`/`previous_group_level` — exercises the level-confidence fallback chain (ranking → previous group).
- **Neither** (low confidence): p024, p067, p081, p102, p119 have empty `ranking_points`, `previous_group_name` and `previous_group_level`; all five also have `new_to_club = 1` and `needs_review = 1`.
- `previous_group_name` follows the pattern `"Torsdag Herr <level> (Hösttermin 2025)"`, consistently mapped to `previous_group_level` 1–12 (counts per level: level 1: 11, level 2: 10, level 3: 11, level 4: 10, level 5: 11, level 6: 10, level 7: 10, level 8: 11, level 9: 10, level 10: 11, level 11: 10, level 12: 10).
- **Friend wishes** (`wants_with_ids`, 15 relationships total):
  - 2 **impossible** mutual pairs (levels differ wildly — satisfying them would fight level-balance): `p048` (ranking 971) ↔ `p129` (ranking 209); `p080` (ranking 965) ↔ `p104` (ranking 240)
  - 7 further ordinary mutual pairs: `p115`↔`p130`, `p064`↔`p049`, `p043`↔`p116`, `p112`↔`p123`, `p033`↔`p032`, `p004`↔`p093`, `p028`↔`p057`
  - 6 one-directional wishes (truster → target, not reciprocated): `p053`→`p044`, `p010`→`p122`, `p056`→`p100`, `p082`→`p065`, `p106`→`p085`, `p005`→`p016`
- **Must-with pairs** (`must_with_ids`, hard, symmetric): `p046`↔`p031`, `p103`↔`p007`, `p018`↔`p107`
- **Not-with pairs** (`not_with_ids`, hard, symmetric): `p084`↔`p070`, `p063`↔`p117`
- **Coach wishes** (`wants_coach_id`, 10 participants): `p001`→`c02`, `p012`→`c06`, `p030`→`c06`, `p049`→`c02`, `p051`→`c05`, `p066`→`c05`, `p094`→`c07`, `p099`→`c06`, `p111`→`c07`, `p112`→`c05`
- **Priority** (1–3) distribution: 16 at priority 1, 67 at priority 2, 47 at priority 3.
- **Time preferences** (`preferred_time_slot_ids`): 40 participants have 1–2 preferred slots.
- No coach has `also_plays_participant_id` set (that scenario is `coach-overlap-20`'s job); coaches `c05`/`c06` are the only ones using `unavailable_time_slot_ids` (all others express availability purely via `available_time_slot_ids`).
