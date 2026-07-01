#!/usr/bin/env python3
"""Deterministic generator for the anonymized solver-regression datasets.

Builds three fixture datasets under ``test-data/datasets/`` (spec §22.2):

    small-10           10 participants,  2 timeslots, 2 coaches   — happy path
    coach-overlap-20   20 participants,  3 timeslots, 3 coaches   — a coach who also plays
    large-120          130 participants, 4 timeslots, 8 coaches   — waitlist pressure, rich wishes

Every value is synthetic: fake Swedish names (including å/ä/ö), fake @example.se emails,
no personnummer, no phone numbers. Nothing here is derived from or resembles real club data.

Usage (from the repo root)::

    python3 test-data/generator/generate.py            # (re)write the committed datasets
    python3 test-data/generator/generate.py --check     # verify committed datasets match (CI gate)

Determinism: each dataset is built from its own fresh ``random.Random(42)`` instance, consumed
in a fixed, linear order (see the ``build_*`` functions below), so re-running this script always
produces byte-identical output. Stdlib only — no third-party dependencies.

See ``test-data/generator/README.md`` for the full schema reference.
"""
from __future__ import annotations

import argparse
import csv
import filecmp
import random
import shutil
import sys
import tempfile
from collections import defaultdict
from pathlib import Path

# ---------------------------------------------------------------------------
# Fixed fake-name lists (deterministic; order matters for nothing but must never change)
# ---------------------------------------------------------------------------

FIRST_NAMES = [
    "Erik", "Anna", "Johan", "Maria", "Lars", "Karin", "Anders", "Eva", "Per", "Kristina",
    "Mikael", "Sara", "Fredrik", "Emma", "Daniel", "Linda", "Jan", "Sofia", "Thomas", "Helena",
    "Peter", "Ingrid", "Magnus", "Camilla", "Stefan", "Malin", "Henrik", "Jenny", "Björn", "Åsa",
    "Göran", "Örjan", "Håkan", "Åke", "Sölve", "Görel", "Kjell", "Ulla", "Nils", "Britt",
    "Gustav", "Elin", "Viktor", "Hanna", "Oskar", "Alma", "Axel", "Signe", "Isak", "Tuva",
]

LAST_NAMES = [
    "Andersson", "Johansson", "Karlsson", "Nilsson", "Eriksson", "Larsson", "Olsson", "Persson",
    "Svensson", "Gustafsson", "Pettersson", "Jonsson", "Jansson", "Hansson", "Bengtsson",
    "Jönsson", "Lindberg", "Jakobsson", "Magnusson", "Olofsson", "Lindqvist", "Lindgren", "Berg",
    "Fransson", "Axelsson", "Bergström", "Lundberg", "Lundgren", "Lundqvist", "Mattsson",
    "Berggren", "Sjöberg", "Söderberg", "Söderström", "Åström", "Öberg", "Näslund", "Lönnqvist",
    "Håkansson", "Bäckström", "Sandström", "Ekström", "Hedström", "Blomqvist", "Wikström",
    "Rosén", "Ström", "Åkesson", "Widmark", "Falk",
]

_FOLD = str.maketrans({"å": "a", "ä": "a", "ö": "o", "Å": "a", "Ä": "a", "Ö": "o"})


def fold(text: str) -> str:
    """ASCII-fold å/ä/ö and keep only [a-z0-9] (used for email local-parts only)."""
    lowered = text.translate(_FOLD).lower()
    return "".join(ch for ch in lowered if ch.isalnum())


def pid(n: int) -> str:
    return f"p{n:03d}"


def cid(n: int) -> str:
    return f"c{n:02d}"


def tid(n: int) -> str:
    return f"t{n:02d}"


def idnum(s: str) -> int:
    return int(s[1:])


def sort_ids(ids):
    return sorted(ids, key=idnum)


def join_ids(ids) -> str:
    return ";".join(sort_ids(ids))


def make_person(rng: random.Random):
    return rng.choice(FIRST_NAMES), rng.choice(LAST_NAMES)


def make_email(first: str, last: str, counter: dict) -> str:
    key = (fold(first), fold(last))
    counter[key] += 1
    return f"{key[0]}.{key[1]}{counter[key]}@example.se"


def csv_value(v) -> str:
    if v is None:
        return ""
    if isinstance(v, bool):
        return "1" if v else "0"
    if isinstance(v, (list, tuple)):
        return join_ids(v)
    return str(v)


