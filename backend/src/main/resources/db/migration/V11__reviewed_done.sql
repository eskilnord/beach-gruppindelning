-- V11__reviewed_done.sql
--
-- WP3 ("Spara och markera som färdig"): a reversible per-row council workflow flag for
-- participant_profile and coach_profile, letting a reviewer mark a player/coach as reviewed and
-- toggle it back. Plain ADD COLUMN is safe here (SQLite has no ALTER COLUMN, but adding a
-- non-null column with a DEFAULT needs no create-copy-rename per CLAUDE.md's SQLite rules).
--
-- This is a PURE workflow marker: it has no effect on solver input, exports, or explanations, and
-- deliberately gets no field_definition row - it must never appear in Fältbyggaren or in
-- import/export column mapping.

ALTER TABLE participant_profile ADD COLUMN reviewed_done INTEGER NOT NULL DEFAULT 0;
ALTER TABLE coach_profile ADD COLUMN reviewed_done INTEGER NOT NULL DEFAULT 0;
