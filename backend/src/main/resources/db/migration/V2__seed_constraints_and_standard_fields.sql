-- V2__seed_constraints_and_standard_fields.sql
--
-- Adds constraint_definition, field_definition, custom_field_value, constraint_weight_config
-- (docs/design/02-product-data-ui.md §1) and seeds:
--   * all 24 standard constraints from ../utkast-kravspec.txt §10.1-10.24 into constraint_definition
--   * the 19 standard fields from ../utkast-kravspec.txt §9.2 into field_definition (global scope,
--     activity_plan_id = NULL; per-plan custom fields are created starting M4)
--
-- Naming/deviation notes (full rationale in backend/docs/m1-notes.md):
--   * Keys are camelCase of the §10 constraint names, preserving the Hard/Soft suffix exactly where
--     the spec's own name has one (e.g. sameGroupHard vs sameGroupSoft) — stripping it would collide
--     two genuinely distinct constraints onto the same key.
--   * field_definition.activity_plan_id is nullable (NULL = global/standard) but the naive
--     UNIQUE(activity_plan_id, key) does NOT stop two global rows from sharing a key: SQLite (like
--     standard SQL) treats every NULL as distinct in a UNIQUE index. A partial unique index below
--     closes that hole for the global (activity_plan_id IS NULL) case.
--
-- (No PRAGMA statements here either - see the note in V1__core.sql.)

-- ─────────────────────────────────────────────────────────────────────────────
-- constraint_definition (spec §7.15) — seeded with all 24 standard constraints (spec §10).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE constraint_definition (
    key                   TEXT PRIMARY KEY,
    label                 TEXT NOT NULL,
    description           TEXT NOT NULL,
    constraint_category   TEXT NOT NULL,
    default_weight        INTEGER NOT NULL,
    hard_or_soft          TEXT NOT NULL,
    enabled               INTEGER NOT NULL DEFAULT 1
);

INSERT INTO constraint_definition (key, label, description, constraint_category, default_weight, hard_or_soft, enabled) VALUES
('trainingBlockCapacity', 'En träningstid/bana per grupp', 'En TrainingBlock kan bara användas av en grupp.', 'CAPACITY', 1, 'HARD', 1),
('groupRequiresTrainingBlock', 'Grupp kräver träningstid/bana', 'Varje grupp ska ha exakt en TrainingBlock.', 'CAPACITY', 1, 'HARD', 1),
('groupMaxSizeHard', 'Max gruppstorlek (hård)', 'Grupp får inte ha fler spelare än maxSize.', 'CAPACITY', 1, 'HARD', 1),
('groupSizeTarget', 'Målstorlek grupp', 'Straffa avvikelse från targetSize.', 'CAPACITY', 50, 'SOFT', 1),
('groupMinSizeSoft', 'Minsta gruppstorlek (mjuk)', 'Straffa grupper som är under minSize.', 'CAPACITY', 50, 'SOFT', 1),
('levelBalance', 'Nivåbalans i grupp', 'Minimera nivåspridning inom grupp.', 'LEVEL', 100, 'SOFT', 1),
('groupOrderByLevel', 'Gruppordning efter nivå', 'Högre grupper ska generellt ha högre nivå än lägre grupper.', 'LEVEL', 50, 'SOFT', 1),
('previousGroupContinuity', 'Kontinuitet från tidigare grupp', 'Belöna placering nära tidigare gruppnivå.', 'LEVEL', 30, 'SOFT', 1),
('timeAvailabilityHard', 'Tillgänglig tid (hård)', 'Spelare får inte placeras i grupp på en tid spelaren inte kan.', 'TIME', 1, 'HARD', 1),
('timePreferenceSoft', 'Tidspreferens (mjuk)', 'Belöna placering på önskad tid. Straffa placering på oönskad men tillåten tid.', 'TIME', 40, 'SOFT', 1),
('sameGroupHard', 'Samma grupp (hård)', 'Två spelare måste placeras i samma grupp.', 'RELATION', 1, 'HARD', 1),
('sameGroupSoft', 'Samma grupp (mjuk)', 'Två spelare bör placeras i samma grupp.', 'RELATION', 80, 'SOFT', 1),
('differentGroupHard', 'Olika grupper (hård)', 'Två spelare får inte placeras i samma grupp.', 'RELATION', 1, 'HARD', 1),
('differentGroupSoft', 'Olika grupper (mjuk)', 'Två spelare bör helst inte placeras i samma grupp.', 'RELATION', 60, 'SOFT', 1),
('coachNoOverlap', 'Tränare krockar inte', 'En tränare kan inte coacha två grupper samtidigt.', 'SCHEDULE', 1, 'HARD', 1),
('playerNoOverlap', 'Spelare krockar inte', 'En spelare kan inte träna i två grupper samtidigt.', 'SCHEDULE', 1, 'HARD', 1),
('coachCannotTrainAndCoachSameTime', 'Tränare kan inte spela och coacha samtidigt', 'Samma person kan inte vara tränare samtidigt som personen själv tränar.', 'SCHEDULE', 1, 'HARD', 1),
('coachAvailabilityHard', 'Tränartillgänglighet (hård)', 'Tränare får inte tilldelas grupp på tid tränaren inte är tillgänglig.', 'COACH', 1, 'HARD', 1),
('coachRequirementHard', 'Tränarkrav (hård)', 'Om grupp kräver tränare måste den ha tränare.', 'COACH', 1, 'HARD', 1),
('coachLevelFit', 'Tränarnivå passar grupp', 'Belöna tränare som matchar gruppens nivå. Straffa tränare som är för låg eller för hög nivå för gruppen.', 'COACH', 50, 'SOFT', 1),
('coachPreferenceSoft', 'Tränarpreferens (mjuk)', 'Belöna om spelare/grupp får önskad tränare.', 'COACH', 50, 'SOFT', 1),
('lateTimeForLowerGroups', 'Sen tid för lägre grupper', 'Lägre grupper prioriteras till sena tider om föreningen vill det.', 'TIME', 30, 'SOFT', 1),
('lockedAssignmentHard', 'Låst placering (hård)', 'Låsta placeringar får inte ändras.', 'LOCK', 1, 'HARD', 1),
('savedPlanResourceBlock', 'Blockering från sparad plan', 'Tidigare sparade/låsta planer blockerar personer, tränare, banor och tider.', 'LOCK', 1, 'HARD', 1);

