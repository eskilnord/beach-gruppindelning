# M3 implementation notes

Design decisions and deviations made while implementing M3 (generic import wizard engine —
backend half only; the wizard UI is a separate task). Primary spec: `../../utkast-kravspec.txt`
§8 (Importkrav); design: `docs/design/02-product-data-ui.md` §2; corrections:
`docs/plan.md`'s M3 row and "Import pipeline corrections (red-team)" section.

## Dependency versions

- `org.apache.poi:poi:5.5.1` / `poi-ooxml:5.5.1` — latest 5.5.x on Maven Central as of 2026-07-02
  (`repo1.maven.org` `maven-metadata.xml` `<latest>`/`<release>` both `5.5.1`), matching the pin
  already verified in `docs/design/02-product-data-ui.md` §0.
- `org.apache.commons:commons-csv:1.14.1` — latest 1.x, same verification method.
- Both pinned exact (no version ranges), per the milestone brief. CLAUDE.md's determinism rules
  ("no float/double/BigDecimal... enforced by ArchUnit") scope only to `solver.domain`/
  `solver.constraints` — the importer is plain data-plumbing and uses ordinary `double`/`String`
  parsing throughout without restriction.

## Migration numbering

`docs/plan.md`'s "SQLite schema" section describes a 4-migration split (`V1__core`,
`V2__seed_constraints_and_standard_fields`, `V3__runs_explanations_plans`, `V4__import`) that
predates the milestone restructuring in the same document, where M3 (import) now runs *before*
M6a (`optimization_run`) and M7 (`explanation_record`). Since only `V1`/`V2` exist on disk, this
milestone's tables (`import_template`, `import_run`) land in **`V3__import.sql`**; the
`optimization_run`/`explanation_record` tables will get their own migration number (`V4`) when
M6a/M7 are implemented. `FlywayMigrationTest` was updated accordingly (three migrations, two new
tables).

## Parsing layer (`importer/parse/`)

