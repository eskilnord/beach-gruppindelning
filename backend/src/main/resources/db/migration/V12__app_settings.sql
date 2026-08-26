-- V12__app_settings.sql
--
-- B3 (v0.6.0): a tiny generic key/value table for app-wide UI settings, starting with a single
-- global "ui.mode" key (SIMPLE|ADVANCED - AppSettingsController) persisted across app restarts.
-- Not per-plan, not per-user - this desktop app has no user model beyond the shared X-GP-Token, so
-- one global row per key is enough. Seeded with the SIMPLE default so a fresh install (and every
-- existing install upgrading through this migration) starts in the simpler mode without requiring
-- the frontend to special-case "no row yet".
--
-- Numbered V12, ahead of V10/V11 but BEHIND the not-yet-landed weights migration despite this
-- feature (B3) landing after it in review order: the weights migration is reserved as V13. Had
-- app_settings taken V13 instead, a dev database that already ran V13__app_settings would hit
-- Flyway's out-of-order-migration refusal the moment V12 (weights) showed up later with a lower
-- version number than an already-applied migration - Flyway requires strictly increasing version
-- numbers to be applied in order. Numbering this one V12 keeps every dev DB's applied-migration
-- sequence monotonic regardless of merge order.

CREATE TABLE app_setting (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- Fixed literal timestamp (not strftime('now')) so the migration is deterministic across runs/CI,
-- and so the seeded shape matches the precision style of Instant.now().toString() writes
-- (AppSettingsRepository.upsert) rather than SQLite's strftime fractional-second format.
INSERT INTO app_setting (key, value, updated_at) VALUES ('ui.mode', 'SIMPLE', '2026-08-26T00:00:00Z');
