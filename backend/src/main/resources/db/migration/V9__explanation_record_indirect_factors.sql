-- v0.3.0 WI-5 (Opus review finding 6, audit completeness): explanation_record must capture the new
-- second-order "via a coach" reasons alongside the first-order factor lists it already logs, so the
-- audit trail matches what the user was actually shown. Nullable ADD COLUMN (allowed per CLAUDE.md's
-- SQLite rules - only ALTER COLUMN is forbidden); NULL for every pre-v0.3.0 row, JSON array (possibly
-- empty) for rows written after this migration. The broken-wish coachBindingSv annotation needs no
-- column of its own: it is a field INSIDE the BrokenWishView records already serialized into
-- broken_preferences_json.
ALTER TABLE explanation_record ADD COLUMN indirect_factors_json TEXT;
