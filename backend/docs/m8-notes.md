# M8 implementation notes (saved plans + export + conflicts — backend half)

Design decisions and deviations made while implementing the backend half of M8 (saved-plan
lifecycle, resource-usage materialization on save, ConflictService saved-plan awareness, export
engine incl. anonymized export, OpenAPI fixes from M7). Primary contract: docs/plan.md M8 row +
Privacy corrections; `../../utkast-kravspec.txt` §14 (status flow §14.2, låst plan §14.3, användning
§14.4), §20 (export, §20.3 comments off by default), §21.3 (anonymized export), §23.6 + §23.9
(acceptance); `docs/design/02-product-data-ui.md` §1 (saved_plan schema — tables from V6) + §7
(export design); `backend/docs/m6b-notes.md` (the minimal SavedPlanService this milestone
productionizes). No new Flyway migration — V6's `saved_plan`/`saved_plan_resource_usage` schema
already carries everything M8 needs.

## Scope actually implemented

- **Saved-plan lifecycle** (`savedplan/` + `api/SavedPlanController`): `POST
  /api/plans/{planId}/saved-plans {name}` snapshots the plan's CURRENT state into
  `saved_plan.snapshot_json` (spec §14.1: groups with time/court labels, players per group, coaches
  per group, waitlist with priority, effective constraint weights via `ConstraintWeightService`,
  locks, latest run's score + id) — EXCLUDING `importedComment`/`internalNote` (never read by any
  snapshot builder; `SavedPlanSnapshotLeakTest`); `GET` list + `GET` one (with `snapshot_json`
  parsed into a plain object tree, `SavedPlanDetailView`); `PATCH {status}` with legal-transition
  validation (`SavedPlanLifecycle`); `DELETE` for `draft`/`saved` only.
- **Resource-usage materialization on SAVE** (the red-team correction): `SavedPlanService
  .materialize` writes `saved_plan_resource_usage` rows (person/coach × frozen time × court) on
  EVERY save, any status — this was already M6b's behavior for the usage rows; M8 adds the full
  snapshot around it. Re-save = a brand-new `saved_plan` row with its own fresh usage set (see
  "Deviation 1").
- **Cross-plan solver blocking source unchanged**: `SolverInputAssembler.collectSavedPlanUsages`
  still reads `SavedPlanRepository.findLockedInSeasonExcludingPlan` (`status='locked'` ONLY) —
  verified and kept per the task brief and spec §14.3's literal "En låst plan ska kunna användas som
  hard constraint". `SavedPlanUsageAssemblyTest`'s
  `aSavedPlanNotYetLockedNeverBlocksAnything` still guards this.
- **ConflictService saved-plan awareness** (spec §19.2): previously read ONLY live current
  assignments; now, per plan, it picks exactly ONE usage source — the plan's most recently created
  non-`archived` `saved_plan` snapshot's usage rows, else (never-saved or archived-only) the live
  assignments (see "Deviation 2").
- **Export engine** (`exporter/` + `api/ExportController`):
  - `GET /api/plans/{planId}/export?format=xlsx|csv&layout=grouped|flat&includeComments=false|true&run={runId?}`
    — grouped council-layout xlsx (`GroupedXlsxWriter`: per group, column A stacks
    name/Ordning/Tid+Bana/Tränare, player header row `Namn|Ranking|Nivåscore|Tidigare
    grupp[|Kommentar]|Varningar`, player rows, `Antal spelare: N`, blank separator; trailing
    `Kölista` section with priority + `Antal på kölista: N`; bold group names + bordered header
    rows, minimal per the brief), flat layout (`FlatExporter`: one row per player `Grupp, Tid, Bana,
    Tränare, Spelare, Ranking, Nivåscore, Tidigare grupp[, Kommentar], Varningar`, plus one row per
    waitlisted participant with `Grupp="Kölista"`), csv `;`-delimited UTF-8 with BOM
    (`TabularSheetWriter`, shared with the anonymized export).
  - `includeComments` defaults `false` AT THE CONTROLLER (`@RequestParam(defaultValue="false")`,
    spec §20.3); when false the comment is never even read into `ExportPlayer.comment`
    (`ExportDataAssembler`) — structural absence, verified at byte level (`CommentLeakExportTest`
    unzips the xlsx and scans every decompressed entry, incl. `xl/sharedStrings.xml`; plus a
    POSITIVE control proving the scanner detects the string when opted in).
  - `GET /api/plans/{planId}/export/anonymized?format=xlsx|csv` (spec §21.3): names →
    `Spelare NNN`/`Tränare NN` (stable per plan state — assigned in the repositories' deterministic
    UUIDv7 id order), emails/phones/comments never read, wish-structure re-expressed over the SAME
    anonymized ids (`Vill spela med`/`Måste spela med`/`Får ej spela med` columns), ranking/level/
    group-shape/time labels/courts preserved. `AnonymizedExportLeakTest` asserts no real
    name/email/phone from the DB appears in the output bytes.
