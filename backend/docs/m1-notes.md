# M1 implementation notes

Design decisions and deviations made while implementing M1 (backend core + database), for the
record and for later milestones to build on. Primary spec: `docs/design/02-product-data-ui.md` §1
(schema) and §8 (REST surface); corrections per `docs/plan.md`; rules per `CLAUDE.md` and ADR-004.

## Dependency versions

- `org.xerial:sqlite-jdbc:3.53.2.0` — latest 3.53.x patch per `repo1.maven.org` maven-metadata.xml
  as of 2026-07-02 (`<latest>`/`<release>` both `3.53.2.0`).
- Flyway: Spring Boot 3.5.16 manages `flyway-core` at **11.7.2**, which pre-dates SQLite support
  (SQLite moved to a dedicated `flyway-database-nc-sqlite` module starting at Flyway **12.3.0**;
  the older `flyway-community-db-support` module that used to carry SQLite support was last
  published at 10.24.0). The `flyway.version` property is overridden to **12.10.0** (latest on
  Maven Central) in `pom.xml`, and `flyway-database-nc-sqlite:12.10.0` is added explicitly (it is
  not part of the Spring Boot BOM, so its version must be pinned by hand and kept in lockstep with
  `flyway.version`).
- Maven Central's `solrsearch` API (used by many agents to "check latest") returned stale results
  in this environment (capped around early 2025 for both artifacts). The `repo1.maven.org/.../
  maven-metadata.xml` endpoint returned accurate, current data and is what the version pins above
  are based on — worth knowing for future milestones that need to verify a version.

## AppDataDirResolver

- Priority: `--app.data-dir=` Spring property > `GP_DATA_DIR` env var > platform default. The
  property is read via `Environment.getProperty("app.data-dir")`, which covers `--app.data-dir=`
  command-line args, `-Dapp.data-dir=` system properties, and `app.data-dir` in a properties/yaml
  source — all "the property", per the milestone brief.
- Platform defaults use **"Gruppindelning"** (capitalized, matching `docs/plan.md` and the task
  brief), not "GroupPlanner" as written in `docs/design/01-architecture.md` line 154 — that file
  appears to carry a stale name from an earlier design round; `docs/plan.md`'s explicit "DB file
  location" section and the M1 brief both say "Gruppindelning", which is what ships.
- Linux default is the XDG-style `~/.local/share/gruppindelning/` (lowercase), matching the task
  brief; not one of the documented design docs, but explicitly requested and a sensible Linux
  convention (this app doesn't ship a Linux build in M0-era, but the resolver supports it for
  completeness and testability).
- The class exposes a pure static `resolveDir`/`platformDefault` for unit testing without touching
  the real filesystem or the real platform directory (see `AppDataDirResolverTest`); the Spring
  constructor is `@Autowired`-annotated because the class has two constructors (Spring cannot infer
  which one to autowire when there is more than one and none is a no-arg default).

## SQLite DataSource & pragmas

- `DataSourceConfig` builds a single `org.sqlite.SQLiteDataSource` configured via `SQLiteConfig`
  (`enforceForeignKeys(true)`, `setJournalMode(WAL)`, `setBusyTimeout(5000)`), URL from
  `AppDataDirResolver.jdbcUrl()`. Defining this `@Bean` makes Boot's `DataSourceAutoConfiguration`
  back off entirely, so **no HikariCP pool** is created — intentional for a single-user embedded
  desktop database (ADR-004: "no write concurrency"). `JdbcClient`/`JdbcTemplate`/the transaction
  manager are still auto-configured by `spring-boot-starter-jdbc` against this DataSource.