- `ParsedCell{rawString, typedValue, cellType}` — `rawString` is always the `DataFormatter`
  (sv-SE locale) display text; `typedValue` is `Double`/`LocalTime`/`LocalDate`/`Boolean`/`String`/
  `null` per the red-team correction ("keep typed values ... map numeric targets from typed
  values; locale-aware parsing only for text-typed cells").
- **DATE vs TIME**: both are xlsx "date-formatted" numeric cells. A value whose integer (day) part
  is zero (e.g. Excel's `18:00:00` stores as `0.75`) is treated as `TIME` (`typedValue` =
  `LocalTime`); anything else is `DATE` (`typedValue` = `LocalDate`). There is no `LocalDateTime`
  typed value — a date-plus-time cell (never seen in this domain) would degrade to `LocalDate`,
  documented rather than silently wrong.
- **Comments/merged cells**: `XlsxParser` never reads cell comments or threaded comments at all —
  POI's cell-value APIs used here are entirely unaffected by their presence, so "tolerate" reduces
  to "don't crash", verified by a fixture carrying both a legacy comment and a real threaded-
  comment OOXML part (see Fixture generator below) plus a merged region. Every cell read is
  wrapped in a try/catch that degrades to `CellType.ERROR` rather than aborting the whole sheet.
- **CSV charset**: BOM sniff first; then a *strict* UTF-8 decode attempt (`CodingErrorAction
  .REPORT`) — genuinely UTF-8 files (the common case) are never mis-decoded; only on failure does
  it fall back to **windows-1252** (not ISO-8859-1 — the red-team correction, since windows-1252
  covers the ’/…/– characters ISO-8859-1 cannot represent).
- **CSV delimiter**: counts `;` vs `,` over the first 5 non-blank lines, `;` wins ties (Swedish
  Excel default).
- **Number parsing** (`SwedishNumberParser`): strips plain space, U+00A0 (NBSP) and U+202F
  (narrow NBSP) thousands separators, then swaps `,` for `.`. Only ever applied to text-typed
  cells — a genuine xlsx `NUMERIC` cell's `Double` is used as-is (`NumericValue.resolve`).
- **Time validation** (`SwedishTimeParser`): a small, generic token grammar — bare `H:MM`/`H.MM`
  (optionally with seconds) or the Swedish shorthand `"ej <hour-or-time>"` ("not at this time"),
  comma/semicolon-separated lists of the above. Anything else is invalid. This is intentionally
  generic (not tied to the real file's exact wording beyond the very common Swedish word "ej") so
  it satisfies CLAUDE.md's "never hardcode column names/values from the real file" while still
  distinguishing genuine free-text noise (e.g. "arton") from recognized shorthand.

## Import session (`importer/`)

- `ImportSessionService` holds sessions in a `ConcurrentHashMap`, purged lazily (on every access)
  rather than via a background scheduler — simpler, and deterministic for tests. The 1-hour TTL
  renews on every `get()`. A package-private constructor accepts an injectable `Clock` so tests
  simulate expiry without sleeping (`ImportSessionServiceTest`).
- **"The sheet" for validate/decisions/commit**: the REST surface's `GET .../validate`, `PUT
  .../decisions`, and `POST .../commit` endpoints take no `sheet` parameter (per the milestone
  brief's endpoint list). `PUT .../header {sheet, headerRowIndex}` both overrides the header row
  *and* selects that sheet as `ImportSession.selectedSheet`; `PUT .../mapping` also carries its own
  `sheet` field and updates the selection as a convenience. `GET .../preview` is the only endpoint
  that takes an explicit `sheet` query parameter, since the user is still choosing among sheets at
  that point (spec §8.3 step 2 "Välj sheet"). This mirrors the wizard's own step order (select
  file → select sheet/header → map → validate → decide → commit).
- Nothing is persisted until commit (verified by `ImportControllerIntegrationTest` — no DB rows
  exist between upload and the `POST .../commit` call).

## Column mapping suggestions

`ColumnMappingSuggester` holds a Swedish/English synonym table (not the real file's column names)
keyed by normalized header text, with a Jaro-Winkler fuzzy fallback (threshold 0.85) for minor
misspellings. `customField:<key>` targets are never suggested automatically (that requires
knowing which custom fields exist for the plan) — the controller resolves those directly against
`FieldDefinitionRepository` when a session's matched `import_template` supplies one.

## Validation (`ImportValidationService`) — spec §8.6 flag-by-flag

| Flag | Implementation |
|---|---|
| saknat namn | `firstName`/`lastName`/`displayName` all blank when at least one is mapped → **SKIP**. Generically absorbs the real file's stacked-metadata rows and section-heading rows (they have no mapped name value), without any file-specific logic. |
| tomma rader | `ParsedSheet.isRowEntirelyBlank` (every column blank) → **SKIP**, checked before any other rule. |
| dubbletter (within file) | Rows are grouped by normalized name and by normalized email in one pass before per-row validation; any group with >1 row → **WARN** on every member. |
| saknad ranking | Only checked when a `rankingPoints` column is mapped at all, and only for rows that otherwise have a name → **WARN**. |
| ogiltiga tal | Any mapped numeric-target cell (`rankingPoints`/`previousGroupLevel`/`manualLevelScore`) that is non-blank and fails `NumericValue.resolve` → **WARN**, with the offending raw text in the reason. |
| okänd tidigare grupp | Compared against the **distinct set of `training_group.name` values across the whole database** (not scoped to the current plan/season — the previous term's groups usually live in a *different* `activity_plan` row). If no `training_group` rows exist anywhere yet (the common case at first import, since groups are normally created after import in M5), the check is **disabled entirely** rather than flagging everything — there's nothing meaningful to compare against yet. `ImportValidationServiceIntegrationTest` covers both branches. |
| ogiltiga tider | Fires only for columns mapped to a `customField:<key>` whose `field_definition.field_type == 'timeRelation'` (e.g. the standard `cannotTimes`/`preferTimes`/`canTimes` fields) — this is the red-team note's "any column mapped to a time-typed target" read literally, and is more robust than a header-synonym heuristic since it's driven by the field's actual declared type. An Excel-native `TIME` cell is always valid regardless of its text; a text-typed cell goes through `SwedishTimeParser`. |
| potentiella dubbletter (§8.7) | `PersonMatcher`: exact email → exact phone (digits-only normalized) → exact normalized name → Jaro-Winkler ≥ 0.92 on `"förnamn efternamn"`. Proposals are attached to the row's `matchProposals` and also add a generic **WARN** reason. |

Row status precedence: any SKIP-triggering condition wins outright (tomma rad / saknat namn);
otherwise any WARN-triggering condition makes the row WARN; otherwise OK. All reasons are still
collected (not short-circuited) so the wizard UI can show everything at once.

## Commit (`ImportCommitService`)

- `@Transactional` — Spring Boot's JDBC starter auto-configures a `DataSourceTransactionManager`
  against the app's single `DataSource` bean, so this works without any extra wiring (no JPA
  involved, per ADR-004).
- Re-runs `ImportValidationService.validate(...)` at commit time (not just trusting a stale
  cached result) so decisions are always checked against the current mapping/session state.
- **Decisions default**: SKIP-status rows default to `SKIP`; everything else defaults to
  `CREATE_NEW` — never silently auto-merges into an existing person, even when a high-confidence
  match proposal exists (spec §8.7: "Användaren ska kunna bekräfta eller separera personer").
- **Person capability widening**: creating/matching a person for a participant row sets
  `canBeParticipant=true`; for an `isCoach` row, `canBeCoach=true`. Matching an *existing* person
  whose capability flag isn't set yet widens it (via a `Person` update) rather than leaving it
  stale — e.g. someone imported as a participant in one file and later marked `isCoach` in
  another becomes both.
- **`isCoach` (docs/plan.md red-team: "coach import ... spec §13.1 allows importing coaches")**: a
  truthy `isCoach`-mapped cell routes the row to `coach_profile` only (no `participant_profile`,
  no `player_assignment`) — the row represents a coach import, not a participant. Truthy values:
  `x`, `ja`, `yes`, `true`, `1`, `sant`, `sann` (case-insensitive).
  A minimal `CoachProfileRepository`/`domain.CoachProfile` were added for this — full Tränarvyn
  CRUD is still M5 scope; only insert-if-absent is implemented here.
- **`coachName` (a participant's free-text coach wish)**: stored as a `custom_field_value` against
  a lazily-created hidden **global** field `importedCoachWish` (text, `CUSTOM` storage, `INFO`,
  `is_standard=false`) — created on first use rather than seeded in `V2`, since it's import-
  pipeline plumbing, not one of the spec §9.2 standard fields. Consistent with spec §2.2/§8.5:
  reference text only, never auto-interpreted into a real coach relation (coaches may not even
  exist in the DB yet at import time).
- **`customField:<key>` targets**: `PUT .../mapping` rejects (400) any `customField:` mapping that
  resolves to a `COLUMN`-storage field (e.g. someone mapping a column to `customField:
  rankingPoints` instead of the dedicated `rankingPoints` target) — every `COLUMN`-storage
  standard field already has its own top-level mapping target, so `customField:` in practice is
  only ever used for `CUSTOM`-storage fields (`personRelation`/`timeRelation`/`coachRelation`/
  `boolean`/`text`/`priority`). This keeps the commit-time write path simple (always
  `custom_field_value`, never a generic column-name dispatch) without silently dropping data.
- **Re-import / upsert semantics**: `upsertParticipantProfile` looks up an existing
  `participant_profile` for `(personId, activityPlanId)` first (`ParticipantProfileRepository
  .findByPersonIdAndActivityPlanId`, added for this) and updates rather than duplicates; a mapped
  field's blank value never clobbers an existing non-blank one. `PlayerAssignmentRepository
  .insertImportedIfAbsent` is similarly idempotent (participant_profile_id is UNIQUE on that
  table) — re-running a commit for the same plan/rows is safe.
- `import_run.warnings_json`/`decisions_json` snapshot the full per-row validation results and
  final decisions for audit — neither ever contains `imported_comment`/`internal_note` text
  (CLAUDE.md confidentiality rules; the only free text that appears in warning reasons is
  ranking/previous-group/time reference values, which are not classified as sensitive).

## New minimal repositories

Three small repositories/domain records were added ahead of their "natural" milestone, scoped
narrowly to what M3's commit path needs (documented here rather than silently expanding scope):

- `CoachProfileRepository`/`domain.CoachProfile` (real milestone: M5) — insert + find-by-person-
  and-plan only.
- `PlayerAssignmentRepository`/`domain.PlayerAssignment` (real milestone: M6+) — insert-if-absent
  + find only, so an imported participant is recorded as "awaiting placement"
  (`source=imported, group_id=NULL`) per `docs/design/02-product-data-ui.md` §2 step 8.
- `CustomFieldValueRepository`/`domain.CustomFieldValue` (real milestone: M4) — upsert + find-by-
  entity only.
- `FieldDefinitionRepository` gained `findVisibleToPlan`, `findByKeyVisibleToPlan`,
  `findGlobalByKey`, and `insert` (previously read-only/global-only).
- `ParticipantProfileRepository` gained `findByPersonIdAndActivityPlanId`.

## REST surface

Mounted under `/api/plans/{planId}/import/...` exactly as specified in the milestone brief (this
supersedes `docs/design/02-product-data-ui.md` §8's `/api/import/...` sketch, consistent with that
document's own header note that the M1-implemented backend is the source of truth for API paths).
Auth header `X-GP-Token` (canonical, per the same header note), enforced by the existing
`TokenAuthFilter` on `/api/*` — no new security wiring needed. `GET /api/import/templates` is
global (not plan-scoped), matching `import_template`'s schema (no `activity_plan_id` column —
mappings are reusable across plans by header shape).

## Fixture generator (`src/test/java/.../importer/fixture/MessyWorkbookBuilder.java`)

Builds an in-memory `.xlsx` via POI from real names/rows in the committed, anonymized
`../test-data/datasets/large-120/participants.csv` (read via a relative path at test runtime —
Maven sets the working directory to the module basedir, so `Path.of("..", "test-data", ...)`
resolves correctly). Reproduces every element from the CONTEXT brief: a stacked-metadata first
column (color/group-number/time/coach fragments across a group's rows), a "N spelare" count row
with no name data, blank separator rows, a `Kölista` and `Utanför pga tid` section (each with a
merged-cell annotation heading and a real player row), a mixed-type `Tid` column (genuine Excel
`TIME` cells, valid `"ej N"` shorthand, and one genuinely invalid value), a within-file name
duplicate, an NBSP-thousands rank value, and å/ä/ö throughout.

**Threaded comment**: POI 5.5.1 has no high-level builder for modern "threaded comments" (verified:
no `Threaded*` class exists in either `poi` or `poi-ooxml` 5.5.1's compiled classes — only the raw
`ThreadedComments.xsb` schema is bundled). The fixture instead adds the real
`xl/threadedComments/threadedComment1.xml` and `xl/persons/person.xml` OOXML parts directly via
POI's public `OPCPackage`/`PackagePart` API, wired to the worksheet/workbook with the exact
relationship types Microsoft's extension defines
(`.../2017/10/relationships/threadedComment`, `.../2017/10/relationships/person`). Verified by
unzipping the generated file: both parts, their `[Content_Types].xml` overrides, and both
relationships are present and well-formed. This is wrapped in a best-effort try/catch that
degrades to "no threaded comment" on any exception, so a future POI upgrade breaking this
low-level trick fails soft rather than failing every import test.

Row indices are never hardcoded by callers — `BuiltWorkbook.row(label)` maps a semantic label
(`"p004"`, `"group1CountRow"`, `"blankRow1"`, ...) to the actual 0-based row index.

## Tests

- Parser unit tests: mixed cell types, typed numeric/date/time values, comments/merged cells
  tolerated, charset/delimiter matrix (UTF-8 BOM, windows-1252 with ’/…/–, semicolon/comma).
- `HeaderDetectorTest`, `ColumnMappingSuggesterTest`, `JaroWinklerTest`, `PersonMatcherTest`.
- `ImportSessionServiceTest` — creation, expiry (simulated via injectable `Clock`), expiry renewal
  on access, template auto-suggestion.
- `ImportValidationServiceIntegrationTest` — every §8.6/§8.7 flag exercised against the messy
  fixture in one Spring context, including both branches of the "okänd tidigare grupp" check.
- `ImportControllerIntegrationTest` — the full wizard flow over HTTP (upload → preview → header →
  columns → mapping → validate → decisions → commit), asserting DB state afterwards
  (`imported_comment` landed, coach-only row created no participant profile, skipped rows created
  no person, the within-file duplicate wasn't double-created) and template save + re-suggestion
  on re-upload.
- `FlywayMigrationTest` updated for the new migration/tables.

All backend tests pass via `./mvnw verify` (141 tests / 31 test classes after the post-review
fixes below; 129/30 at initial M3 delivery; up from the pre-M3 baseline).

## E2E verification

Built the fat jar (`./mvnw verify` already produces `target/backend.jar`), started it standalone
(`GP_TOKEN=... GP_DATA_DIR=<temp> java -jar target/backend.jar --server.port=4599`), dumped the
messy fixture to a temp file via a throwaway `main` compiled against `target/test-classes` (per
the milestone brief: "test utility main"), then drove the entire wizard with `curl` against the
real running server: create season → create plan → upload → preview → header → columns →
mapping → validate → decisions (skip a duplicate) → commit → verify participants/coach/templates
via the CRUD endpoints → re-upload the same file and confirm the saved template is auto-suggested
by header hash. Full transcript trimmed in the milestone completion report.

## Deviations from the round-1 design doc

- Endpoint paths follow the milestone brief exactly (`/api/plans/{planId}/import/...`), not
  `docs/design/02-product-data-ui.md` §2/§8's `/api/import/...` sketch — consistent with that
  document's own canonical-paths note ("the implemented backend is the source of truth").
- `GET /sessions/{sid}/columns` and `PUT .../mapping`/`GET .../validate`/`PUT .../decisions`/
  `POST .../commit` operate on a session-wide "selected sheet" rather than repeating a `sheet`
  parameter on every call (the brief's own endpoint list only shows `sheet` on `preview`, `header`,
  and `mapping`) — see "Import session" above.
- This task delivered the backend engine only — the wizard UI under `frontend/` is a separate,
  concurrently-owned task (so `frontend/` may well have changed in parallel; this doc makes no
  claim about it). No changes to `docs/design/`, `docs/plan.md`, or `CLAUDE.md`.

## Post-review fixes (adversarial review, M3)

The Opus adversarial review returned MERGE WITH FIXES (privacy trace clean, §8.6 coverage and
commit atomicity confirmed). Applied:

1. **externalId re-import collision (required)**: `PersonMatcher` now matches on
   `person.external_id` first (`EXTERNAL_ID_EXACT`, top confidence 1.0 — the member id is the
   source system's own identity), and `ImportCommitService.resolvePerson` reuses the existing
   person when a CREATE_NEW row carries an externalId that already exists, instead of inserting
   into the UNIQUE column and rolling the whole commit back as an opaque 409. External ids are
   stored/compared stripped. The messy fixture gained a `MedlemsId` column (index 10, sourced from
   the anonymized CSV's `id` column; the `p006Duplicate` row deliberately repeats `p006`'s id, and
   the coach row carries its own), and the full-flow integration test maps it; a dedicated
   re-import test runs the whole upload→commit round twice and asserts the second run merges
   (person count unchanged) rather than 409ing.
2. **Swedish phone normalization (required)**: `normalizePhone` canonicalizes the international
   prefix after digit-stripping — leading `46` (length ≥ 10) and leading `0046` (length ≥ 12) both
   map onto the domestic `0…` form, so `"+46 70 123 45 67"` ↔ `"070-123 45 67"` are `PHONE_EXACT`.
   Tested with both written forms.
3. **Privacy regression test (required)**: the full-flow integration test now reads
   `import_run.warnings_json`/`decisions_json` back from the DB after commit and asserts neither
   contains any imported-comment text from the fixture (CLAUDE.md leak-test rule).
4. **Session bound to plan (required)**: `ImportSession` stores its `activityPlanId` at creation;
   every plan-scoped endpoint resolves the session via `ImportSessionService.getForPlan(sid,
   planId)`, which 400s on mismatch — a session uploaded for plan A can no longer preview into,
   validate against, or commit into plan B. Unit + MockMvc tests.
5. **Multipart limits**: `spring.servlet.multipart.max-file-size`/`max-request-size` = 25MB
   (application.yaml defaults document, so dev inherits it); `MaxUploadSizeExceededException` maps
   to 413 with a Swedish-safe message in `ApiExceptionHandler` (new `ApiExceptionHandlerTest`).
6. **SwedishNumberParser hardening**: a plain-number regex (`[+-]?\d+(\.\d+)?` after separator
   normalization) gates `Double.parseDouble`, plus a `Double.isFinite` belt-and-braces check — so
   `"NaN"`, `"Infinity"`, `"0x1p4"`, `"5f"`, `"1e99"` all flag as "ogiltiga tal" instead of
   parsing. Signed plain numbers still parse.
7. **Corrupt .xlsx → 400**: `XlsxParser` catches `POIXMLException`/`UnsupportedFileFormatException`
   around workbook open/parse and rewraps as `IllegalArgumentException`, which
   `ImportSessionService.createSession` already maps to `BadRequestException` (400, not 500).
   Tested with a text file renamed `.xlsx` and a corrupt zip renamed `.xlsx`.
8. **SwedishTimeParser comma ambiguity**: `,` removed from the bare-time separator set (grammar is
   now `H(:|.)mm`); the comma is documented as a list separator only, so the inherently ambiguous
   `"9,30"` reads as the list "9 and 30" (30 is not a valid hour → flagged "ogiltig tid —
   kontrollera manuellt" for the user to resolve) while `"9.30"`/`"9:30"` still parse. Tested.
9. **Nits**: `ImportSession.setTemplateMatch`/`templateMatch` are now `synchronized` like the
   sibling mutators; the scope claim above was corrected (frontend is another agent's concurrent
   task, not "unchanged").
