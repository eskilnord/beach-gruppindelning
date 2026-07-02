-- V7__explainability.sql
--
-- M7 (explainability + what-if, backend/docs/m7-notes.md). Two things:
--
-- 1) Staleness plumbing (docs/design/04-solver.md §11.6): activity_plan.plan_revision, an integer
--    bumped every time something happens that could change what an already-computed explanation
--    means (a manual move, a lock/unlock, a participant/field edit, OR completing a new solve/greedy
--    run — see backend/docs/m7-notes.md "invalidation surface" for why a fresh solve's writeback
--    must also bump it: assemble()-based explanation/what-if computation always reads CURRENT
--    player_assignment/training_group/coach_assignment rows, so an older run's cached explanation
--    would otherwise silently start describing a NEWER run's placements the moment anyone re-solves).
--    optimization_run.plan_revision snapshots the plan_revision value immediately AFTER that run's
--    own writeback (+ bump) — this is the "basedOnRevision" every explanation/what-if response
--    compares against the plan's current plan_revision to compute {stale: basedOnRevision !=
--    currentRevision}.
--
-- 2) explanation_record (spec §7.18, docs/design/02-product-data-ui.md §1): a write-through
--    persistence log of computed person-level explanations, keyed (optimization_run_id,
--    participant_profile_id). The SERVING path uses an in-memory LRU (se.klubb.groupplanner.explain
--    .ExplanationCache) per design §11.4's "lazy-computation-with-cache" decision - this table exists
--    so explanations survive a process restart / are auditable, not as the read-through cache itself
--    (see backend/docs/m7-notes.md for the full reasoning).

ALTER TABLE activity_plan ADD COLUMN plan_revision INTEGER NOT NULL DEFAULT 0;

ALTER TABLE optimization_run ADD COLUMN plan_revision INTEGER NOT NULL DEFAULT 0;

CREATE TABLE explanation_record (
    id                        TEXT PRIMARY KEY,
    optimization_run_id       TEXT NOT NULL REFERENCES optimization_run (id) ON DELETE CASCADE,
    participant_profile_id    TEXT NOT NULL REFERENCES participant_profile (id) ON DELETE CASCADE,
    selected_group_id         TEXT REFERENCES training_group (id) ON DELETE SET NULL,
    positive_factors_json     TEXT,
    negative_factors_json     TEXT,
    alternative_groups_json   TEXT,
    broken_preferences_json   TEXT,
    score_impact_json         TEXT,
    created_at                TEXT NOT NULL,
    UNIQUE (optimization_run_id, participant_profile_id)
);

CREATE INDEX idx_explanation_record_run_id ON explanation_record (optimization_run_id);