-- ─────────────────────────────────────────────────────────────────────────────
-- field_definition (spec §7.13) — is_standard=1 rows below are global (activity_plan_id NULL);
-- per-plan custom fields (M4) will have activity_plan_id set and is_standard=0.
-- storage_kind: COLUMN (maps onto a participant_profile column) | CUSTOM (goes to custom_field_value)
-- column_name: the actual participant_profile column a COLUMN-storage field maps to (e.g.
--              needsManualReview -> manual_review_flag); NULL for CUSTOM-storage fields. Storing the
--              mapping explicitly removes any need for camelCase->snake_case name guessing when
--              field values are read/written generically (M4). FlywayMigrationTest asserts every
--              COLUMN row's column_name exists on participant_profile.
-- constraint_type: LEVEL_BALANCE_INPUT|TIME_AVAILABILITY|TIME_PREFERENCE|SAME_GROUP|DIFFERENT_GROUP|
--                  COACH_PREFERENCE|COACH_FORBIDDEN|PRIORITY|NONE
-- hard_or_soft here is the field's own default classification (INFO = never reaches the solver);
-- weight is NULL for HARD/INFO fields where a numeric weight is meaningless, populated for SOFT.
-- Exact constraint wiring/weights are refined in M4 (Fältbyggaren) and M6 (solver); these are
-- reasonable seed defaults, not the final tuned values.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE field_definition (
    id                     TEXT PRIMARY KEY,
    activity_plan_id       TEXT REFERENCES activity_plan (id) ON DELETE CASCADE,
    key                    TEXT NOT NULL,
    label                  TEXT NOT NULL,
    field_type             TEXT NOT NULL,
    is_standard            INTEGER NOT NULL DEFAULT 0,
    storage_kind           TEXT NOT NULL,
    column_name            TEXT,
    affects_optimization   INTEGER NOT NULL DEFAULT 0,
    constraint_type        TEXT NOT NULL DEFAULT 'NONE',
    hard_or_soft           TEXT,
    weight                 INTEGER,
    direction              TEXT,
    explanation_text       TEXT,
    options_json           TEXT,
    sort_order             INTEGER,
    UNIQUE (activity_plan_id, key),
    -- COLUMN-storage fields must say which column; CUSTOM-storage fields must not.
    CHECK ((storage_kind = 'COLUMN') = (column_name IS NOT NULL))
);

