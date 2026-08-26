-- V13__priority_order_default_weights.sql
--
-- v0.6.0 milestone B6: retempers 13 `constraint_definition.default_weight` rows to the new
-- "priority order" default weight ladder (backend/docs/priority-order-notes.md,
-- se.klubb.groupplanner.fields.PriorityOrder). Six of the thirteen (sameGroupSoft,
-- differentGroupSoft, previousGroupContinuity, timePreferenceSoft, levelBalance,
-- groupOrderByLevel) are the constraint-key buckets PriorityOrder.weightsFor(PriorityOrder
-- .defaultOrder()) computes; the remaining seven (groupSizeTarget, groupMinSizeSoft, coachLevelFit,
-- coachPreferenceSoft, coachPreferredTimeSlot, coachUnknownTimeSlot, lateTimeForLowerGroups) are
-- NOT part of the four-priority ladder but are retempered alongside it so every SOFT constraint's
-- relative scale stays coherent (see priority-order-notes.md for the full table and rationale).
--
-- groupSizeTargetEmpty/groupMinSizeEmpty have NO constraint_definition row of their own (V2's
-- comment + ConstraintKeys.COMPLEMENTS_OF: they fan out from groupSizeTarget's/groupMinSizeSoft's
-- ONE row) - nothing to update for them here, their applied weight moves automatically with their
-- parent row.
--
-- Deliberately NOT touched: constraint_weight_config (per-plan overrides are untouched - an
-- existing plan that already overrode a weight keeps its override exactly as before), the
-- unassignedPlayer MEDIUM row (reserved, ADR-006), and every HARD weight (all still 1, unaffected
-- by this milestone).
--
-- IMPORTANT: a plan that has NEVER overridden any of these 13 constraints will see its solver
-- behavior CHANGE the next time it is solved, on this migration alone - this is deliberate (the new
-- v0.6.0 default priority order), not a bug. Real production use of these new defaults starts with
-- season VT27; existing VT26-era plans that want the OLD behavior back can still get it via
-- per-plan constraint-weight overrides (spec §9.4) - nothing here forces a re-tuning of an existing
-- plan's overrides.

UPDATE constraint_definition SET default_weight = 2400 WHERE key = 'sameGroupSoft';
UPDATE constraint_definition SET default_weight = 1800 WHERE key = 'differentGroupSoft';
UPDATE constraint_definition SET default_weight = 1500 WHERE key = 'previousGroupContinuity';
UPDATE constraint_definition SET default_weight = 950  WHERE key = 'timePreferenceSoft';
UPDATE constraint_definition SET default_weight = 85   WHERE key = 'levelBalance';
UPDATE constraint_definition SET default_weight = 42   WHERE key = 'groupOrderByLevel';
UPDATE constraint_definition SET default_weight = 800  WHERE key = 'groupSizeTarget';
UPDATE constraint_definition SET default_weight = 2000 WHERE key = 'groupMinSizeSoft';
UPDATE constraint_definition SET default_weight = 42   WHERE key = 'coachLevelFit';
UPDATE constraint_definition SET default_weight = 600  WHERE key = 'coachPreferenceSoft';
UPDATE constraint_definition SET default_weight = 250  WHERE key = 'coachPreferredTimeSlot';
UPDATE constraint_definition SET default_weight = 250  WHERE key = 'coachUnknownTimeSlot';
UPDATE constraint_definition SET default_weight = 300  WHERE key = 'lateTimeForLowerGroups';

-- levelBalance's seeded description (V2) never mentioned the B2 (2026-08-26) matchWeight-unit
-- temper from whole level points to spread units (LevelMath.spreadUnits, SPREAD_UNIT_SCALED = 1000
-- scaled = 10 level points/unit) - ConstraintMetadata (explain package) already carries the updated
-- Swedish text; this brings the DB seed text (what the Fält/weights UI actually reads via
-- constraint_definition.description) into line with it.
UPDATE constraint_definition
   SET description = 'Minimera nivåspridning inom grupp (viktas per 10 nivåpoängs spridning).'
 WHERE key = 'levelBalance';

-- v0.6.0 milestone B6 (LEVEL-family unit coherence review fix): groupOrderByLevel's and
-- coachLevelFit's matchWeights moved from whole level points to the SAME spread-unit basis
-- levelBalance already uses (GroupPlanConstraintProvider.meanDiffPoints/coachDistancePoints) - their
-- seeded descriptions never mentioned this either. Brings both into line with levelBalance's own
-- description update above.
UPDATE constraint_definition
   SET description = 'Högre grupper ska generellt ha högre nivå än lägre grupper (viktas per 10 nivåpoängs skillnad).'
 WHERE key = 'groupOrderByLevel';

UPDATE constraint_definition
   SET description = 'Belöna tränare som matchar gruppens nivå. Straffa tränare som är för låg eller för hög nivå för gruppen (viktas per 10 nivåpoängs avstånd).'
 WHERE key = 'coachLevelFit';