def write_csv(path: Path, header, rows) -> None:
    with open(path, "w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f, lineterminator="\n")
        writer.writerow(header)
        for row in rows:
            writer.writerow([csv_value(row.get(col)) for col in header])


CONFIG_HEADER = ["key", "value"]
TIMESLOT_HEADER = ["id", "day_of_week", "start_time", "end_time", "label", "courts_per_slot"]
COACH_HEADER = [
    "id", "first_name", "last_name", "coach_level", "can_coach_min", "can_coach_max",
    "available_time_slot_ids", "unavailable_time_slot_ids", "max_groups_per_day",
    "max_groups_per_week", "also_plays_participant_id",
]
PARTICIPANT_HEADER = [
    "id", "first_name", "last_name", "email", "ranking_points", "previous_group_name",
    "previous_group_level", "manual_level_score", "priority", "unavailable_time_slot_ids",
    "preferred_time_slot_ids", "wants_with_ids", "must_with_ids", "not_with_ids",
    "wants_coach_id", "new_to_club", "needs_review",
]


def label_for(start_h, start_m, end_h, end_m) -> str:
    return f"Torsdag {start_h}.{start_m:02d}–{end_h}.{end_m:02d}"


# ---------------------------------------------------------------------------
# small-10: happy path, no waitlist pressure, one mutual friend wish
# ---------------------------------------------------------------------------

def build_small_10():
    rng = random.Random(42)
    email_counter = defaultdict(int)

    config = [
        ("category", "Torsdag Nybörjare"),
        ("target_size", "5"),
        ("min_size", "4"),
        ("max_size", "6"),
    ]

    timeslots = [
        {"id": tid(1), "day_of_week": 4, "start_time": "18:00", "end_time": "19:30",
         "label": label_for(18, 0, 19, 30), "courts_per_slot": 1},
        {"id": tid(2), "day_of_week": 4, "start_time": "19:30", "end_time": "21:00",
         "label": label_for(19, 30, 21, 0), "courts_per_slot": 1},
    ]

    # --- coaches (fixed order: c01 then c02) ---
    coach_specs = [
        {"num": 1, "coach_level": 480, "can_coach_min": 300, "can_coach_max": 650},
        {"num": 2, "coach_level": 750, "can_coach_min": 550, "can_coach_max": 950},
    ]
    coaches = []
    for spec in coach_specs:
        first, last = make_person(rng)
        coaches.append({
            "id": cid(spec["num"]), "first_name": first, "last_name": last,
            "coach_level": spec["coach_level"], "can_coach_min": spec["can_coach_min"],
            "can_coach_max": spec["can_coach_max"],
            "available_time_slot_ids": [tid(1), tid(2)], "unavailable_time_slot_ids": [],
            "max_groups_per_day": 2, "max_groups_per_week": 5,
            "also_plays_participant_id": "",
        })

    # --- participants p001..p010 ---
    names = [make_person(rng) for _ in range(10)]
    emails = [make_email(f, l, email_counter) for f, l in names]

    ranking_points = []
    for i in range(10):
        low = 300 + i * 65
        high = 300 + (i + 1) * 65
        ranking_points.append(rng.randint(low, high))

    friend_pair = tuple(sorted(rng.sample(range(1, 11), 2)))

    wants_with = defaultdict(list)
    wants_with[friend_pair[0]].append(pid(friend_pair[1]))
    wants_with[friend_pair[1]].append(pid(friend_pair[0]))

    participants = []
    for i in range(1, 11):
        first, last = names[i - 1]
        participants.append({
            "id": pid(i), "first_name": first, "last_name": last, "email": emails[i - 1],
            "ranking_points": ranking_points[i - 1], "previous_group_name": "",
            "previous_group_level": None, "manual_level_score": None, "priority": 3,
            "unavailable_time_slot_ids": [], "preferred_time_slot_ids": [],
            "wants_with_ids": wants_with[i], "must_with_ids": [], "not_with_ids": [],
            "wants_coach_id": "", "new_to_club": 0, "needs_review": 0,
        })

    notes = {
        "friend_pair": (pid(friend_pair[0]), pid(friend_pair[1])),
        "ranking_range": (min(ranking_points), max(ranking_points)),
    }
    readme = render_readme_small_10(config, timeslots, coaches, participants, notes)
    return {"config": config, "timeslots": timeslots, "coaches": coaches,
            "participants": participants, "readme": readme}


def render_readme_small_10(config, timeslots, coaches, participants, notes) -> str:
    cfg = dict(config)
    lines = []
    lines.append("# small-10\n")
    lines.append(
        "Happy-path fixture: a single, uncontroversial evening with plenty of room for "
        "everyone. Used as the basic sanity fixture — if this dataset doesn't solve to "
        "`hardScore == 0` with zero unassigned players, something fundamental is broken.\n"
    )
    lines.append("## Shape\n")
    lines.append(f"- {len(participants)} participants (`p001`..`p010`), {len(coaches)} coaches "
                  f"(`c01`, `c02`), {len(timeslots)} timeslots.")
    lines.append(f"- Group config: category `{cfg['category']}`, target {cfg['target_size']}, "
                  f"min {cfg['min_size']}, max {cfg['max_size']}.")
    total_courts = sum(t["courts_per_slot"] for t in timeslots)
    lines.append(f"- Capacity: {total_courts} blocks × max {cfg['max_size']} = "
                  f"{total_courts * int(cfg['max_size'])} ≥ 10 participants; "
                  f"{total_courts} × target {cfg['target_size']} = "
                  f"{total_courts * int(cfg['target_size'])} == 10. Everyone fits at target "
                  f"size with no waitlist pressure.")
    lines.append(f"- `ranking_points` spread cleanly across "
                  f"{notes['ranking_range'][0]}–{notes['ranking_range'][1]} "
                  f"(requested band: 300–950).")
    lines.append("\n## Invariants tests can rely on\n")
    a, b = notes["friend_pair"]
    lines.append(f"- **Mutual friend wish**: `{a}` and `{b}` each list the other in "
                  f"`wants_with_ids` (the only wish in this dataset). A feasible solve should "
                  f"place them in the same group when reasonably possible.")
    lines.append("- No participant has any `unavailable_time_slot_ids` — no one can ever be "
                  "infeasible on time grounds in this dataset.")
    lines.append("- No `must_with_ids`/`not_with_ids`/`wants_coach_id` are set anywhere.")
    lines.append("- All `previous_group_name`/`previous_group_level`/`manual_level_score` are "
                  "empty — every participant's level comes purely from `ranking_points`.")
    lines.append("- A feasible solve of this dataset should have `hardScore == 0` and "
                  "`mediumScore == 0` (no one waitlisted).")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# coach-overlap-20: a coach who also plays (§10.17 scenario)
# ---------------------------------------------------------------------------

def build_coach_overlap_20():
    rng = random.Random(42)
    email_counter = defaultdict(int)

    config = [
        ("category", "Torsdag Mix"),
        ("target_size", "5"),
        ("min_size", "4"),
        ("max_size", "7"),
    ]

    timeslots = [
        {"id": tid(1), "day_of_week": 4, "start_time": "16:30", "end_time": "18:00",
         "label": label_for(16, 30, 18, 0), "courts_per_slot": 1},
        {"id": tid(2), "day_of_week": 4, "start_time": "18:00", "end_time": "19:30",
         "label": label_for(18, 0, 19, 30), "courts_per_slot": 2},
        {"id": tid(3), "day_of_week": 4, "start_time": "19:30", "end_time": "21:00",
         "label": label_for(19, 30, 21, 0), "courts_per_slot": 1},
    ]
    all_slot_ids = [t["id"] for t in timeslots]

    # --- coach names (fixed order c01, c02, c03) ---
    coach_names = [make_person(rng) for _ in range(3)]

    # --- participant names p001..p020 ---
    names = [make_person(rng) for _ in range(20)]
    emails = [make_email(f, l, email_counter) for f, l in names]

    # --- ranking points, 20 bins across 250..950 ---
    ranking_points = []
    for i in range(20):
        low = 250 + i * 35
        high = 250 + (i + 1) * 35
        ranking_points.append(rng.randint(low, high))

    # --- also-plays participant ---
    also_plays_num = rng.choice(range(1, 21))
    also_plays_pid = pid(also_plays_num)

    # --- 5 partial-unavailable participants (never the also-plays participant) ---
    remaining = sorted(n for n in range(1, 21) if n != also_plays_num)
    partial_unavailable_nums = sorted(rng.sample(remaining, 5))
    unavailable = defaultdict(list)
    for n in partial_unavailable_nums:
        unavailable[n].append(rng.choice(all_slot_ids))

    # --- c03 available subset (2 of 3 slots) ---
    c03_available = sorted(rng.sample(all_slot_ids, 2), key=idnum)

    coaches = [
        {"id": cid(1), "first_name": coach_names[0][0], "last_name": coach_names[0][1],
         "coach_level": 500, "can_coach_min": 300, "can_coach_max": 700,
         "available_time_slot_ids": all_slot_ids, "unavailable_time_slot_ids": [],
         "max_groups_per_day": 2, "max_groups_per_week": 4, "also_plays_participant_id": ""},
        {"id": cid(2), "first_name": coach_names[1][0], "last_name": coach_names[1][1],
         "coach_level": 650, "can_coach_min": 400, "can_coach_max": 800,
         "available_time_slot_ids": all_slot_ids, "unavailable_time_slot_ids": [],
         "max_groups_per_day": 1, "max_groups_per_week": 2,
         "also_plays_participant_id": also_plays_pid},
        {"id": cid(3), "first_name": coach_names[2][0], "last_name": coach_names[2][1],
         "coach_level": 800, "can_coach_min": 500, "can_coach_max": 900,
         "available_time_slot_ids": c03_available, "unavailable_time_slot_ids": [],
         "max_groups_per_day": 2, "max_groups_per_week": 4, "also_plays_participant_id": ""},
    ]

    participants = []
    for i in range(1, 21):
        first, last = names[i - 1]
        participants.append({
            "id": pid(i), "first_name": first, "last_name": last, "email": emails[i - 1],
            "ranking_points": ranking_points[i - 1], "previous_group_name": "",
            "previous_group_level": None, "manual_level_score": None, "priority": 3,
            "unavailable_time_slot_ids": unavailable[i], "preferred_time_slot_ids": [],
            "wants_with_ids": [], "must_with_ids": [], "not_with_ids": [],
            "wants_coach_id": "", "new_to_club": 0, "needs_review": 0,
        })

    notes = {
        "also_plays_pid": also_plays_pid,
        "also_plays_coach": cid(2),
        "partial_unavailable_pids": [pid(n) for n in partial_unavailable_nums],
        "unavailable_map": {pid(n): sort_ids(unavailable[n]) for n in partial_unavailable_nums},
        "c03_available": c03_available,
    }
    readme = render_readme_coach_overlap_20(config, timeslots, coaches, participants, notes)
    return {"config": config, "timeslots": timeslots, "coaches": coaches,
            "participants": participants, "readme": readme}


def render_readme_coach_overlap_20(config, timeslots, coaches, participants, notes) -> str:
    cfg = dict(config)
    lines = []
    lines.append("# coach-overlap-20\n")
    lines.append(
        "Exercises the \"coach who also plays\" scenario (spec §22.2: *\"20 spelare, tränare "
        "som också spelar\"*, constraint 10.17 `coachCannotTrainAndCoachSameTime`). Also "
        "carries partial time-availability constraints and a non-uniform courts-per-slot "
        "schedule.\n"
    )
    lines.append("## Shape\n")
    lines.append(f"- {len(participants)} participants (`p001`..`p020`), {len(coaches)} coaches "
                  f"(`c01`..`c03`), {len(timeslots)} timeslots "
                  f"({timeslots[0]['label']} courts={timeslots[0]['courts_per_slot']}, "
                  f"{timeslots[1]['label']} courts={timeslots[1]['courts_per_slot']} "
                  f"[middle slot], {timeslots[2]['label']} "
                  f"courts={timeslots[2]['courts_per_slot']}).")
    lines.append(f"- Group config: category `{cfg['category']}`, target {cfg['target_size']}, "
                  f"min {cfg['min_size']}, max {cfg['max_size']}.")
    lines.append("\n## Invariants tests can rely on\n")
    lines.append(
        f"- **Also-plays scenario**: coach `{notes['also_plays_coach']}` has "
        f"`also_plays_participant_id = {notes['also_plays_pid']}`. Participant "
        f"`{notes['also_plays_pid']}` also appears as a normal row in `participants.csv` "
        f"(same person, two roles) and deliberately has **no** `unavailable_time_slot_ids`, and "
        f"`{notes['also_plays_coach']}` is `available_time_slot_ids` at all 3 slots — nothing in "
        f"the fixture trivially keeps them apart, so a correct solver must never schedule "
        f"`{notes['also_plays_coach']}` to coach at the same timeslot `{notes['also_plays_pid']}` "
        f"is playing."
    )
    lines.append(f"- Coach `c03` is only `available_time_slot_ids` for "
                  f"{', '.join(notes['c03_available'])} (2 of the 3 slots) — availability "
                  f"variety across coaches.")
    lines.append("- Partial time constraints: the following participants have exactly one "
                  "`unavailable_time_slot_ids` entry each (never all 3 — no one in this "
                  "dataset is permanently unplaceable):")
    for p in notes["partial_unavailable_pids"]:
        lines.append(f"  - `{p}`: unavailable at {', '.join(notes['unavailable_map'][p])}")
    lines.append("- No `wants_with_ids`/`must_with_ids`/`not_with_ids`/`wants_coach_id` are set "
                  "anywhere — this dataset's purpose is narrowly the also-plays + partial-"
                  "availability scenario (wish-logic variety is covered by `large-120`).")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# large-120: 130 participants, waitlist pressure, rich wish/priority/level data
# ---------------------------------------------------------------------------

def build_large_120():
    rng = random.Random(42)
    email_counter = defaultdict(int)
    N = 130

    config = [
        ("category", "Torsdag Herr"),
        ("target_size", "10"),
        ("min_size", "8"),
        ("max_size", "12"),
    ]

    timeslot_specs = [
        (1, 16, 30, 18, 0, 3),
        (2, 18, 0, 19, 30, 4),
        (3, 19, 30, 21, 0, 3),
        (4, 21, 0, 22, 30, 2),
    ]
    timeslots = [
        {"id": tid(n), "day_of_week": 4, "start_time": f"{sh:02d}:{sm:02d}",
         "end_time": f"{eh:02d}:{em:02d}", "label": label_for(sh, sm, eh, em),
         "courts_per_slot": courts}
        for (n, sh, sm, eh, em, courts) in timeslot_specs
    ]
    all_slot_ids = [t["id"] for t in timeslots]
    total_blocks = sum(t["courts_per_slot"] for t in timeslots)

    # --- 1. coach names ---
    coach_names = [make_person(rng) for _ in range(8)]

    # --- 2. participant names ---
    names = [make_person(rng) for _ in range(N)]
    emails = [make_email(f, l, email_counter) for f, l in names]

    # --- 3. true_skill per participant, id order ---
    true_skill = {i: rng.randint(200, 980) for i in range(1, N + 1)}

    # --- 4-7. special id sets, fixed draw order ---
    pool = list(range(1, N + 1))
    always_unavailable = sorted(rng.sample(pool, 3))

    remaining1 = sorted(set(pool) - set(always_unavailable))
    missing_ranking = sorted(rng.sample(remaining1, 10))

    remaining2 = sorted(set(remaining1) - set(missing_ranking))
    neither = sorted(rng.sample(remaining2, 5))

    remaining3 = sorted(set(remaining2) - set(neither))
    partial_unavailable = sorted(rng.sample(remaining3, 22))

    unavailable = defaultdict(list)
    for n in always_unavailable:
        unavailable[n] = list(all_slot_ids)
    for n in partial_unavailable:
        k = rng.choice([1, 2, 3])
        unavailable[n] = sorted(rng.sample(all_slot_ids, k), key=idnum)

    # --- 8. ranking_points ---
    no_ranking = set(missing_ranking) | set(neither)
    ranking_points = {i: (None if i in no_ranking else true_skill[i]) for i in range(1, N + 1)}

    # --- 9. previous_group_level / previous_group_name ---
    eligible_for_level = [i for i in range(1, N + 1) if i not in set(neither)]
    sorted_desc = sorted(eligible_for_level, key=lambda i: (-true_skill[i], i))
    previous_group_level = {}
    previous_group_name = {}
    for rank_index, i in enumerate(sorted_desc):
        level = 1 + (rank_index * 12) // len(sorted_desc)
        previous_group_level[i] = level
        previous_group_name[i] = f"Torsdag Herr {level} (Hösttermin 2025)"
    for i in neither:
        previous_group_level[i] = None
        previous_group_name[i] = ""

    # --- 10. two "impossible" mutual wants_with pairs (wildly different levels) ---
    normal_ids = [i for i in range(1, N + 1) if i not in no_ranking]
    normal_sorted_desc = sorted(normal_ids, key=lambda i: (-true_skill[i], i))
    top5 = normal_sorted_desc[:5]
    bottom5 = normal_sorted_desc[-5:]

    top_pool = list(top5)
    bottom_pool = list(bottom5)
    a1 = rng.choice(top_pool)
    top_pool.remove(a1)
    b1 = rng.choice(bottom_pool)
    bottom_pool.remove(b1)
    a2 = rng.choice(top_pool)
    b2 = rng.choice(bottom_pool)
    impossible_pairs = [(a1, b1), (a2, b2)]

    wants_with = defaultdict(list)
    used_in_wishes = set()
    for a, b in impossible_pairs:
        wants_with[a].append(pid(b))
        wants_with[b].append(pid(a))
        used_in_wishes.update([a, b])

    # --- 11. 7 more ordinary mutual pairs ---
    wish_pool = [i for i in range(1, N + 1)
                 if i not in set(always_unavailable) and i not in set(neither)
                 and i not in used_in_wishes]
    ordinary_mutual_flat = rng.sample(wish_pool, 14)
    ordinary_mutual_pairs = []
    for j in range(0, 14, 2):
        a, b = ordinary_mutual_flat[j], ordinary_mutual_flat[j + 1]
        wants_with[a].append(pid(b))
        wants_with[b].append(pid(a))
        ordinary_mutual_pairs.append((a, b))
        used_in_wishes.update([a, b])

    # --- 12. 6 one-directional wishes ---
    wish_pool2 = [i for i in range(1, N + 1)
                  if i not in set(always_unavailable) and i not in set(neither)
                  and i not in used_in_wishes]
    one_directional_flat = rng.sample(wish_pool2, 12)
    one_directional_pairs = []
    for j in range(0, 12, 2):
        truster, target = one_directional_flat[j], one_directional_flat[j + 1]
        wants_with[truster].append(pid(target))
        one_directional_pairs.append((truster, target))
        used_in_wishes.update([truster, target])

    # --- 13. 3 must_with pairs (symmetric) ---
    must_pool = [i for i in range(1, N + 1)
                 if i not in set(always_unavailable) and i not in set(neither)
                 and i not in used_in_wishes]
    must_flat = rng.sample(must_pool, 6)
    must_with = defaultdict(list)
    must_pairs = []
    for j in range(0, 6, 2):
        a, b = must_flat[j], must_flat[j + 1]
        must_with[a].append(pid(b))
        must_with[b].append(pid(a))
        must_pairs.append((a, b))
        used_in_wishes.update([a, b])

    # --- 14. 2 not_with pairs (symmetric) ---
    not_pool = [i for i in range(1, N + 1)
                if i not in set(always_unavailable) and i not in set(neither)
                and i not in used_in_wishes]
    not_flat = rng.sample(not_pool, 4)
    not_with = defaultdict(list)
    not_pairs = []
    for j in range(0, 4, 2):
        a, b = not_flat[j], not_flat[j + 1]
        not_with[a].append(pid(b))
        not_with[b].append(pid(a))
        not_pairs.append((a, b))

    # --- 15. coach wishes ---
    coach_ids = [cid(n) for n in range(1, 9)]
    coach_wish_nums = sorted(rng.sample(list(range(1, N + 1)), 10))
    wants_coach_id = {}
    for n in coach_wish_nums:
        wants_coach_id[n] = rng.choice(coach_ids)

    # --- 16. priority ---
    priority = {i: rng.choices([1, 2, 3], weights=[2, 5, 3])[0] for i in range(1, N + 1)}

    # --- 17. preferred_time_slot_ids for ~40 participants ---
    preferred_nums = sorted(rng.sample(list(range(1, N + 1)), 40))
    preferred = defaultdict(list)
    for n in preferred_nums:
        count = rng.choice([1, 1, 1, 2])
        preferred[n] = sorted(rng.sample(all_slot_ids, count), key=idnum)

    # --- coaches ---
    coaches = []
    for idx in range(1, 9):
        base = 200 + (idx - 1) * (780 // 7)
        first, last = coach_names[idx - 1]
        if idx in (5, 6):
            available = list(all_slot_ids)
            coach_unavailable = [rng.choice(all_slot_ids)]
        else:
            size = rng.choice([2, 3])
            available = sorted(rng.sample(all_slot_ids, size), key=idnum)
            coach_unavailable = []
        coaches.append({
            "id": cid(idx), "first_name": first, "last_name": last,
            "coach_level": base, "can_coach_min": max(100, base - 80),
            "can_coach_max": min(1000, base + 80),
            "available_time_slot_ids": available,
            "unavailable_time_slot_ids": coach_unavailable,
            "max_groups_per_day": rng.choice([1, 2, 3]),
            "max_groups_per_week": rng.choice([3, 4, 5, 6]),
            "also_plays_participant_id": "",
        })

    # --- participants ---
    participants = []
    for i in range(1, N + 1):
        first, last = names[i - 1]
        participants.append({
            "id": pid(i), "first_name": first, "last_name": last, "email": emails[i - 1],
            "ranking_points": ranking_points[i], "previous_group_name": previous_group_name[i],
            "previous_group_level": previous_group_level[i], "manual_level_score": None,
            "priority": priority[i],
            "unavailable_time_slot_ids": unavailable.get(i, []),
            "preferred_time_slot_ids": preferred.get(i, []),
            "wants_with_ids": wants_with.get(i, []),
            "must_with_ids": must_with.get(i, []),
            "not_with_ids": not_with.get(i, []),
            "wants_coach_id": wants_coach_id.get(i, ""),
            "new_to_club": 1 if i in set(neither) else 0,
            "needs_review": 1 if i in set(neither) else 0,
        })

    level_counts = defaultdict(int)
    for i in eligible_for_level:
        level_counts[previous_group_level[i]] += 1

    notes = {
        "total_blocks": total_blocks,
        "always_unavailable_pids": [pid(n) for n in always_unavailable],
        "partial_unavailable_count": len(partial_unavailable),
        "missing_ranking_pids": [pid(n) for n in missing_ranking],
        "neither_pids": [pid(n) for n in neither],
        "impossible_pairs": [(pid(a), pid(b), ranking_points[a], ranking_points[b])
                              for a, b in impossible_pairs],
        "ordinary_mutual_pairs": [(pid(a), pid(b)) for a, b in ordinary_mutual_pairs],
        "one_directional_pairs": [(pid(a), pid(b)) for a, b in one_directional_pairs],
        "must_pairs": [(pid(a), pid(b)) for a, b in must_pairs],
        "not_pairs": [(pid(a), pid(b)) for a, b in not_pairs],
        "coach_wishes": [(pid(n), wants_coach_id[n]) for n in coach_wish_nums],
        "priority_counts": {v: sum(1 for p in priority.values() if p == v) for v in (1, 2, 3)},
        "preferred_count": len(preferred_nums),
        "level_counts_str": ", ".join(f"level {lvl}: {cnt}"
                                       for lvl, cnt in sorted(level_counts.items())),
    }
    readme = render_readme_large_120(config, timeslots, coaches, participants, notes)
    return {"config": config, "timeslots": timeslots, "coaches": coaches,
            "participants": participants, "readme": readme}


def render_readme_large_120(config, timeslots, coaches, participants, notes) -> str:
    cfg = dict(config)
    target = int(cfg["target_size"])
    maxs = int(cfg["max_size"])
    n = len(participants)
    blocks = notes["total_blocks"]
    lines = []
    lines.append("# large-120\n")
    lines.append(
        f"The stress-test fixture (spec §22.2: *\"120 spelare, 12 grupper, flera "
        f"tidspreferenser\"*): {n} participants against {blocks} group-blocks of capacity, "
        f"rich friend/coach wishes, varied confidence in level data, and a handful of "
        f"participants who can never be placed. The directory keeps the historical "
        f"`large-120` name even though it now generates {n} participants — the extra 10 "
        f"create real waitlist/target-size pressure without making the dataset infeasible.\n"
    )
    lines.append("## Shape\n")
    slot_descr = ", ".join(f"{t['label']} courts={t['courts_per_slot']}" for t in timeslots)
    lines.append(f"- {n} participants (`p001`..`p{n:03d}`), {len(coaches)} coaches "
                  f"(`c01`..`c{len(coaches):02d}`), {len(timeslots)} timeslots "
                  f"({slot_descr}) = {blocks} group-blocks.")
    lines.append(f"- Group config: category `{cfg['category']}`, target {target}, "
                  f"min {cfg['min_size']}, max {maxs}.")
    lines.append(f"- Capacity: {blocks} × target {target} = {blocks * target} < {n} "
                  f"(target-size pressure by design); {blocks} × max {maxs} = "
                  f"{blocks * maxs} ≥ {n} (hard-feasible in principle, modulo the "
                  f"permanently-unavailable participants below).")
    lines.append("\n## Invariants tests can rely on\n")
    lines.append(
        f"- **Waitlist certainties**: {', '.join(notes['always_unavailable_pids'])} have "
        f"`unavailable_time_slot_ids` covering all 4 timeslots. They can never be placed in "
        f"any feasible solve — a correct solver leaves them unassigned (medium-score cost "
        f"only, never a hard violation)."
    )
    total_unavailable = len(notes["always_unavailable_pids"]) + notes["partial_unavailable_count"]
    lines.append(
        f"- **Partial time constraints**: {notes['partial_unavailable_count']} further "
        f"participants have 1–3 (never 4) `unavailable_time_slot_ids` entries, for a total of "
        f"{total_unavailable} participants with any time restriction."
    )
    lines.append(
        f"- **Missing ranking, has previous level**: {', '.join(notes['missing_ranking_pids'])} "
        f"have empty `ranking_points` but a populated `previous_group_name`/"
        f"`previous_group_level` — exercises the level-confidence fallback chain "
        f"(ranking → previous group)."
    )
    lines.append(
        f"- **Neither** (low confidence): {', '.join(notes['neither_pids'])} have empty "
        f"`ranking_points`, `previous_group_name` and `previous_group_level`; all five also "
        f"have `new_to_club = 1` and `needs_review = 1`."
    )
    lines.append(
        f"- `previous_group_name` follows the pattern `\"Torsdag Herr <level> (Hösttermin "
        f"2025)\"`, consistently mapped to `previous_group_level` 1–12 "
        f"(counts per level: {notes['level_counts_str']})."
    )
    lines.append("- **Friend wishes** (`wants_with_ids`, 15 relationships total):")
    lines.append(
        f"  - 2 **impossible** mutual pairs (levels differ wildly — satisfying them would "
        f"fight level-balance): "
        + "; ".join(f"`{a}` (ranking {ra}) ↔ `{b}` (ranking {rb})"
                     for a, b, ra, rb in notes["impossible_pairs"])
    )
    lines.append("  - 7 further ordinary mutual pairs: "
                  + ", ".join(f"`{a}`↔`{b}`" for a, b in notes["ordinary_mutual_pairs"]))
    lines.append("  - 6 one-directional wishes (truster → target, not reciprocated): "
                  + ", ".join(f"`{a}`→`{b}`" for a, b in notes["one_directional_pairs"]))
    lines.append(
        "- **Must-with pairs** (`must_with_ids`, hard, symmetric): "
        + ", ".join(f"`{a}`↔`{b}`" for a, b in notes["must_pairs"])
    )
    lines.append(
        "- **Not-with pairs** (`not_with_ids`, hard, symmetric): "
        + ", ".join(f"`{a}`↔`{b}`" for a, b in notes["not_pairs"])
    )
    lines.append(
        "- **Coach wishes** (`wants_coach_id`, 10 participants): "
        + ", ".join(f"`{p}`→`{c}`" for p, c in notes["coach_wishes"])
    )
    lines.append(
        f"- **Priority** (1–3) distribution: {notes['priority_counts'][1]} at priority 1, "
        f"{notes['priority_counts'][2]} at priority 2, {notes['priority_counts'][3]} at "
        f"priority 3."
    )
    lines.append(
        f"- **Time preferences** (`preferred_time_slot_ids`): {notes['preferred_count']} "
        f"participants have 1–2 preferred slots."
    )
    lines.append(
        "- No coach has `also_plays_participant_id` set (that scenario is "
        "`coach-overlap-20`'s job); coaches `c05`/`c06` are the only ones using "
        "`unavailable_time_slot_ids` (all others express availability purely via "
        "`available_time_slot_ids`)."
    )
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# I/O + CLI
# ---------------------------------------------------------------------------

def build_all():
    return {
        "small-10": build_small_10(),
        "coach-overlap-20": build_coach_overlap_20(),
        "large-120": build_large_120(),
    }


def write_dataset(base_dir: Path, name: str, data: dict) -> list:
    out_dir = base_dir / name
    out_dir.mkdir(parents=True, exist_ok=True)
    written = []

    config_rows = [{"key": k, "value": v} for k, v in data["config"]]
    write_csv(out_dir / "config.csv", CONFIG_HEADER, config_rows)
    written.append(out_dir / "config.csv")

    write_csv(out_dir / "timeslots.csv", TIMESLOT_HEADER, data["timeslots"])
    written.append(out_dir / "timeslots.csv")

    write_csv(out_dir / "coaches.csv", COACH_HEADER, data["coaches"])
    written.append(out_dir / "coaches.csv")

    write_csv(out_dir / "participants.csv", PARTICIPANT_HEADER, data["participants"])
    written.append(out_dir / "participants.csv")

    readme_path = out_dir / "README.md"
    with open(readme_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(data["readme"])
    written.append(readme_path)

    return written


def compare_trees(generated_dir: Path, committed_dir: Path) -> list:
    """Return a list of human-readable drift messages; empty list means identical."""
    problems = []
    gen_files = sorted(p.relative_to(generated_dir).as_posix()
                        for p in generated_dir.rglob("*") if p.is_file())
    committed_files = sorted(p.relative_to(committed_dir).as_posix()
                              for p in committed_dir.rglob("*") if p.is_file())

    gen_set, committed_set = set(gen_files), set(committed_files)
    for extra in sorted(gen_set - committed_set):
        problems.append(f"MISSING from committed datasets: {extra}")
    for missing in sorted(committed_set - gen_set):
        problems.append(f"EXTRA in committed datasets (generator no longer produces it): "
                         f"{missing}")

    for rel in sorted(gen_set & committed_set):
        gen_path = generated_dir / rel
        committed_path = committed_dir / rel
        if not filecmp.cmp(gen_path, committed_path, shallow=False):
            problems.append(f"CONTENT DRIFT: {rel}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                         help="regenerate to a temp dir and diff against committed datasets; "
                              "exit non-zero on drift (used by CI)")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    datasets_dir = repo_root / "test-data" / "datasets"

    data = build_all()

    if args.check:
        tmp_root = Path(tempfile.mkdtemp(prefix="gruppindelning-testdata-check-"))
        try:
            for name, d in data.items():
                write_dataset(tmp_root, name, d)
            problems = compare_trees(tmp_root, datasets_dir)
        finally:
            shutil.rmtree(tmp_root, ignore_errors=True)

        if problems:
            print("Generated test-data DRIFT detected:")
            for p in problems:
                print(f"  - {p}")
            print("\nRun `python3 test-data/generator/generate.py` and commit the result.")
            return 1

        print("OK: committed test-data/datasets/ matches the generator output exactly.")
        return 0

    for name, d in data.items():
        written = write_dataset(datasets_dir, name, d)
        print(f"{name}: wrote {len(written)} files "
              f"({len(d['participants'])} participants, {len(d['coaches'])} coaches, "
              f"{len(d['timeslots'])} timeslots)")

    return 0


if __name__ == "__main__":
    sys.exit(main())