-- Closes the NULL-collision hole described above: enforces unique keys among global field rows.
CREATE UNIQUE INDEX idx_field_definition_global_key ON field_definition (key) WHERE activity_plan_id IS NULL;

CREATE INDEX idx_field_definition_activity_plan_id ON field_definition (activity_plan_id);

INSERT INTO field_definition
    (id, activity_plan_id, key, label, field_type, is_standard, storage_kind, column_name, affects_optimization, constraint_type, hard_or_soft, weight, direction, explanation_text, sort_order)
VALUES
('019f1fd9-7034-7000-b1dd-30ce275158b5', NULL, 'rankingPoints', 'Seriespelsranking', 'number', 1, 'COLUMN', 'ranking_points', 1, 'LEVEL_BALANCE_INPUT', 'SOFT', 100, 'HIGHER_BETTER', 'Ranking från seriespel, används som nivåkälla.', 1),
('019f1fd9-7034-7001-908b-4674bd143058', NULL, 'previousGroupName', 'Tidigare träningsgrupp', 'text', 1, 'COLUMN', 'previous_group_name', 0, 'NONE', 'INFO', NULL, NULL, 'Vilken grupp spelaren gick i förra terminen.', 2),
('019f1fd9-7034-7002-b53d-2934a81d5cd1', NULL, 'previousGroupLevel', 'Tidigare gruppnivå', 'number', 1, 'COLUMN', 'previous_group_level', 1, 'LEVEL_BALANCE_INPUT', 'SOFT', 30, 'HIGHER_BETTER', 'Nivå härledd från tidigare gruppens placering.', 3),
('019f1fd9-7034-7003-b56e-f0d753655e20', NULL, 'manualLevelScore', 'Manuell nivåscore', 'number', 1, 'COLUMN', 'manual_level_score', 1, 'LEVEL_BALANCE_INPUT', 'SOFT', 100, 'HIGHER_BETTER', 'Kansliets egen manuella nivåbedömning, används före ranking.', 4),
('019f1fd9-7034-7004-a027-7cbb5e30833d', NULL, 'levelConfidence', 'Nivåsäkerhet', 'number', 1, 'COLUMN', 'level_confidence', 0, 'NONE', 'INFO', NULL, NULL, 'Hur säker den beräknade nivån är (0.0-1.0).', 5),
('019f1fd9-7034-7005-a84b-7fa7a56664c3', NULL, 'canTimes', 'Kan tider', 'timeRelation', 1, 'CUSTOM', NULL, 1, 'TIME_AVAILABILITY', 'HARD', NULL, NULL, 'Tider spelaren kan delta på (whitelist).', 6),
('019f1fd9-7034-7006-9d55-307e82ac0f47', NULL, 'cannotTimes', 'Kan inte tider', 'timeRelation', 1, 'CUSTOM', NULL, 1, 'TIME_AVAILABILITY', 'HARD', NULL, NULL, 'Tider spelaren inte kan delta på (blacklist).', 7),
('019f1fd9-7034-7007-9a55-b93abb90203a', NULL, 'preferTimes', 'Föredrar tider', 'timeRelation', 1, 'CUSTOM', NULL, 1, 'TIME_PREFERENCE', 'SOFT', 40, NULL, 'Tider spelaren helst vill ha, men inte kräver.', 8),
('019f1fd9-7034-7008-8378-a6a64f0391cf', NULL, 'playWith', 'Vill spela med', 'personRelation', 1, 'CUSTOM', NULL, 1, 'SAME_GROUP', 'SOFT', 80, NULL, 'Andra spelare denna spelare gärna vill dela grupp med.', 9),
('019f1fd9-7034-7009-a448-84aecb322bc6', NULL, 'mustPlayWith', 'Måste spela med', 'personRelation', 1, 'CUSTOM', NULL, 1, 'SAME_GROUP', 'HARD', NULL, NULL, 'Andra spelare denna spelare måste dela grupp med.', 10),
('019f1fd9-7034-700a-b953-38d8492a876d', NULL, 'avoidPlayWith', 'Vill inte spela med', 'personRelation', 1, 'CUSTOM', NULL, 1, 'DIFFERENT_GROUP', 'SOFT', 60, NULL, 'Andra spelare denna spelare helst inte vill dela grupp med.', 11),
('019f1fd9-7034-700b-b071-84bafa3d52b4', NULL, 'wantsCoach', 'Vill ha tränare', 'coachRelation', 1, 'CUSTOM', NULL, 1, 'COACH_PREFERENCE', 'SOFT', 50, NULL, 'Önskad tränare för spelaren/gruppen.', 12),
('019f1fd9-7034-700c-894a-330e3ff9df2d', NULL, 'mustHaveCoach', 'Måste ha tränare', 'coachRelation', 1, 'CUSTOM', NULL, 1, 'COACH_PREFERENCE', 'HARD', NULL, NULL, 'Tränare spelaren/gruppen måste ha.', 13),
('019f1fd9-7034-700d-b473-4cd06bcee3e8', NULL, 'cannotHaveCoach', 'Kan inte ha tränare', 'coachRelation', 1, 'CUSTOM', NULL, 1, 'COACH_FORBIDDEN', 'HARD', NULL, NULL, 'Tränare spelaren/gruppen inte får ha.', 14),
('019f1fd9-7034-700e-8ac3-2b2dd5c24e3d', NULL, 'newToClub', 'Ny i klubben', 'boolean', 1, 'CUSTOM', NULL, 0, 'NONE', 'INFO', NULL, NULL, 'Markerar spelare som är nya i klubben.', 15),
('019f1fd9-7034-700f-adb9-951add3241b7', NULL, 'needsManualReview', 'Behöver manuell bedömning', 'boolean', 1, 'COLUMN', 'manual_review_flag', 0, 'NONE', 'INFO', NULL, NULL, 'Flaggar deltagare som kansliet manuellt behöver granska.', 16),
('019f1fd9-7034-7010-873a-395a77e5716a', NULL, 'priority', 'Prioritet', 'number', 1, 'CUSTOM', NULL, 1, 'PRIORITY', 'MEDIUM', 100, 'HIGHER_BETTER', 'Prioritet vid platsbrist; styr vem som hamnar på kölistan sist.', 17),
('019f1fd9-7034-7011-9298-32875042d415', NULL, 'importedComment', 'Kommentar från anmälan', 'text', 1, 'COLUMN', 'imported_comment', 0, 'NONE', 'INFO', NULL, NULL, 'Fritext från anmälningsformuläret. Känslig — används aldrig i optimeringen.', 18),
('019f1fd9-7034-7012-a10d-ebafa0e3b425', NULL, 'internalNote', 'Intern kommentar', 'text', 1, 'COLUMN', 'internal_note', 0, 'NONE', 'INFO', NULL, NULL, 'Kansliets interna anteckning. Känslig — används aldrig i optimeringen.', 19);

-- ─────────────────────────────────────────────────────────────────────────────
-- custom_field_value (spec §7.14)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE custom_field_value (
    id                    TEXT PRIMARY KEY,
    field_definition_id   TEXT NOT NULL REFERENCES field_definition (id) ON DELETE CASCADE,
    entity_type           TEXT NOT NULL,
    entity_id             TEXT NOT NULL,
    value_json            TEXT,
    UNIQUE (field_definition_id, entity_type, entity_id)
);

CREATE INDEX idx_custom_field_value_entity ON custom_field_value (entity_type, entity_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- constraint_weight_config (spec §7.16) — per-plan override of a constraint_definition's defaults.
-- Empty until a plan's weights are edited (M6b); structure only in M1.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE constraint_weight_config (
    id                  TEXT PRIMARY KEY,
    activity_plan_id    TEXT NOT NULL REFERENCES activity_plan (id) ON DELETE CASCADE,
    constraint_key      TEXT NOT NULL REFERENCES constraint_definition (key) ON DELETE CASCADE,
    hard_or_soft        TEXT NOT NULL,
    weight              INTEGER NOT NULL,
    enabled             INTEGER NOT NULL DEFAULT 1,
    UNIQUE (activity_plan_id, constraint_key)
);
