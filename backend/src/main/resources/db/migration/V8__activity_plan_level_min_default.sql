-- V8__activity_plan_level_min_default.sql
--
-- v0.3.0 user feedback ("Jag vill kunna ställa in standardstorlekar target/max samt min-nivå"):
-- activity_plan already had default_group_target_size/default_group_min_size/default_group_max_size
-- (V1) for GroupGenerator's group-size defaults, but no plan-level default for the LOWEST group's
-- level floor ("min-nivå"). Nullable REAL, same 0-1000 scale as participant_profile.estimated_level
-- (LevelService) - a plain ADD COLUMN is safe here (SQLite has no ALTER COLUMN, but adding a
-- nullable column needs no create-copy-rename per CLAUDE.md's SQLite rules).
--
-- GroupGenerator (solver.assemble, NOT solver.domain/constraints - the ArchUnit no-float rule does
-- not cover this package) reads this to clamp the lowest-level group's computed levelMin up to this
-- floor when set, never inverting levelMin above the group's own levelMax.

ALTER TABLE activity_plan ADD COLUMN default_level_min REAL;
