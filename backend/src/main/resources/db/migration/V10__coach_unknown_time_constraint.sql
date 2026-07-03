-- V10__coach_unknown_time_constraint.sql
--
-- WI-B: "Okänd" (neutral/unlisted) coach availability used to be scored EXACTLY like an explicit
-- AVAILABLE row by the solver - fully neutral, so leaving the availability matrix blank was free.
-- This migration seeds the constraint_definition row for the new coachUnknownTimeSlot SOFT
-- constraint (GroupPlanConstraintProvider, backend), which gives a small "föredrar inte" penalty
-- to a coach assigned to a group on a time slot they never expressed an opinion about (no explicit
-- AVAILABLE/PREFERRED/UNAVAILABLE row) - the explicit-UNAVAILABLE case stays HARD-only via the
-- existing coachAvailabilityHard row and is not double-punished. Weight is user-editable in the
-- Fält panel exactly like every other SOFT row (see ConstraintWeightConfigRepository /
-- SolverInputAssembler.buildConstraintWeightOverrides, which iterates constraint_definition rows
-- keyed off ConstraintKeys.IMPLEMENTED - no extra plumbing needed beyond this row + the code key).

INSERT INTO constraint_definition (key, label, description, constraint_category, default_weight, hard_or_soft, enabled) VALUES
('coachUnknownTimeSlot', 'Tränare på tid utan angiven tillgänglighet',
 'Okänd (ej ifylld) tillgänglighet räknas som ''föredrar inte'' – liten minuspoäng per pass. Tränare som fyllt i sin tillgänglighet gynnas.',
 'COACH', 20, 'SOFT', 1);
