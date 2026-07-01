-- V1__core.sql
--
-- Core schema per docs/design/02-product-data-ui.md §1 (primary spec), corrected per docs/plan.md
-- and ADR-004. Rules from CLAUDE.md "SQLite rules": PKs are TEXT UUIDv7 (see se.klubb.groupplanner
-- .util.Uuid7), timestamps are TEXT ISO-8601 UTC, booleans are INTEGER 0/1. Schema is owned
-- exclusively by Flyway migrations; SQLite has no ALTER COLUMN, so future reshaping migrations use
-- the create-copy-rename idiom (see backend/docs/m1-notes.md).
--
-- ON DELETE choices (documented per-table below; full rationale in backend/docs/m1-notes.md):
--   * activity_plan / participant_profile / coach_profile / time_slot / training_block /
--     training_group rows are "plan-scoped" data: deleting the owning season_plan/activity_plan
--     cascades and removes them, since they have no meaning outside their parent plan.
--   * person rows are shared, long-lived entities referenced from potentially many activity
--     plans across seasons; deleting a person while they still have participant/coach profiles is
--     blocked (ON DELETE RESTRICT) so historical plan data is never silently destroyed.
--   * venue/court are foundational scheduling resources that are rarely deleted after creation;
--     deleting one while courts/training_blocks reference it is blocked (ON DELETE RESTRICT) to
--     avoid silently wiping schedule data — the caller must first remove/deactivate the dependents.
--   * training_group.assigned_training_block_id uses ON DELETE SET NULL: if its block disappears
--     the group simply becomes unscheduled rather than being deleted itself.
--   * player_assignment.group_id uses ON DELETE SET NULL: if a group disappears the participant's
--     assignment record survives as "unassigned" (consistent with the waitlist model) rather than
--     being deleted.
--   * coach_assignment.group_id uses ON DELETE CASCADE: a coach assignment has no independent
--     meaning once its group is gone (unlike player_assignment, coaches have no waitlist concept).
--
-- Note: `foreign_keys=ON` is a per-connection pragma, not a schema setting - it is applied by
-- se.klubb.groupplanner.config.DataSourceConfig on every connection this application opens
-- (including the one Flyway itself uses to run this migration), so it is deliberately NOT repeated
-- here as a PRAGMA statement: Flyway's SQLite parser classifies PRAGMA as non-transactional and
-- CREATE TABLE as transactional, and refuses to mix the two in one migration ("mixed" is false).

-- ─────────────────────────────────────────────────────────────────────────────
-- person (spec §7.1) — shared across seasons/activity plans.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE person (
    id                   TEXT PRIMARY KEY,
    first_name           TEXT NOT NULL,
    last_name            TEXT NOT NULL,
    display_name         TEXT,
    email                TEXT,
    phone                TEXT,
    external_id          TEXT UNIQUE,
    can_be_participant   INTEGER NOT NULL DEFAULT 1,
    can_be_coach         INTEGER NOT NULL DEFAULT 0,
    notes                TEXT,
    created_at           TEXT NOT NULL,
    updated_at           TEXT NOT NULL
);

-- ─────────────────────────────────────────────────────────────────────────────
-- season_plan (spec §7.5)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE season_plan (
    id           TEXT PRIMARY KEY,
    name         TEXT NOT NULL,
    start_date   TEXT,
    end_date     TEXT,
    status       TEXT NOT NULL DEFAULT 'active',
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL
);

-- ─────────────────────────────────────────────────────────────────────────────
-- activity_plan (spec §7.6) — statuses: draft|saved|locked|published|archived
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE activity_plan (
    id                          TEXT PRIMARY KEY,
    season_plan_id              TEXT NOT NULL REFERENCES season_plan (id) ON DELETE CASCADE,
    name                        TEXT NOT NULL,
    category                    TEXT,
    status                      TEXT NOT NULL DEFAULT 'draft',
    default_group_target_size   INTEGER,
    default_group_min_size      INTEGER,
    default_group_max_size      INTEGER,
    created_at                  TEXT NOT NULL,
    updated_at                  TEXT NOT NULL
);

