# small-10

Happy-path fixture: a single, uncontroversial evening with plenty of room for everyone. Used as the basic sanity fixture — if this dataset doesn't solve to `hardScore == 0` with zero unassigned players, something fundamental is broken.

## Shape

- 10 participants (`p001`..`p010`), 2 coaches (`c01`, `c02`), 2 timeslots.
- Group config: category `Torsdag Nybörjare`, target 5, min 4, max 6.
- Capacity: 2 blocks × max 6 = 12 ≥ 10 participants; 2 × target 5 = 10 == 10. Everyone fits at target size with no waitlist pressure.
- `ranking_points` spread cleanly across 325–920 (requested band: 300–950).

## Invariants tests can rely on

- **Mutual friend wish**: `p003` and `p004` each list the other in `wants_with_ids` (the only wish in this dataset). A feasible solve should place them in the same group when reasonably possible.
- No participant has any `unavailable_time_slot_ids` — no one can ever be infeasible on time grounds in this dataset.
- No `must_with_ids`/`not_with_ids`/`wants_coach_id` are set anywhere.
- All `previous_group_name`/`previous_group_level`/`manual_level_score` are empty — every participant's level comes purely from `ranking_points`.
- A feasible solve of this dataset should have `hardScore == 0` and `mediumScore == 0` (no one waitlisted).
