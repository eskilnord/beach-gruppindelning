# Anonymized test-dataset generator

`generate.py` deterministically builds the three committed solver-regression datasets under
`test-data/datasets/` (spec §22.2):

| Dataset | Participants | Timeslots | Coaches | Scenario |
|---|---|---|---|---|
| `small-10` | 10 | 2 | 2 | happy path — everyone fits, no waitlist pressure |
| `coach-overlap-20` | 20 | 3 | 3 | a coach who also plays (§10.17) + partial availability |
| `large-120` | 130 | 4 | 8 | waitlist pressure, rich wishes, varied level-data confidence |

Every name, email, and id in these datasets is synthetic. **Nothing is derived from real club
data** — see "Anonymization rules" below.

## How to run

From the repo root (`app/`):

```bash
python3 test-data/generator/generate.py            # (re)generate all three datasets in place
python3 test-data/generator/generate.py --check     # verify committed datasets match the
                                                     # generator output; exits non-zero on drift
                                                     # (this is what CI runs)
```

No arguments select a subset — the script always (re)builds all three datasets together, since
they're small and this keeps the "committed output == generator output" invariant simple to
state and check.

## Determinism guarantee

- Each dataset is built from its **own** fresh `random.Random(42)` instance (not a module-level
  shared one), consumed in a fixed, linear order within that dataset's `build_*` function. This
  means each dataset's output is independent of the others and of the order `build_all()` calls
  them in.
- No wall-clock, environment, filesystem-iteration-order, or hash-seed-dependent values are ever
  used. Every multi-value field (semicolon-separated id list) is explicitly sorted before being
  written — raw `set`/`dict` iteration order is never emitted directly, so output is unaffected by
  Python's per-process `PYTHONHASHSEED` string-hash randomization.
- Output files are UTF-8 **without a BOM**, comma-separated, with **LF** line endings only (written
  via `csv.writer(..., lineterminator="\n")` on a file opened with `newline=""`).
- Consequence: running `generate.py` twice produces **byte-identical** files every time, on any
  machine, in any Python 3 interpreter. This was verified by running the generator twice and
  diffing the output (`diff -r`), and is what `--check` automates as a CI gate.

## `--check` semantics

Regenerates all three datasets into a fresh temp directory, then compares that tree file-by-file
against the committed `test-data/datasets/`. Reports any missing file, extra file, or byte-level
content drift, and exits `1` if anything differs (`0` if the committed datasets exactly match what
the generator currently produces). Intended to run in CI so committed fixtures can never silently
drift from the code that generates them.

## Anonymization rules

- **Names**: drawn from two fixed lists hardcoded in `generate.py` — `FIRST_NAMES` (50 entries)
  and `LAST_NAMES` (50 entries) — both deliberately include Swedish names with å/ä/ö
  (e.g. `Åsa`, `Björn`, `Söderström`, `Sjöberg`, `Håkansson`) alongside plain-ASCII Swedish names.
  Names are picked via `rng.choice`, so the same first/last name pair can recur across
  participants — this is intentional (real membership data has repeated names too) and is
  disambiguated in the email, not suppressed.
- **Emails**: `<first>.<last><N>@example.se`, where `<first>`/`<last>` are the person's first/last
  name **ASCII-folded** (å→a, ä→a, ö→o, then lowercased, non-alphanumeric characters stripped) and
  `<N>` is a per-(first,last) running counter starting at 1, so repeated name pairs still get
  unique emails (e.g. `asa.svensson1@example.se`, `asa.svensson2@example.se`).
- **No personnummer, ever, anywhere** — no field resembling `YYMMDD-XXXX` / `YYYYMMDD-XXXX` exists
  in any generated file (verified as part of this generator's acceptance checks and independently
  enforced by `scripts/check-no-confidential.sh`).
- **No phone numbers** — the schema has no phone column and no generated value is a phone number.
- IDs (`p001`, `c01`, `t01`, ...) are synthetic sequence numbers with no relationship to any real
  membership/registration numbering.

## Schema reference

All four CSVs per dataset are comma-separated, UTF-8, no BOM, LF line endings, header row first,
data rows sorted by `id` (config.csv has no `id` column; its 4 rows are in a fixed logical order).
Multi-value columns use `;` as separator, are always sorted, and are the empty string when empty.
Booleans are `0`/`1`. Missing numeric values are the empty string (never `NaN`/`null`/`None`).

