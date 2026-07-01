# coach-overlap-20

Exercises the "coach who also plays" scenario (spec §22.2: *"20 spelare, tränare som också spelar"*, constraint 10.17 `coachCannotTrainAndCoachSameTime`). Also carries partial time-availability constraints and a non-uniform courts-per-slot schedule.

## Shape

- 20 participants (`p001`..`p020`), 3 coaches (`c01`..`c03`), 3 timeslots (Torsdag 16.30–18.00 courts=1, Torsdag 18.00–19.30 courts=2 [middle slot], Torsdag 19.30–21.00 courts=1).
- Group config: category `Torsdag Mix`, target 5, min 4, max 7.

## Invariants tests can rely on

- **Also-plays scenario**: coach `c02` has `also_plays_participant_id = p010`. Participant `p010` also appears as a normal row in `participants.csv` (same person, two roles) and deliberately has **no** `unavailable_time_slot_ids`, and `c02` is `available_time_slot_ids` at all 3 slots — nothing in the fixture trivially keeps them apart, so a correct solver must never schedule `c02` to coach at the same timeslot `p010` is playing.
- Coach `c03` is only `available_time_slot_ids` for t01, t02 (2 of the 3 slots) — availability variety across coaches.
- Partial time constraints: the following participants have exactly one `unavailable_time_slot_ids` entry each (never all 3 — no one in this dataset is permanently unplaceable):
  - `p003`: unavailable at t02
  - `p004`: unavailable at t03
  - `p005`: unavailable at t02
  - `p008`: unavailable at t01
  - `p014`: unavailable at t02
- No `wants_with_ids`/`must_with_ids`/`not_with_ids`/`wants_coach_id` are set anywhere — this dataset's purpose is narrowly the also-plays + partial-availability scenario (wish-logic variety is covered by `large-120`).