- Migration SQL files deliberately do **not** contain `PRAGMA` statements: Flyway's SQLite parser
  classifies `PRAGMA` as non-transactional and `CREATE TABLE`/`INSERT` as transactional, and refuses
  to mix the two in one migration file ("Detected both transactional and non-transactional
  statements... even though mixed is false"). Since `foreign_keys=ON`/WAL/busy_timeout are
  per-connection pragmas applied by `DataSourceConfig` on every connection this app opens
  (including Flyway's own), repeating them in SQL would be both redundant and would break the
  migration. `PragmaIntegrationTest` verifies the pragmas take effect end-to-end, including an
  actual FK-violation insert to prove enforcement (not just that the pragma value reads back as 1).
- `ResultSet.getObject(String, Class)` is JDBC-driver-dependent for which target classes it
  supports. xerial sqlite-jdbc 3.53.2.0 supports `Integer.class` but throws
  `SQLException("Bad value for type Double")` for `Double.class` on a nullable REAL column. All row
  mappers use the classic `getDouble()`/`getInt()` + `wasNull()` idiom instead (see
  `se.klubb.groupplanner.repo.NullableColumns`), which is uniform across JDBC drivers.

## Schema deviations from the naive design-doc schema

- **`field_definition.activity_plan_id` NULL-collision fix**: the design doc's
  `UNIQUE(activity_plan_id, key)` does not stop two *global* (`activity_plan_id IS NULL`) rows from
  sharing a `key`, because SQLite (like standard SQL) treats every NULL as distinct in a UNIQUE
  index. Closed with a partial unique index:
  `CREATE UNIQUE INDEX ... ON field_definition (key) WHERE activity_plan_id IS NULL`. This was
  necessary in M1 itself, since all 19 seeded standard fields have `activity_plan_id IS NULL` and
  must have unique keys among themselves.
- **`field_definition.column_name`** (added at review): COLUMN-storage fields carry the actual
  `participant_profile` column they map to (e.g. `needsManualReview` → `manual_review_flag`,
  `rankingPoints` → `ranking_points`), so M4's generic field read/write never needs a
  camelCase→snake_case guessing function. A CHECK constraint
  (`(storage_kind = 'COLUMN') = (column_name IS NOT NULL)`) keeps the shape honest for future
  writes; `FlywayMigrationTest` additionally asserts every seeded `column_name` exists on
  `participant_profile` (via `pragma_table_info`) and pins the exact key→column map. Since no
  deployed databases exist yet, this was added by editing V2 in place, not via a new migration.
- **Secondary FK indexes** (added at review): `coach_time_slot(time_slot_id)`,
  `training_block(court_id)`, `training_group(assigned_training_block_id)`,
  `coach_assignment(coach_profile_id)` — covers the reverse-direction lookups the composite
  UNIQUE/primary indexes don't, and speeds up FK action (CASCADE/SET NULL) resolution.
- **ON DELETE choices** (not specified in the design doc; documented in SQL comments in
  `V1__core.sql` and repeated here):
  - `activity_plan.season_plan_id`, and every plan-scoped child FK (`participant_profile
    .activity_plan_id`, `coach_profile.activity_plan_id`, `time_slot.activity_plan_id`,
    `training_block.activity_plan_id`, `training_group.activity_plan_id`,
    `field_definition.activity_plan_id`, `constraint_weight_config.activity_plan_id`) →
    `ON DELETE CASCADE`. These rows have no meaning outside their parent plan/season.
  - `participant_profile.person_id`, `coach_profile.person_id` → `ON DELETE RESTRICT`. A `person`
    is a shared, long-lived entity potentially referenced by many plans across seasons; deleting one
    while it still has participant/coach history is blocked rather than silently destroying that
    history.
  - `court.venue_id`, `training_block.court_id` → `ON DELETE RESTRICT`. Venues/courts are
    foundational, rarely-deleted scheduling resources; blocking deletion while referenced avoids
    silently wiping schedule data (the caller must remove/deactivate dependents first).
  - `training_group.assigned_training_block_id` → `ON DELETE SET NULL` (group becomes unscheduled,
    not deleted, if its block disappears).
  - `player_assignment.group_id` → `ON DELETE SET NULL` (participant becomes "unassigned", which is
    a normal state in the waitlist model, rather than losing their assignment row).
  - `coach_assignment.group_id` → `ON DELETE CASCADE` (a coach assignment has no independent meaning
    once its group is gone; unlike players, coaches have no waitlist concept).
- **Standard field count**: the spec (§9.2) and design doc both round this off informally ("18
  standard fields" in the design doc's prose); the actual bullet list in the spec has **19** items.
  All 19 are seeded; `FlywayMigrationTest`/`FieldDefinitionControllerTest` assert exactly 19.

## Constraint key naming

- Keys are the camelCase of each `§10.x` constraint's own name, preserving the `Hard`/`Soft` suffix
  exactly where the spec's own name has one (e.g. `sameGroupHard` vs. `sameGroupSoft`,
  `timeAvailabilityHard`, `differentGroupSoft`). The milestone brief's example
  ("`trainingBlockCapacity`...`lockedAssignment`, `savedPlanResourceBlock`") drops the `Hard` suffix
  from `LockedAssignmentHard`, but that shorthand cannot be applied uniformly: stripping `Hard`/
  `Soft` from `SameGroupHard`/`SameGroupSoft` (and the other Hard/Soft pairs) would collide two
  genuinely distinct constraints onto the same key. Read as an illustrative shorthand rather than a
  literal rule, since `LockedAssignmentHard` has no `...Soft` counterpart to collide with either way.
- `constraint_category`, seed `default_weight`, and (for `field_definition`) `constraint_type`/
  `hard_or_soft`/`weight` are reasonable M1 defaults for a read-only seeded catalogue, not yet
  wired into a solver (that arrives in M6) or a weight-editing UI (M4/M6b). Where the spec gives an
  explicit example weight (`sameGroupSoft` → 80, `levelBalance`/`manualLevelScore` → 100), that
  exact value was used.

## Error shape

- All error responses are `{"error": "<message>"}` (matches the existing `TokenAuthFilter` 401
  body from M0, so the shape is uniform across the whole API surface).
- `NotFoundException` → 404, `BadRequestException` → 400 (missing/invalid required fields,
  malformed JSON body), unique/foreign-key constraint violations → 409. The 409 path is detected by
  walking the exception's cause chain for `org.sqlite.SQLiteException` and checking
  `getResultCode().name().startsWith("SQLITE_CONSTRAINT")`, **not** by relying on Spring's
  `SQLErrorCodeSQLExceptionTranslator` database-specific error-code table — that table does not
  ship a mapping for SQLite, so the generic SQLState-based fallback translator is all that's
  available otherwise, and result-code inspection is more precise anyway.
- Foreign-key body references (e.g. `personId` on participant-profile create) are pre-validated by
  the controller and rejected with 400 + a clear message, rather than left to surface as a raw FK
  constraint violation (409) — friendlier for a body-referenced-but-missing-related-resource case,
  reserving 404 for the resource directly addressed by the URL path.

## REST surface scope

- `GET /api/plans/{planId}/field-definitions` returns only the 19 global standard fields in M1 (the
  milestone brief is explicit that activity-plan-scoped custom fields are M4 scope); `planId` is
  still validated so the endpoint 404s for an unknown plan.
- `GET /api/constraint-definitions` is global (not plan-scoped), matching
  `docs/design/02-product-data-ui.md` §8 exactly.
- `ParticipantProfile` also gets singular `/api/participants/{id}` GET/PATCH/DELETE routes (not
  spelled out letter-for-letter in the M1 brief's endpoint list, but required for CRUD and present
  in the design doc's own REST surface table: `PATCH /participants/{id}`).

## Test isolation

- Every `@SpringBootTest` uses a static JUnit `@TempDir` + `@DynamicPropertySource` to point
  `app.data-dir` at a fresh temp directory per test class. This was necessary for two reasons: (1)
  without it, tests would create/pollute the real developer machine's platform data directory the
  first time a DataSource bean is wired; (2) Spring's test-context cache reuses an `ApplicationContext`
  (and therefore the same underlying SQLite file) across test classes with identical configuration,
  which would leak state between otherwise-independent test classes (e.g. row counts) if they all
  pointed at one fixed directory.