CREATE INDEX idx_activity_plan_season_plan_id ON activity_plan (season_plan_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- participant_profile (spec §7.2). imported_comment/internal_note are sensitive: never fed to the
-- solver, never included in *_json snapshots, never in the default export (CLAUDE.md confidentiality
-- rules) — enforced at the application layer, not by the schema.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE participant_profile (
    id                     TEXT PRIMARY KEY,
    person_id              TEXT NOT NULL REFERENCES person (id) ON DELETE RESTRICT,
    activity_plan_id       TEXT NOT NULL REFERENCES activity_plan (id) ON DELETE CASCADE,
    ranking_points         REAL,
    ranking_source         TEXT,
    previous_group_name    TEXT,
    previous_group_level   REAL,
    estimated_level        REAL,
    level_confidence       REAL,
    manual_level_score     REAL,
    imported_comment       TEXT,
    internal_note          TEXT,
    manual_review_flag     INTEGER NOT NULL DEFAULT 0,
    waitlisted             INTEGER NOT NULL DEFAULT 0,
    UNIQUE (person_id, activity_plan_id)
);

CREATE INDEX idx_participant_profile_activity_plan_id ON participant_profile (activity_plan_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- coach_profile (spec §7.3)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE coach_profile (
    id                             TEXT PRIMARY KEY,
    person_id                      TEXT NOT NULL REFERENCES person (id) ON DELETE RESTRICT,
    activity_plan_id               TEXT NOT NULL REFERENCES activity_plan (id) ON DELETE CASCADE,
    coach_level                    REAL,
    can_coach_min_level            REAL,
    can_coach_max_level            REAL,
    max_groups_per_day             INTEGER,
    max_groups_per_week            INTEGER,
    can_also_train_as_participant  INTEGER NOT NULL DEFAULT 0,
    notes                          TEXT,
    UNIQUE (person_id, activity_plan_id)
);

CREATE INDEX idx_coach_profile_activity_plan_id ON coach_profile (activity_plan_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- venue (spec §7.7) / court (spec §7.8)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE venue (
    id      TEXT PRIMARY KEY,
    name    TEXT NOT NULL,
    notes   TEXT
);

CREATE TABLE court (
    id         TEXT PRIMARY KEY,
    venue_id   TEXT NOT NULL REFERENCES venue (id) ON DELETE RESTRICT,
    name       TEXT NOT NULL,
    active     INTEGER NOT NULL DEFAULT 1,
    notes      TEXT
);

CREATE INDEX idx_court_venue_id ON court (venue_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- time_slot (spec §7.9) — day_of_week stored as java.time.DayOfWeek name (MONDAY..SUNDAY);
-- start_time/end_time stored as "HH:mm" text.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE time_slot (
    id                 TEXT PRIMARY KEY,
    activity_plan_id   TEXT NOT NULL REFERENCES activity_plan (id) ON DELETE CASCADE,
    day_of_week        TEXT,
    date               TEXT,
    start_time         TEXT NOT NULL,
    end_time           TEXT NOT NULL,
    duration_minutes   INTEGER,
    label              TEXT
);

CREATE INDEX idx_time_slot_activity_plan_id ON time_slot (activity_plan_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- coach_time_slot (spec §7.3 availableTimeSlotIds/unavailableTimeSlotIds/preferredTimeSlotIds,
-- normalized) — kind: AVAILABLE|UNAVAILABLE|PREFERRED
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE coach_time_slot (
    id                 TEXT PRIMARY KEY,
    coach_profile_id   TEXT NOT NULL REFERENCES coach_profile (id) ON DELETE CASCADE,
    time_slot_id       TEXT NOT NULL REFERENCES time_slot (id) ON DELETE CASCADE,
    kind               TEXT NOT NULL,
    UNIQUE (coach_profile_id, time_slot_id)
);

-- The UNIQUE(coach_profile_id, time_slot_id) index only accelerates lookups by coach; slot-first
-- lookups ("which coaches are available at slot X?") need their own index.
CREATE INDEX idx_coach_time_slot_time_slot_id ON coach_time_slot (time_slot_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- training_block (spec §7.10) — one court reserved for one time slot.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE training_block (
    id                 TEXT PRIMARY KEY,
    time_slot_id       TEXT NOT NULL REFERENCES time_slot (id) ON DELETE CASCADE,
    court_id           TEXT NOT NULL REFERENCES court (id) ON DELETE RESTRICT,
    activity_plan_id   TEXT NOT NULL REFERENCES activity_plan (id) ON DELETE CASCADE,
    active             INTEGER NOT NULL DEFAULT 1,
    locked             INTEGER NOT NULL DEFAULT 0,
    UNIQUE (time_slot_id, court_id)
);

CREATE INDEX idx_training_block_activity_plan_id ON training_block (activity_plan_id);
-- Court-first lookups (e.g. "which blocks use court X?" when deactivating a court) — the
-- UNIQUE(time_slot_id, court_id) index only covers slot-first access.
CREATE INDEX idx_training_block_court_id ON training_block (court_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- training_group (spec §7.4) — "group" is a SQL keyword, table is named training_group.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE training_group (
    id                          TEXT PRIMARY KEY,
    activity_plan_id            TEXT NOT NULL REFERENCES activity_plan (id) ON DELETE CASCADE,
    name                        TEXT NOT NULL,
    group_order                 INTEGER,
    category                    TEXT,
    min_size                    INTEGER,
    target_size                 INTEGER,
    max_size                    INTEGER,
    required_coach_count        INTEGER NOT NULL DEFAULT 1,
    level_min                   REAL,
    level_max                   REAL,
    assigned_training_block_id  TEXT REFERENCES training_block (id) ON DELETE SET NULL,
    locked                      INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_training_group_activity_plan_id ON training_group (activity_plan_id);
-- Block-first lookups ("which group holds block X?") and faster ON DELETE SET NULL resolution.
CREATE INDEX idx_training_group_assigned_training_block_id ON training_group (assigned_training_block_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- player_assignment (spec §7.11) — source: imported|manual|solver|locked
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE player_assignment (
    id                          TEXT PRIMARY KEY,
    participant_profile_id      TEXT NOT NULL UNIQUE REFERENCES participant_profile (id) ON DELETE CASCADE,
    group_id                    TEXT REFERENCES training_group (id) ON DELETE SET NULL,
    locked                      INTEGER NOT NULL DEFAULT 0,
    source                      TEXT NOT NULL
);

CREATE INDEX idx_player_assignment_group_id ON player_assignment (group_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- coach_assignment (spec §7.12)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE coach_assignment (
    id                 TEXT PRIMARY KEY,
    coach_profile_id   TEXT NOT NULL REFERENCES coach_profile (id) ON DELETE CASCADE,
    group_id           TEXT NOT NULL REFERENCES training_group (id) ON DELETE CASCADE,
    locked             INTEGER NOT NULL DEFAULT 0,
    source             TEXT NOT NULL
);

CREATE INDEX idx_coach_assignment_group_id ON coach_assignment (group_id);
-- Coach-first lookups ("which groups does coach X have?") and faster ON DELETE CASCADE resolution.
CREATE INDEX idx_coach_assignment_coach_profile_id ON coach_assignment (coach_profile_id);