- **OpenAPI fixes (M7 findings)**: `AssignmentController.MoveRequest` → `ApplyMoveRequest`;
  `WhatIfController.MoveRequest` → `WhatIfMoveRequest` (springdoc keys `components.schemas` by
  simple class name; the two same-named records clobbered each other in `/v3/api-docs`).
  `SolveController#solve` now returns `ResponseEntity<SolveResponse>` — ONE record with nullable
  greedy-only fields (`score`, `hardViolations`, `feasible` are `null` for the async 202 branch,
  always set for the synchronous GREEDY 200 branch) instead of the schema-less
  `ResponseEntity<?>` picking between two ad hoc records. `OpenApiSchemaTest` (dev profile, same
  unauthenticated path the frontend typegen uses) asserts both renamed schemas exist with their own
  distinct fields, `MoveRequest` is gone, `SolveResponse` exists, and the solve operation's
  responses actually reference it.

## Deviations / notable decisions (each with reasoning)

### 1. Re-save creates a NEW `saved_plan` row; "refresh" is by construction, not by UPDATE
The task says "refresh on re-save". Two readings: (a) update the existing `saved_plan` row in place,
(b) each save is a new versioned snapshot. Implemented (b): every `POST .../saved-plans` inserts a
fresh row + fresh usage set. Reasons: V6's own schema comment frames a `saved_plan` as
"immutable-in-spirit... a later edit to the live plan does not retroactively change what an
already-locked OTHER plan blocks" — in-place refresh of a LOCKED row would do exactly that
retroactive mutation; §14.2's status flow is one-directional (an updated-in-place `locked` row
re-becoming `saved` has no legal transition); and versioned saves match the council's real habit of
keeping named variants. "Refresh" still genuinely happens: the new row's snapshot + usage rows are
built entirely from CURRENT persisted state (`SavedPlanControllerIntegrationTest
.reSavingRefreshesUsageRowsFromCurrentState` asserts the second save reflects the moved player while
the first save's rows stay frozen). Older `saved`-status rows can be `DELETE`d when they become
noise.

### 2. ConflictService picks ONE source per plan (latest non-archived snapshot XOR live), never both
The task's wording ("ConflictService reads ALL statuses — verify it picks up saved plans now...
season view should show conflicts between saved plans AND live plan state") could be read as
"sweep snapshot usages IN ADDITION TO live usages". Deliberately not done: immediately after a save
the two sources are identical, so summing them would double-report every real conflict — the exact
duplicate-conflict failure mode m6b-notes.md already documents once (the court sweep's "11
duplicates for one real conflict"). Instead each plan contributes its most recently created
non-`archived` snapshot's frozen usage rows (ANY status — unlike in-solver blocking's locked-only
rule), falling back to live assignments when no snapshot exists. This still satisfies both halves of
the task's sentence: saved/locked plans are now visible to the season view via what was actually
recorded (previously a locked plan whose live rows were later cleared silently vanished from the
report), and never-saved plans keep warning early from live state. `archived` falls back to live on
purpose (an explicitly retired snapshot must not keep resurrecting stale schedule data);
`ConflictServiceSavedPlanTest` covers frozen-snapshot-beats-diverged-live, no-duplication, and the
archived fallback. The pre-existing `ConflictServiceTest` (live-only fixtures) passes unchanged.

### 3. `saved_plan_resource_usage` has no group granularity → snapshot court usages are deduped (courtId, TimeKey) pairs
The live court sweep builds one usage per group-with-a-court (m6b's dedup fix). A snapshot's usage
rows are person-level by V6's deliberate flattening, so the snapshot-sourced court sweep dedupes
person rows to distinct `(courtId, TimeKey)` pairs — recovering exactly the one-per-group-per-court
shape (every member of a group shares the same court+time). Group NAME is `null` for
snapshot-sourced usages in the report (the flattened row simply doesn't carry it) — same rendering
path as the live court sweep's already-null person fields.

### 4. `layout=grouped&format=csv` is a 400, not a silently-flattened csv
The grouped layout IS a spreadsheet shape (stacked metadata column, blank separators, a second
section) — a csv rendering of it would be a lie about what Excel shows. Explicit
`BadRequestException` ("use layout=flat for csv") rather than guessing; the design doc's own §7 only
ever pairs "xlsx grouped" and "csv flat".

### 5. `run` query parameter accepted but unused (documented hook)
Spec §20.2's "Eventuella varningar" is computed from persisted domain data (`ExportWarnings`:
manual-review flag, under/over group size, missing coach) so export works with or without any solve.
The `run` parameter exists in the endpoint shape per the task, reserved for a future per-run
explanation cross-reference; wiring warnings to a specific run's `ScoreAnalysis` would have made
export fail on never-solved plans for no §20 benefit.

### 6. `internalNote` is NEVER exported, even with `includeComments=true`
Spec §20.3's opt-in checkbox is "Inkludera kommentarer i export", which in the council's own file
maps to the imported registration comment. The council-internal note (`internalNote`, spec §21.2
"visas endast internt") has no export use case in the spec at all, so the opt-in emits ONLY
`importedComment`. Asserted explicitly in `CommentLeakExportTest`'s positive control (comment
present, note still absent). If a future product decision wants internal notes exportable it is a
one-line change in `ExportDataAssembler` + a conscious spec amendment.

### 7. Anonymized export deliberately does NOT reuse `ExportDataAssembler`
`ExportData`/`ExportPlayer` carry real display names by design. `AnonymizedExportService` does its
own from-scratch repository pass and never loads `Person` at all — leak-freedom by construction
(the type that could carry a name is absent from the class), not by remembering to filter.
`ExportWarnings` IS shared (structural statements only, no names).

### 8. `SavedPlanService.materialize(planId, name, status)` stays public with an explicit status
The REST path only ever creates `status='saved'` ("spara plan" IS the save action; `draft` has no
producer in this MVP and `locked` is reached via PATCH). The direct `materialize` entry point with
an explicit status is kept because M6b's tests (`SavedPlanUsageAssemblyTest`,
`ConflictServiceSavedPlanTest`, cross-plan fixtures) legitimately need to construct
locked/archived snapshots without walking the PATCH flow, and it remains the single code path both
callers share (no drift between "test materialization" and "real materialization" — it IS the same
method the controller uses).

