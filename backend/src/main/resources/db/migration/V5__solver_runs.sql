-- V5__solver_runs.sql
--
-- M6a (Timefold solver core - backend/docs/m6a-notes.md). Three things:
--
-- 1) optimization_run (docs/design/02-product-data-ui.md §1): one row per solve. CRITICAL privacy
--    rule (docs/plan.md "Privacy corrections", CLAUDE.md): input_snapshot_json/result_summary_json
--    must EXCLUDE imported_comment/internal_note - enforced at the application layer
--    (SolverInputAssembler/OptimizationRunService never read those two columns for this purpose),
--    and covered by an extended leak test (se.klubb.groupplanner.solver.run
--    .OptimizationRunSnapshotLeakTest).
--
-- 2) New constraint_definition rows for the constraints this design doc's per-constraint table
--    (§4) requires that V1/V2 (seeded before the solver design was finalized) never anticipated:
--    coachMaxGroups (spec §13.1 "Max antal grupper", no § number), coachWishRequired/
--    coachWishForbidden (§10.21b/c - the seeded coachPreferenceSoft only covers the SOFT/WANT case),
--    savedPlanPersonBlocked/savedPlanCoachBlocked/savedPlanCourtBlocked (§10.24a/b/c - the seeded
--    savedPlanResourceBlock anticipated ONE constraint, but the finalized design independently
--    justifies and weights person/coach/court blocking separately). The original
--    savedPlanResourceBlock row is deliberately left in place, unused: FlywayMigrationTest and
--    ConstraintWeightService.NEVER_DISABLEABLE_KEYS already reference it by exact string, and
--    renaming/deleting it would churn already-tested M1/M4 code for a cosmetic gain - it becomes a
--    harmless orphan until a future migration retires it (see m6a-notes.md).
--    unassignedPlayer (MEDIUM, §2.1) is the solver's own reserved waitlist penalty (ADR-006);
--    ConstraintWeightService's generic MEDIUM guardrail (M4) already protects it with zero code
--    changes required here.
--
-- 3) One new standard field, mustNotPlayWith (personRelation, DIFFERENT_GROUP, HARD): the spec's
--    §9.2 19-field list only has a SOFT "Vill inte spela med" (avoidPlayWith -> differentGroupSoft),
--    but §10.13 differentGroupHard is already seeded as a HARD constraint and this design doc's
--    WishType enum (§1.2) defines MUST_DIFFERENT as a first-class wish type feeding it - and the
--    committed anonymized test datasets' not_with_ids columns are documented as "hard, symmetric"
--    (test-data/datasets/large-120/README.md). Without this field, differentGroupHard would have no
--    real data source. Mirrors mustPlayWith's shape exactly (personRelation/HARD, no weight).

CREATE TABLE optimization_run (
    id                        TEXT PRIMARY KEY,
    activity_plan_id          TEXT NOT NULL REFERENCES activity_plan (id) ON DELETE CASCADE,
    input_snapshot_json       TEXT,
    constraint_weights_json   TEXT,
    score                     TEXT,
    status                    TEXT NOT NULL,   -- QUEUED|SOLVING|FINISHED|CANCELLED|FAILED
    started_at                TEXT,
    finished_at               TEXT,
    duration_ms               INTEGER,
    result_summary_json       TEXT
);

CREATE INDEX idx_optimization_run_activity_plan_id ON optimization_run (activity_plan_id);

INSERT INTO constraint_definition (key, label, description, constraint_category, default_weight, hard_or_soft, enabled) VALUES
('coachMaxGroups', 'Max antal grupper per tränare', 'En tränare får inte tilldelas fler grupper än sitt maxantal.', 'COACH', 1, 'HARD', 1),
('coachWishRequired', 'Måste ha tränare (hård)', 'Om spelare/grupp måste ha en viss tränare måste den tränaren tilldelas.', 'COACH', 1, 'HARD', 1),
('coachWishForbidden', 'Kan inte ha tränare (hård)', 'Spelare/grupp får inte tilldelas en förbjuden tränare.', 'COACH', 1, 'HARD', 1),
('savedPlanPersonBlocked', 'Blockering: spelare i sparad plan', 'En spelare som redan är bokad i en annan sparad/låst plan får inte krocka i tid.', 'LOCK', 1, 'HARD', 1),
('savedPlanCoachBlocked', 'Blockering: tränare i sparad plan', 'En tränare som redan är bokad i en annan sparad/låst plan får inte krocka i tid.', 'LOCK', 1, 'HARD', 1),
('savedPlanCourtBlocked', 'Blockering: bana i sparad plan', 'En bana som redan är bokad i en annan sparad/låst plan får inte krocka i tid.', 'LOCK', 1, 'HARD', 1),
('unassignedPlayer', 'Oplacerad spelare (kölista)', 'Minimera antal oplacerade spelare, viktat efter prioritet. Reserverad MEDIUM-nivå (ADR-006).', 'WAITLIST', 100, 'MEDIUM', 1);

INSERT INTO field_definition
    (id, activity_plan_id, key, label, field_type, is_standard, storage_kind, column_name, affects_optimization, constraint_type, hard_or_soft, weight, direction, explanation_text, sort_order)
VALUES
('019f1fda-0000-7000-8000-000000000001', NULL, 'mustNotPlayWith', 'Måste inte spela med', 'personRelation', 1, 'CUSTOM', NULL, 1, 'DIFFERENT_GROUP', 'HARD', NULL, NULL, 'Andra spelare denna spelare inte får dela grupp med.', 20);
