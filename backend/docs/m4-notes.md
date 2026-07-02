# M4 implementation notes (backend half)

Design decisions and deviations made while implementing the backend half of M4 (fields, constraint
config, participant structuring). Primary spec: `../../utkast-kravspec.txt` §9 (Field Builder/
Constraint Builder), §11.2 (EstimatedLevel), §21.2 (känsliga kommentarer); design:
`docs/design/02-product-data-ui.md` §3; corrections: `docs/plan.md`'s M4 row and "Privacy
corrections" section. The frontend half (Fältbyggare/Deltagarvy UI) is a separate task.

## Schema

**No new Flyway migration.** `field_definition`, `custom_field_value`, `constraint_definition`, and
`constraint_weight_config` were all already created by `V2__seed_constraints_and_standard_fields.sql`
(M1), anticipating M4's CRUD — confirmed by re-reading the V1/V2 migrations and
`FlywayMigrationTest.allExpectedTablesExist()` before writing any code. `field_definition`'s existing
`CHECK ((storage_kind = 'COLUMN') = (column_name IS NOT NULL))` already guarantees every
API-created custom field (always `storage_kind = 'CUSTOM'`, `column_name = NULL`) is well-formed.

## Field builder (spec §9)

### Endpoints
- `GET /api/plans/{planId}/field-definitions` — now returns `findVisibleToPlan` (globals + the
  plan's own custom fields) instead of M1's `findStandardFields`-only stub. Existing M1 test
  (`FieldDefinitionControllerTest`) still passes unchanged since it never creates a custom field.
- `POST /api/plans/{planId}/field-definitions` — create a custom field.
- `PATCH /api/field-definitions/{id}` — edit a field (see restrictions below).
- `DELETE /api/field-definitions/{id}` — custom fields only.

Route shape (nested for the collection, flat `/api/field-definitions/{id}` for the single
resource) mirrors the existing `ParticipantProfileController` convention rather than the
`docs/design/02-product-data-ui.md` §8 sketch's `/field-definitions/{id}` — that sketch is
literally the same shape, just confirming the existing codebase pattern was the right one to copy.

### fieldType ↔ constraintType compatibility table (`se.klubb.groupplanner.fields.ConstraintTypes`)

The spec gives worked examples (§9.1) but no exhaustive table, so this is an M4 design decision:

| fieldType | allowed constraintType (besides always-allowed `NONE`) |
|---|---|
| `text`, `boolean`, `singleSelect`, `multiSelect`, `tag`, `groupRelation` | none (information-only) |
| `number` | `LEVEL_BALANCE_INPUT`, `PRIORITY` |
| `personRelation` | `SAME_GROUP`, `DIFFERENT_GROUP` |
| `coachRelation` | `COACH_PREFERENCE`, `COACH_FORBIDDEN` |
| `timeRelation` | `TIME_AVAILABILITY`, `TIME_PREFERENCE` |

`groupRelation` has no constraint-type mapping in MVP (no §10 constraint consumes an arbitrary
group-reference field) — it stays information-only until a concrete solver use case exists in M6+.

### Validation matrix (`FieldDefinitionValidator`, shared by create + patch)
- `affectsOptimization=false` ⇒ `constraintType` must be `NONE`, `hardOrSoft` must be `INFO` (or
  omitted), `weight` must be omitted.
- `affectsOptimization=true` ⇒ `constraintType` must be non-`NONE` and compatible with `fieldType`
  (table above); `hardOrSoft` must be `HARD` or `SOFT` — **never `MEDIUM`** (ADR-006: MEDIUM is
  reserved for the solver's own `unassignedPlayer` waitlist penalty, landing in M6a); `SOFT`
  requires a positive integer `weight`, `HARD` forbids one.
- `key` must match `^[a-zA-Z][a-zA-Z0-9]*$` (camelCase, matching every seeded standard-field key)
  and must not collide with a global/standard field's key (checked explicitly with a clear 400,
  in addition to the DB's `UNIQUE(activity_plan_id, key)` constraint that catches collisions with
  another custom field on the same plan as a 409).
- `singleSelect`/`multiSelect` require a non-blank `optionsJson` (a JSON array of option strings) at
  creation time.

### Standard/global field PATCH restriction
Per the milestone brief, standard fields only allow changing
`affectsOptimization`/`constraintType`/`hardOrSoft`/`weight`/`direction`/`explanationText` — never
`key`/`fieldType`/`storageKind` (structurally impossible: the patch DTO has no such fields) nor
`label`/`optionsJson`/`sortOrder` (explicitly rejected with a 400 if a standard field's patch
request includes any of them, rather than silently ignoring — silent ignoring would hide a caller
bug). Custom fields additionally allow `label`/`optionsJson`/`sortOrder`.

**Weight auto-clearing on HARD/INFO transitions**: rather than requiring the caller to explicitly
clear a stale `weight` when flipping a field from SOFT to HARD (which would need the same
null-vs-absent distinction described below for participant PATCH), the merge logic in
`FieldDefinitionController.update` *forces* `weight = null` whenever the resulting `hardOrSoft` is
`HARD` or `affectsOptimization` becomes `false` — a business rule, not a workaround, since a weight
is definitionally meaningless in either case. This sidesteps needing a `JsonNode`-based patch body
for this endpoint. One accepted gap: `direction`/`explanationText` cannot be explicitly cleared to
`null` via this endpoint (only overwritten with a new value or left as-is) — lower-stakes than
comments, and out of this milestone's explicit scope (item 6 only mandates the fix for participant
fields).

