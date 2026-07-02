-- V6__soft_constraints_locks_saved_plan.sql
--
-- M6b (solver completion - backend/docs/m6b-notes.md). Two things:
--
-- 1) One new constraint_definition row: coachPreferredTimeSlot (SOFT, ~20) - the SOFT reward
--    counterpart of coachAvailabilityHard, consuming the M5 tri-state's PREFERRED kind
--    (coach_time_slot.kind = 'PREFERRED', already modeled since M5; this is its first solver
--    consumer). Every other M6b SOFT constraint (groupSizeTarget, groupMinSizeSoft, levelBalance,
--    groupOrderByLevel, previousGroupContinuity, timePreferenceSoft, sameGroupSoft,
--    differentGroupSoft, coachLevelFit, coachPreferenceSoft, lateTimeForLowerGroups) already has a
--    constraint_definition row seeded by V2 - only the Constraint Streams code was missing (added
--    in GroupPlanConstraintProvider this milestone). The empty-group complements
--    (groupSizeTargetEmpty/groupMinSizeEmpty) and the two lateTimeForLowerGroups directions
--    deliberately do NOT get their own rows - see ConstraintKeys.COMPLEMENTS_OF and
--    GroupPlanConstraintProvider#lateTimeForLowerGroups for the fan-out/shared-row rationale.
--
-- 2) saved_plan / saved_plan_resource_usage (docs/design/02-product-data-ui.md §1, design §6.2,
--    spec §14.1/§14.3): the persistence side of cross-plan blocking. Full "spara plan" (snapshot,
--    scoring, status transitions) is M8 scope per docs/plan.md; this milestone needs only the
--    tables plus a minimal internal materialization service (se.klubb.groupplanner.savedplan
--    .SavedPlanService) so SolverInputAssembler can load a LOCKED saved plan's resource usage into
--    SavedPlanResourceUsage facts (§10.24a/b/c) and ConflictService-adjacent tests can exercise the
--    Anna example (spec §13.2) end-to-end. saved_plan.status mirrors activity_plan.status's value
--    domain (draft|saved|locked|published|archived) but is independently tracked: an activity_plan
--    is the live working plan; a saved_plan is an immutable-in-spirit snapshot of it taken at
--    "spara"/"lås" time, so a later edit to the live plan does not retroactively change what an
--    already-locked OTHER plan blocks.

INSERT INTO constraint_definition (key, label, description, constraint_category, default_weight, hard_or_soft, enabled) VALUES
('coachPreferredTimeSlot', 'Tränarens föredragna tid', 'Belöna tränare som får en tid de markerat som föredragen.', 'COACH', 20, 'SOFT', 1);

CREATE TABLE saved_plan (
    id                    TEXT PRIMARY KEY,
    activity_plan_id      TEXT NOT NULL REFERENCES activity_plan (id) ON DELETE CASCADE,
    name                  TEXT NOT NULL,
    status                TEXT NOT NULL DEFAULT 'saved',   -- draft|saved|locked|published|archived
    snapshot_json         TEXT,
    score                 TEXT,
    optimization_run_id   TEXT REFERENCES optimization_run (id) ON DELETE SET NULL,
    created_at            TEXT NOT NULL,
    updated_at            TEXT NOT NULL
);

CREATE INDEX idx_saved_plan_activity_plan_id ON saved_plan (activity_plan_id);
CREATE INDEX idx_saved_plan_status ON saved_plan (status);

-- One row per (group -> block) assignment's player/coach usage, materialized at save/lock time
-- (se.klubb.groupplanner.savedplan.SavedPlanService). day_of_week/start_time/end_time/court_id are
-- copied from the source training_block/time_slot at materialization time (a deliberate snapshot,
-- not a live FK to time_slot/court, so a later edit to the SOURCE plan's schedule does not silently
-- change what an already-locked saved_plan blocks). role: PLAYER|COACH - court blocking (§10.24c)
-- is derived from the court_id already present on every PLAYER/COACH row, not a third role.
CREATE TABLE saved_plan_resource_usage (
    id              TEXT PRIMARY KEY,
    saved_plan_id   TEXT NOT NULL REFERENCES saved_plan (id) ON DELETE CASCADE,
    person_id       TEXT REFERENCES person (id) ON DELETE CASCADE,
    role            TEXT NOT NULL,   -- PLAYER|COACH
    day_of_week     TEXT,
    date            TEXT,
    start_time      TEXT NOT NULL,
    end_time        TEXT NOT NULL,
    court_id        TEXT REFERENCES court (id) ON DELETE SET NULL
);

CREATE INDEX idx_saved_plan_resource_usage_saved_plan_id ON saved_plan_resource_usage (saved_plan_id);
CREATE INDEX idx_saved_plan_resource_usage_person_id ON saved_plan_resource_usage (person_id);
