-- V3__import.sql
--
-- Import wizard engine tables (docs/design/02-product-data-ui.md §1/§2, ../utkast-kravspec.txt §8),
-- per docs/plan.md's M3 row and "Import pipeline corrections (red-team)" section. Follows the same
-- conventions as V1/V2: TEXT UUIDv7 PKs (se.klubb.groupplanner.util.Uuid7), timestamps TEXT
-- ISO-8601 UTC, booleans INTEGER 0/1, JSON in `*_json` TEXT columns. No PRAGMA statements here
-- either - see the note in V1__core.sql (foreign_keys=ON is a per-connection pragma applied by
-- se.klubb.groupplanner.config.DataSourceConfig, not a schema concern).
--
-- Note on numbering: docs/plan.md's "SQLite schema" section lists a 4-migration split
-- (V1__core, V2__seed..., V3__runs_explanations_plans, V4__import) that predates the milestone
-- restructuring in the same document (M3 = import wizard, M6a = optimization_run, M7 =
-- explanation_record). Only V1/V2 exist on disk today, so this milestone's tables land in
-- V3__import.sql; the optimization_run/explanation_record tables will get their own migration
-- number when M6a/M7 are implemented (documented in backend/docs/m3-notes.md).

-- ─────────────────────────────────────────────────────────────────────────────
-- import_template (spec §8.3 step 5/8 "Spara som importmall") — a reusable column-mapping,
-- looked up by header_hash (see se.klubb.groupplanner.importer.HeaderHash) so a second import of a
-- structurally-identical file can reuse its mapping with no re-mapping step.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE import_template (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    header_hash   TEXT NOT NULL,
    mapping_json  TEXT NOT NULL,
    created_at    TEXT NOT NULL
);

-- Template suggestion on upload looks up by header_hash first (docs/plan.md: "reusable import
-- templates keyed by header hash"); this index makes that lookup O(log n) instead of a scan.
CREATE INDEX idx_import_template_header_hash ON import_template (header_hash);

-- ─────────────────────────────────────────────────────────────────────────────
-- import_run (spec §8.3 step 8 "Importera") — one row per commit, an audit trail of what was
-- imported/skipped/warned about, and which decisions the user made for ambiguous rows (spec §8.7).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE import_run (
    id                   TEXT PRIMARY KEY,
    activity_plan_id     TEXT NOT NULL REFERENCES activity_plan (id) ON DELETE CASCADE,
    file_name            TEXT NOT NULL,
    sheet_name           TEXT NOT NULL,
    import_template_id   TEXT REFERENCES import_template (id) ON DELETE SET NULL,
    total_rows           INTEGER NOT NULL,
    imported_rows        INTEGER NOT NULL,
    skipped_rows         INTEGER NOT NULL,
    warnings_json        TEXT,
    decisions_json       TEXT,
    created_at           TEXT NOT NULL
);

CREATE INDEX idx_import_run_activity_plan_id ON import_run (activity_plan_id);