### Reserved-MEDIUM field PATCH (M4 review fix)
The seeded standard `priority` field is the one MEDIUM-classified field
(`is_standard=1, hard_or_soft='MEDIUM', affects_optimization=1` in V2) — its weight scales M6's
`unassignedPlayer` waitlist penalty (ADR-006). The original M4 PATCH path had the guardrail
**inverted**: any patch leaving `affectsOptimization=true` reconstructed MEDIUM and tripped the
unconditional MEDIUM rejection (even `{"explanationText":"x"}` 400'd, making the field
un-editable), while `{"hardOrSoft":"SOFT"}` and `{"affectsOptimization":false}` sailed through the
non-MEDIUM validation branch and corrupted the reserved classification. Fixed by mirroring
`ConstraintWeightService.validateReclassification`'s split in the field-PATCH path
(`FieldDefinitionValidator.validateReservedMediumPatch`): when the *existing* field is MEDIUM it
must **stay** MEDIUM (explicit `"hardOrSoft":"MEDIUM"` is an accepted no-op; anything else 400s)
and `affectsOptimization` must stay true; `weight` (floor ≥ 1), `direction` and `explanationText`
remain editable. MEDIUM still can never be *introduced* on non-MEDIUM fields (create + patch).

## Custom field values (spec §7.14, §9.1 structured-entry drawer)

`se.klubb.groupplanner.fields.FieldValueService` is generic over `entityType` per the brief
("design the repo generically... Same for coaches later (M5)") — `CustomFieldValueRepository`
already was generic (M3); this service adds the type-aware validation layer on top and is
unchanged if/when M5 adds a coach-scoped controller calling the same methods.

`GET|PUT /api/plans/{planId}/participants/{pid}/field-values`. Value shapes:

| fieldType | JSON value shape | validation |
|---|---|---|
| `text` | string | — |
| `number` | number | — |
| `boolean` | boolean | — |
| `singleSelect` | string | must be one of `options_json` if the field declares options |
| `multiSelect` | array of strings | each must be one of `options_json` if declared |
| `tag` | array of strings | none (free-form) |
| `personRelation` | array of `participant_profile` ids | each must exist **in the same plan**; self-reference rejected |
| `coachRelation` | array of `coach_profile` ids | each must exist in the same plan |
| `groupRelation` | array of `training_group` ids | each must exist in the same plan (direct `JdbcClient` existence check — no `TrainingGroupRepository`/domain type exists yet, that's M5; adding one just for this one query would be premature) |
| `timeRelation` | array of free-text time expressions | each validated with the **existing** `SwedishTimeParser.isValidTimeExpression` grammar (§8.6 "ogiltiga tider") |

**`timeRelation` deviation from the design doc's structured `TimeSlot`-picker vision**: M5 (which
hasn't landed) is what introduces `TimeSlot` CRUD/repository; M4 has no structured time slots to
validate against yet, so per the milestone brief's explicit instruction ("timeRelation values
validated with the existing SwedishTimeParser"), values are free-text expressions like `"18:00"` or
`"ej 21"` validated by the same parser the import pipeline already uses for the analogous free-text
`Tid` column — not resolved against `time_slot` ids. When M5 lands, a follow-up could add a second,
stricter validation path (or migrate this field type to reference real `TimeSlot` ids) without
changing the wire shape for already-entered free-text values under discussion with the product
owner at that time.

Only `CUSTOM`-storage fields are writable here; a `COLUMN`-storage field key (e.g.
`rankingPoints`) is rejected with a 400 pointing at the participant PATCH endpoint instead.

A JSON `null` for a field clears its stored value (deletes the effective value; the underlying
`custom_field_value.value_json` is set to SQL `NULL` via the existing generic `upsert`).

## Constraint weights (spec §9.4, §7.16)

`GET|PUT /api/plans/{planId}/constraint-weights` merges the 24 seeded `constraint_definition`
defaults with a plan's `constraint_weight_config` overrides (`ConstraintWeightService`). `PUT`
accepts a list of partial overrides (`{key, hardOrSoft?, weight?, enabled?}`); each entry is merged
onto its *currently-effective* row (override if one exists, else the definition default) before
validation and an upsert of the full resulting row — the DB table has no nullable columns, so this
merge-then-store approach avoids needing partial-null semantics here (unlike the participant PATCH
case below, every field of a `constraint_weight_config` row is always meaningful).

**Guardrail hook (structured now, enforced generically, exercised for real in M6a)**: none of
today's 24 standard constraints are `MEDIUM`-classified, so the "not disableable, not
reclassifiable away from MEDIUM, weight floor ≥ 1" guardrail
(`ConstraintWeightService.validateReclassification`) currently only ever takes the
"else" branch (`HARD`/`SOFT` only, weight ≥ 1) for real requests — but it keys off
`constraint_definition.hard_or_soft == 'MEDIUM'` generically, not off a hardcoded constraint key, so
when M6a seeds the real `unassignedPlayer` row (MEDIUM, per ADR-006), the guardrail applies with
zero code changes here. Deviation from `docs/plan.md`'s literal wording ("weight floor ≥ 1 (422
otherwise)"): this returns **400**, matching every other validation failure in this codebase (no
422 usage anywhere in the existing controllers) — M6a can promote it to 422 if the full solver-side
guardrail spec wants that exact code once the real constraint exists.