### `config.csv`

`key,value` — exactly 4 rows, in this order:

| key | meaning |
|---|---|
| `category` | free-text group-category label for this activity plan (e.g. `Torsdag Herr`) |
| `target_size` | preferred group size |
| `min_size` | minimum acceptable group size |
| `max_size` | maximum acceptable group size |

### `timeslots.csv`

`id,day_of_week,start_time,end_time,label,courts_per_slot`

| column | type | notes |
|---|---|---|
| `id` | string | `t01`, `t02`, ... zero-padded, stable |
| `day_of_week` | int 1-7 | ISO day of week; always `4` (Thursday) in these fixtures |
| `start_time` / `end_time` | `HH:MM` (24h) | machine-readable time-of-day |
| `label` | string | Swedish display label, e.g. `Torsdag 18.00–19.30` (period notation + en dash) |
| `courts_per_slot` | int | number of concurrent courts/group-blocks available in this slot |

### `coaches.csv`

`id,first_name,last_name,coach_level,can_coach_min,can_coach_max,available_time_slot_ids,`
`unavailable_time_slot_ids,max_groups_per_day,max_groups_per_week,also_plays_participant_id`

| column | type | notes |
|---|---|---|
| `id` | string | `c01`, `c02`, ... |
| `first_name` / `last_name` | string | fake name, may contain å/ä/ö |
| `coach_level` | int | same 0–1000 scale as `ranking_points` |
| `can_coach_min` / `can_coach_max` | int | level band this coach is comfortable coaching |
| `available_time_slot_ids` | `;`-list of timeslot ids | slots the coach can coach at |
| `unavailable_time_slot_ids` | `;`-list of timeslot ids | explicit blackout slots (may be empty even when `available_time_slot_ids` is also populated — the two columns are both exercised across the fixtures, not always redundant) |
| `max_groups_per_day` / `max_groups_per_week` | int | coaching-load caps |
| `also_plays_participant_id` | participant id or empty | set only in `coach-overlap-20`, where one coach is also a participant (§10.17 scenario) |

### `participants.csv`

`id,first_name,last_name,email,ranking_points,previous_group_name,previous_group_level,`
`manual_level_score,priority,unavailable_time_slot_ids,preferred_time_slot_ids,wants_with_ids,`
`must_with_ids,not_with_ids,wants_coach_id,new_to_club,needs_review`

| column | type | notes |
|---|---|---|
| `id` | string | `p001`, `p002`, ... zero-padded 3 digits, stable |
| `first_name` / `last_name` | string | fake name, may contain å/ä/ö |
| `email` | string | `<fold(first)>.<fold(last)><N>@example.se` |
| `ranking_points` | int or empty | primary level signal, 0–1000-ish scale; empty when unknown |
| `previous_group_name` | string or empty | e.g. `Torsdag Herr 3 (Hösttermin 2025)`, consistently derived from `previous_group_level` |
| `previous_group_level` | int 1-12 or empty | fallback level signal when `ranking_points` is empty |
| `manual_level_score` | int or empty | manual override level; unused (always empty) in these fixtures |
| `priority` | int | waitlist-shedding priority; higher = costlier to leave unassigned |
| `unavailable_time_slot_ids` | `;`-list of timeslot ids | slots this participant cannot attend |
| `preferred_time_slot_ids` | `;`-list of timeslot ids | soft time preference |
| `wants_with_ids` | `;`-list of participant ids | soft "wants to play with" wishes (not necessarily reciprocated) |
| `must_with_ids` | `;`-list of participant ids | hard "must be in the same group as" (always symmetric in these fixtures) |
| `not_with_ids` | `;`-list of participant ids | hard "must not be in the same group as" (always symmetric) |
| `wants_coach_id` | coach id or empty | soft "wants this coach" wish |
| `new_to_club` | 0/1 | |
| `needs_review` | 0/1 | flags low-confidence level data |

## Per-dataset invariants

Each dataset directory has its own generated `README.md` documenting the exact ids involved in
that dataset's invariants (e.g. which participants can never be placed, which pairs form the
"impossible" friend wishes, etc.) — those files are produced by `generate.py` from the same
in-memory data used to write the CSVs, so they can never drift out of sync with the fixtures they
describe.
