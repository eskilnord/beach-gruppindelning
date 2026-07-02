-- V4__resources.sql
--
-- M5 (resources, coaches, capacity — backend/docs/m5-notes.md). No new tables: venue, court,
-- time_slot, training_block, coach_profile, coach_time_slot were all already created by V1 in
-- anticipation of M5 (confirmed by re-reading V1 before writing any code, same practice as M4).
--
-- 1) Courts auto-created by the block-generation endpoint
-- (PUT /api/plans/{planId}/time-slots/{slotId}/courts) are named "Bana 1".."Bana N" within a venue
-- and looked up by that name to stay idempotent (docs/plan.md M5 row: "IDEMPOTENT: same count twice
-- = no change"). A UNIQUE index (not a column change, so no create-copy-rename needed per CLAUDE.md
-- "SQLite rules") both speeds up that lookup and gives a free 409 via the existing
-- ApiExceptionHandler/DataIntegrityViolationException path if a caller ever races two court-creating
-- requests for the same venue+name, or manually creates a duplicate via POST /api/courts.
CREATE UNIQUE INDEX idx_court_venue_id_name ON court (venue_id, name);

-- 2) Why a block is inactive (M5 review fix): a manual §12.3 exception ('MANUAL', set by
-- PATCH /api/training-blocks/{id} {active:false}) must survive a later court-count regeneration,
-- while a block deactivated by shrinking the declared court count ('SHRINK') is fair game for
-- automatic reactivation when the count grows back. NULL while a block is active (reactivation
-- always clears it). Plain ADD COLUMN is supported natively by SQLite (only ALTER/DROP COLUMN
-- reshaping needs the create-copy-rename idiom per CLAUDE.md).
ALTER TABLE training_block ADD COLUMN deactivation_source TEXT;