### 9. Status transition matrix: `archived` from `saved`/`locked`/`published`; strict forward steps otherwise
Spec §14.2 lists the five statuses but not the edges. Encoded (in `SavedPlanLifecycle`, unit-tested
as a full legal/illegal matrix): `draft→saved`, `saved→locked`, `locked→published`, plus
`{saved,locked,published}→archived`; `archived` terminal; no backward moves (un-lock/un-publish is
NOT a transition — a mutable variant is achieved by re-saving, and premature locks are handled by
`archived` + a fresh save, keeping "what a locked plan blocked" historically intact). Same-status
PATCH = idempotent 200 no-op; unknown status = 400; known-but-illegal = 409 (`ConflictException`,
matching the codebase's existing 409 semantics).

## Bug found during E2E (pre-existing, fixed in this milestone)

**Solver placements were silently DROPPED on writeback for any participant created via `POST
/api/plans/{planId}/participants`.** Only the import-commit path ever created the "awaiting
placement" `player_assignment` row (docs/design/02-product-data-ui.md §2 step 8);
`SolveCoordinator#persistResult`'s `updateGroupAndSource` is an `UPDATE ... WHERE
participant_profile_id = ? AND locked = 0` — with no row to update, the write was a no-op and the
in-memory solve result for that participant simply vanished (run score recorded `-800 medium` = 3
unassigned while the DB showed all 130 unplaced). Never caught before because every automated test
seeds participants through `TestDatasetLoader`, which explicitly calls `insertImportedIfAbsent` —
only the M8 jar E2E, which seeds 130 participants through the REAL REST endpoint for the first
time, exposed it. Two independent fixes:

1. `ParticipantProfileController#create` now calls `playerAssignmentRepository
   .insertImportedIfAbsent` (REST-created participants get the same `source=imported`,
   `group_id=NULL` row the import commit creates).
2. `SolveCoordinator#persistResult` inserts-if-absent before every `updateGroupAndSource` — the
   same defensive convention `AssignmentController#move` has used since M7, covering any
   historical row-less participant in an existing DB.

Regression test: `api.ParticipantAssignmentRowRegressionTest` (2) — REST create seeds the row; a
deliberately row-less profile still gets its greedy/solver placement persisted.

## M8 review fixes (Opus review round, "MERGE WITH FIXES" — privacy axis verdict CLEAN)

1. **Finding 1 — archived-newest snapshot masked a still-blocking older one.** `ConflictService
   .effectiveSnapshotFor` filtered `archived` AFTER the SQL `LIMIT 1`
   (`findLatestByActivityPlanId` picked the plain latest row): the sequence "lock v1 → save v2 →
   archive v2" made the season view fall back to LIVE state while v1 — still locked, still blocking
   other plans' solves via `findLockedInSeasonExcludingPlan` — silently vanished from the report.
   Fixed by replacing the repository method with `findLatestNonArchivedByActivityPlanId`
   (`status != 'archived'` inside the query, before `LIMIT 1`), so archiving a snapshot re-exposes
   the next-newest non-archived one; only a plan whose EVERY snapshot is archived falls back to
   live. Test: `ConflictServiceSavedPlanTest#archivingTheNewestSnapshotReExposesTheOlderStillLockedOne`
   (the exact lock-v1/save-v2/archive-v2 sequence).
2. **Finding 2 — duplicate blocking facts from multiple locked snapshots of one plan.** Since every
   M8 save is a NEW versioned row, "lock v1, re-save, lock v2" gave `SolverInputAssembler
   .collectSavedPlanUsages` two locked snapshots each contributing an identical
   `SavedPlanResourceUsage` fact — doubling the hard-score magnitude of one real clash and
   duplicating its explanation justifications. Fixed with dedup on the canonical key
   `(usageType, personId, courtId, TimeKey)` (a `LinkedHashMap.putIfAbsent`; provenance fields
   `sourcePlanName`/`sourceDetail` excluded from the key; first occurrence wins, deterministic via
   the queries' `ORDER BY id`). Also collapses the redundant two-different-plans-same-commitment
   case — one fact fully expresses "this resource is taken at this time". Test:
   `SavedPlanUsageAssemblyTest#twoLockedSnapshotsOfTheSamePlanProduceNoDuplicateBlockingFacts`.
3. **Finding 4 — leak-test hardening.** (a) Every person in the leak fixtures now carries a planted
   phone, a personnummer-LIKE `external_id` (constructed at RUNTIME by concatenation — a committed
   `DDMMYY-NNNN` literal would trip `scripts/check-no-confidential.sh`'s regex on the test source
   itself), and free-text notes. (b) `CommentLeakExportTest
   #contactAndIdentityDataIsAbsentFromEveryNormalExportVariant`: email/phone/externalId/notes
   asserted absent from ALL six normal export variants (grouped/flat × xlsx/csv ×
   includeComments=false/true — the comment opt-in must never widen to contact/identity data).
   (c) `AnonymizedExportLeakTest.identifyingStrings` extended with `externalId` + `notes` (planted,
   never vacuously null). (d) A sensitive custom TEXT field ("Medicinsk info: ...") created through
   the REAL field API + field-values endpoint, asserted absent from every export variant AND from
   `saved_plan.snapshot_json` (`CommentLeakExportTest#sensitiveCustomTextFieldNeverReachesExportsOrSavedPlanSnapshot`)
   — locking in that both pipelines consume only their specific structured fields, never arbitrary
   custom-field text.
4. **Finding 6 — anonymized export format validation.** `AnonymizedExportService.export` previously
   let any unknown `format` fall through to xlsx; now unknown → `BadRequestException` (400),
   matching `ExportService.export`'s validation; blank/null still defaults to xlsx (the
   controller's `defaultValue`). Test: `AnonymizedExportLeakTest
   #unknownFormatIsRejectedInsteadOfSilentlyDefaultingToXlsx`.

None of the four fixes touch constraint code or weights; golden scores verified byte-for-byte
unchanged after the fix round.

## Endpoints added (all under `/api`, token-guarded like everything else)

- `POST /api/plans/{planId}/saved-plans` `{name}` → 201, `SavedPlanDetailView` (status `saved`)
- `GET /api/plans/{planId}/saved-plans` → `SavedPlan[]` (raw rows, snapshot_json string included)
- `GET /api/plans/{planId}/saved-plans/{savedPlanId}` → `SavedPlanDetailView` (parsed snapshot)
- `PATCH /api/plans/{planId}/saved-plans/{savedPlanId}` `{status}` → transition (400/409 on bad)
- `DELETE /api/plans/{planId}/saved-plans/{savedPlanId}` → 204 (draft/saved only; 409 otherwise)
- `GET /api/plans/{planId}/export?format=&layout=&includeComments=&run=` → file bytes
  (Content-Disposition attachment; filename derived from plan name)
- `GET /api/plans/{planId}/export/anonymized?format=` → file bytes

## Tests

`./mvnw clean verify`: **501 tests, 0 failures, 0 errors** (M7 baseline: 443 → +53 in the main M8
round, +5 more in the review-fix round). Golden scores
(`test-data/regression/expected-scores.json`) byte-for-byte unchanged — nothing in M8 touches
constraint code, weights, or solver configuration. New files:

- `savedplan.SavedPlanLifecycleTest` (23) — the full legal/illegal transition matrix +
  deletability + unknown-status rejection (pure unit).
- `savedplan.SavedPlanSnapshotLeakTest` (1) — the task's snapshot comment-leak extension: raw
  `snapshot_json` column AND the served parsed view scanned for planted comment/note strings.
- `api.SavedPlanControllerIntegrationTest` (9) — POST/GET/PATCH/DELETE through MockMvc: created
  status+parsed snapshot, missing name 400, legal chain, illegal transitions 409, unknown status
  400, idempotent same-status PATCH, delete matrix, cross-plan 404 scoping,
  materialization-refresh-on-re-save (old rows frozen, new rows current).
- `api.SavedPlanCrossPlanBlockingE2ETest` (1) — **THE §23.6 path through the REAL API**: save via
  `POST .../saved-plans` (usage rows asserted present already at save), lock via `PATCH
  {status:"locked"}`, Anna (small-10's own coach c02) added as a Dam-plan player, real async solve
  with `blocking.blockCoaches=true` → she is never placed in whichever Dam group lands on the
  18.00 slot her locked Herr coaching occupies.
- `season.ConflictServiceSavedPlanTest` (3) — locked-snapshot-beats-diverged-live, snapshot never
  duplicates live conflicts, archived falls back to live.
- `exporter.GroupedXlsxExportTest` (3) — golden-structure reparse with POI: stacked column-A
  metadata (name → Ordning → Tid → Tränare), Swedish player header, `Antal spelare: 4`, blank
  separator rows, trailing Kölista section with header + `Antal på kölista: 2`; no Kommentar column
  by default; Kommentar column present on opt-in.
- `exporter.FlatExportTest` (4) — csv UTF-8 BOM bytes + `;`-delimited §20.2 column set + 10 data
  rows (8 placed + 2 Kölista), flat xlsx row/column shape, opt-in Kommentar column, grouped+csv 400.
- `exporter.CommentLeakExportTest` (3) — **the definitive §23.9 byte-level test**: comments planted
  on ALL 10 participants (placed + waitlisted), all three default export variants scanned at
  decompressed-zip-entry level (sharedStrings included) / decoded csv text; positive control
  (opt-in comment found by the same scanner, internal note still absent); anonymized export always
  comment-free.
- `exporter.AnonymizedExportLeakTest` (2) — no real first/last/display name, email, phone from the
  DB (players AND coaches) in xlsx-decompressed or csv bytes; structure survives: `Spelare 001`/
  `Tränare 01`, real ranking values, `Torsdag 18.00–19.30` labels, wish column referencing
  anonymized ids, 2 Kölista rows.
- `api.OpenApiSchemaTest` (2) — `/v3/api-docs` (dev profile, unauthenticated — the typegen path)
  contains `ApplyMoveRequest` (groupId only) AND `WhatIfMoveRequest` (participantProfileId/
  targetGroupId/runId), no lingering `MoveRequest`; `SolveResponse` exists with all five fields and
  is referenced by the solve operation's responses.
- `api.ParticipantAssignmentRowRegressionTest` (2) — the E2E-discovered writeback bug (see "Bug
  found during E2E" above).
- `exporter.ExportTestFixture` (helper, not a test) — deterministic 8-placed/2-waitlisted small-10
  schedule so every export test has a real Kölista.

## E2E verification (packaged jar, dev profile, isolated `--app.data-dir`, port 4998)

`target/backend.jar` from `./mvnw clean verify`, fresh data dir. Unlike m6b/m7's transcripts (which
SQL-seeded participants), this run seeds the ENTIRE large-120 fixture through the REAL REST API
(130× `POST /persons` + `POST .../participants` + `PUT .../field-values`, 8 coaches + tri-state
availability, 4 slots + courts 3/4/3/2 → 12 blocks) — which is exactly what exposed the writeback
bug documented above. Trimmed transcript (after the fix):

```
time slots=4, training blocks=12 / coaches=8 / participants=130 recomputed=130 / groups=12
solve started: {"runId":"019f2292-1a43-...", "status":"SOLVING_ACTIVE", "score":null, ...}   <- new SolveResponse shape
solve finished in 28.2s: hard=0 medium=-800 soft=-341990 feasible=True waitlist=3
coach at 18.00: coaching 'Torsdag Herr 3' (naturally solver-assigned, no forcing needed)
POST saved-plans -> status=saved score=0hard/-800medium/-341990soft
  snapshot: groups=12 waitlist=3 constraintWeights=32
PATCH {status:"locked"} -> locked
grouped xlsx: 8821 bytes, 9 zip entries, 200 shared strings
  column A block head: ['Torsdag Herr 1','Ordning: 1','Tid: Torsdag 19.30–21.00 / Bana 1','Tränare: Hanna Åkesson','Namn']
  count rows: 'Antal spelare: 12/10/11...' ... Kölista section present
flat csv: 16004 bytes, BOM=True, 130 data rows, 3 Kölista rows
  header: Grupp;Tid;Bana;Tränare;Spelare;Ranking;Nivåscore;Tidigare grupp;Varningar
anonymized csv: 130 rows, real-name occurrences: 0, 'Spelare 001' present
  sample: Torsdag Herr 1;Torsdag 19.30–21.00;Bana 1;Tränare 06;Spelare 004;684.0;684.0;...;Spelare 093;;;
anonymized xlsx: 9 entries, headers Grupp/Tid/Bana/Tränare/Spelare/Ranking, no @example.se emails
dam plan: 13 participants (incl. the Herr 18.00-coach's PERSON), 2 groups, ONLY slot = 18.00–19.30
dam solve (FAST, blockCoaches=true): 12/13 placed; shared person's groupId=None (waitlisted)
§23.6 VERIFIED: locked Herr coaching commitment at 18.00 blocks the same person from the Dam solve
POST /api/system/shutdown -> {"status":"SHUTTING_DOWN"}
```

The Herr run's `0hard/-800medium` (3 permanently-unavailable participants waitlisted) matches
m6b/m7's transcripts for the same fixture — determinism carries across milestones AND across the
seeding path (API-created vs SQL-seeded participants produce the same solve outcome). The Dam
verification is decisive by construction: with 18.00 as the plan's ONLY slot, blocking leaves the
shared person nowhere legal to go, so "blocked" must manifest as an explicit waitlisting while all
12 other players place normally.

## Known gaps / deferred

- Frontend (Sparade planer view, Exportdialog, §14.4 blocking checkboxes UI) — the other half of M8,
  out of scope for this backend session. Note for that session: the solve response record is now
  `SolveResponse` (nullable `score`/`hardViolations`/`feasible`), and the two move-request schemas
  are `ApplyMoveRequest`/`WhatIfMoveRequest` after typegen.
- `run` export parameter is accepted-but-unused (Deviation 5).
- Saved-plan RESTORE ("återställ plan till detta snapshot") — spec §14 never requires it for MVP;
  the parsed snapshot is served read-only.
- No pagination on `GET .../saved-plans` (a council produces a handful per plan per term).