**Never-disableable structural constraints (M4 review fix)**: `enabled=false` is rejected (400) for
the ten structural feasibility constraints whose absence would let the solver produce a physically
impossible or contract-breaking schedule: `trainingBlockCapacity`, `groupRequiresTrainingBlock`,
`groupMaxSizeHard`, `playerNoOverlap`, `coachNoOverlap`, `coachCannotTrainAndCoachSameTime`,
`timeAvailabilityHard`, `coachAvailabilityHard`, `lockedAssignmentHard`, `savedPlanResourceBlock`
(allowlist in `ConstraintWeightService.NEVER_DISABLEABLE_KEYS`, keys exactly as seeded in V2).
They remain reclassifiable HARD↔SOFT (the spec's own §9.1 examples do exactly that); everything
else — size targets, level balance/ordering, continuity, all preferences, `lateTimeForLowerGroups`,
`sameGroupHard`/`differentGroupHard` (data-driven wishes, not physics) — stays freely disableable
per spec §9.

## LevelService (spec §11.2/§11.4)

`se.klubb.groupplanner.level.LevelService` — a plain deterministic service outside the solver (per
`docs/plan.md`'s solver-design section), so ordinary `double` math is used throughout; CLAUDE.md's
no-float rule scopes only to `solver.domain`/`solver.constraints`, not this package.

Chain (first source wins): `manualLevelScore` → `rankingPoints` → `previousGroupLevel` → none.

**Confidence values** (spec §11.4 allows "hög/medel/låg" or numeric 0.0–1.0; this service always
returns numeric, at four fixed points — an M4 design decision, no spec-given numbers):
`HIGH=1.0` (manual score), `MEDIUM=0.6` (ranking), `LOW=0.3` (previous-group mapping), `NONE=0.0`
(no source, `manualReviewFlag` forced on).

**Ranking normalization**: identity mapping (`rankingPoints` used directly). The spec says "ranking
normaliserat" without a formula; the real file's observed `Rank` column is already 0–1000, the same
scale as every other level field, so MVP normalization is the identity function — documented per
the milestone brief's explicit instruction.

**previousGroupLevel → level score mapping**: `level = 1000 - (groupLevel - 1) * 75`. Group 1 (the
top group) → 1000; group 12 (the lowest of 12 groups, spec's canonical group count) → 175. This is
this milestone's own design decision (the spec only says "omvandla ... till nivåscore" without a
formula) using exactly the example formula given in the milestone brief.

**Clamping — ALL sources (M4 review fix)**: the result of *every* source in the chain — not just
the previous-group mapping — is clamped to `[0, 1000]` before being returned/persisted as
`estimatedLevel`: a dirty imported ranking of 1200 or a fat-fingered manual score of −50 must never
push an out-of-scale value into the solver's level math. The stored source column itself
(`ranking_points`/`manual_level_score`/`previous_group_level`) is deliberately left untouched so the
original input stays visible and correctable in the UI. Boundary cases (−50/0/1000/1200/1500,
group-level 0/1/12/100) are covered in `LevelServiceTest`.

**manualReviewFlag on recompute never un-flags**: `ParticipantProfileRepository
.updateComputedLevel` only ever turns `manual_review_flag` **on** (via a SQL `CASE WHEN
:force = 1 THEN 1 ELSE manual_review_flag END`), never off — a routine recompute must not silently
clear a flag the council may have set for reasons unrelated to level confidence (e.g. "double-check
this waitlist edge case"). Only the "no level source at all" case ever forces it on.

**Endpoints**: `POST /api/plans/{planId}/participants/recompute-levels` (plan-wide, manual
re-trigger) and an automatic call from `ImportCommitService.commit()` — in the *same* transaction as
the import commit itself, so a commit and its level recompute always succeed or roll back together
(docs/plan.md: "also auto-run after import commit").

## Comment delete/anonymize (spec §21.2, docs/plan.md "Privacy corrections")

`CommentPrivacyController`:
- `DELETE /api/plans/{planId}/participants/{pid}/comments` — nulls one participant's
  `imported_comment`/`internal_note`. 204.
- `POST /api/plans/{planId}/comments/anonymize` — nulls every participant's comments in the plan.
  Requires an explicit `{"confirm": true}` body (irreversible plan-wide action); returns
  `{"clearedCount": N}` (only participants that actually had a comment are counted/touched, via a
  `WHERE imported_comment IS NOT NULL OR internal_note IS NOT NULL` guard in the repository query).

`CommentPrivacyControllerTest` extends the comment-leak test surface (CLAUDE.md: "keep it green")
by asserting the raw DB columns are `NULL` after anonymize via a direct `JdbcClient` query — not
just the domain-record mapping, which could mask a query bug (e.g. a `SELECT *` that silently
returns the old value from a stale row).

## PATCH null-vs-absent clearing semantics (docs/plan.md M4 row; M1 review finding)

The M1 review flagged that `ParticipantProfileController.update`'s "if request field is null, keep
the existing value" merge can never *clear* a nullable column (most importantly
`importedComment`/`internalNote` — spec §21.2 requires comments be clearable). The fix:
`PATCH /api/participants/{id}` now binds its body as a raw `Map<String, JsonNode>` instead of a
typed request record.

**Why not a typed DTO**: a Java `record`/any Jackson creator-based type structurally cannot make
this distinction — Jackson resolves *every* constructor parameter through the same
missing-vs-explicit-null fallback path (`SettableBeanProperty.getNullValueProvider().getNullValue()`
is invoked identically whether the JSON property was absent or `null`), so both cases collapse to
the same value no matter what wrapper type is used for the field. This was verified empirically
while implementing this milestone (a `JsonNode`-typed record component was tried first and produces
`NullNode` in both cases). A `Map<String, JsonNode>` sidesteps the problem entirely because
Jackson's map deserializer only ever creates entries for keys that were textually present in the
source JSON — `body.containsKey(field)` is therefore a reliable presence check, and
`body.get(field).isNull()` a reliable explicit-null check (deserializing an explicit JSON `null`
into a `JsonNode`-typed map value yields `NullNode`, not a Java `null`, for the same reason —
verified by the tests in `ParticipantProfileControllerTest`).

Applied to every patchable field: `rankingPoints`, `rankingSource`, `previousGroupName`,
`previousGroupLevel`, `estimatedLevel`, `levelConfidence`, `manualLevelScore`, `importedComment`,
`internalNote` (nullable columns — omitted keeps, explicit `null` clears) and `manualReviewFlag`,
`waitlisted` (`NOT NULL` columns — omitted keeps, explicit `null` is **rejected with a 400** rather
than silently coercing to `false`, since that would be a different, surprising kind of "clearing").

**Unknown keys are rejected (M4 review fix)**: any key outside the exact patchable set above is a
400 listing the valid fields. Silently ignoring unknown keys — the default behavior of a raw map
binding — would make a typo like `{"imortedComment": null}` return 200 while clearing nothing: the
user believes the §21.2-sensitive comment is deleted, but it is still in the database. This matches
the strictness `FieldValueService` (unknown field key → 400) and `ConstraintWeightService` (unknown
constraint key → 400) already had.

**`importedComment` is clear-only via PATCH (M4 review fix)**: `importedComment` is the audit trail
of what the member actually wrote at registration — per spec §2.2/§8.5 only the import pipeline
writes it. The generic PATCH may therefore only *clear* it (explicit `null`, spec §21.2); a request
carrying non-null text for it is a 400. `internalNote` (the council's own working note) stays freely
writable. The create endpoint still accepts an initial `importedComment` (it is the API-level
equivalent of an import for manually-registered participants and is exercised by tests); the
restriction is specifically about mutating an existing participant's imported audit text.

**Trade-off accepted**: `Map<String, JsonNode>` shows up in the generated OpenAPI schema as a
generic object rather than a named shape with typed properties, which will slightly degrade the
frontend's `openapi-typescript` typegen for this one endpoint (M2's typegen pipeline). The exact
wire shape is documented in the controller's class Javadoc and in this file. `FieldValueService`'s
`PUT .../field-values` endpoint uses the same `Map<String, JsonNode>` pattern for the same reason
(every value in that map is "present" by construction — the field key literally being in the map
*is* the write instruction — so `null` there unambiguously means "clear this one field", no
absent-vs-null ambiguity to resolve, but the same wire shape was kept for consistency).

## Tests

188 backend tests green (`./mvnw verify`), up from 158 before this milestone (180 at first review,
+8 from the review fixes). New files:
- `FieldDefinitionCrudControllerTest` (13) — create/patch/delete validation matrix, incl. the
  reserved-MEDIUM `priority` field patch behavior (allowed: weight/explanationText; forbidden:
  SOFT reclassification, affectsOptimization off).
- `ConstraintWeightControllerTest` (8) — merge logic, HARD/SOFT-only reclassification, MEDIUM
  rejection, weight floor, unknown key, never-disableable structural constraints (blocked:
  `playerNoOverlap`; allowed: `lateTimeForLowerGroups`).
- `ParticipantFieldValueControllerTest` (6) — round-trips per type, personRelation/self/cross-plan
  validation, timeRelation grammar, singleSelect options, COLUMN-field rejection.
- `CommentPrivacyControllerTest` (5) — delete/anonymize incl. raw-DB leak assertion, confirm
  guard, cross-plan isolation.
- `se.klubb.groupplanner.level.LevelServiceTest` (9) — pure unit tests, no Spring context: chain
  priority order, clamp boundaries for all three sources (manual −50/0/1000/1200, ranking
  −1/0/1000/1500, groupLevel 0/1/12/100), no-source case.
- `se.klubb.groupplanner.level.LevelServiceIntegrationTest` (1) — DB-level: recompute NULLs a
  previously-persisted `estimated_level` when every source is removed (confidence → 0.0,
  manual_review_flag forced on), asserted on the raw columns.
- Extended `ParticipantProfileControllerTest` (+5): absent-vs-null PATCH semantics, required-boolean
  null rejection, recompute-levels endpoint wiring, unknown-key 400 rejection, importedComment
  clear-only restriction.
- Extended `ImportControllerIntegrationTest` (+2 assertions): auto-recompute after commit persists
  `estimatedLevel`/`levelConfidence` for an imported row.

## E2E verification (curl transcript against the packaged jar, dev profile, port 4517)

Ran the full scenario from the milestone brief end to end against `target/backend.jar`: season →
plan → two persons/participants → custom field "Vill spela med" (personRelation/SAME_GROUP/SOFT/80)
→ linked the two participants via `PUT .../field-values` → overrode `sameGroupSoft` weight to 95 via
`PUT .../constraint-weights` → `POST .../recompute-levels` (confirmed `720.0`/`0.6` identity-ranking
and `850.0`/`0.3` previous-group-mapping results) → `POST .../comments/anonymize` → confirmed via
both the API response and a direct `sqlite3` query that `imported_comment`/`internal_note` are
`NULL` in the database. All steps succeeded; no deviations found during this pass.

## Known gaps / deferred to later milestones
- `groupRelation` field values validate existence directly against `training_group` via a raw SQL
  query (no repository/domain type yet) — fine for M4, but M5 should introduce a proper
  `TrainingGroupRepository` and this validation should switch to using it.
- `timeRelation` custom field values are free-text (SwedishTimeParser-validated), not resolved
  against structured `TimeSlot` rows, since M5 hasn't landed `TimeSlot` CRUD yet (see above).
- Standard field PATCH cannot explicitly clear `direction`/`explanationText` to `null` (only
  overwrite or leave as-is) — accepted gap, out of this milestone's explicit scope.
