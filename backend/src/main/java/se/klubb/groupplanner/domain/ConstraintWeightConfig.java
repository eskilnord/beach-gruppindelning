package se.klubb.groupplanner.domain;

/**
 * A per-plan override of a {@link ConstraintDefinition}'s defaults (spec §7.16 / §9.4 "constraint-
 * vikttabell"). Rows only exist once a plan's weight for a given constraint has actually been
 * edited (M4); an absent row means "use {@code constraint_definition}'s defaults" — see {@code
 * se.klubb.groupplanner.fields.ConstraintWeightService} for the merge.
 *
 * <p>{@code hardOrSoft} is restricted to {@code HARD|SOFT} by the API layer (ADR-006: MEDIUM is
 * reserved for the solver's own {@code unassignedPlayer} waitlist penalty, introduced in M6 — none
 * of the 24 standard constraints are MEDIUM, and this record/table impose no DB-level restriction
 * so the guardrail can generalize once a MEDIUM-classified constraint_definition row exists).
 */
public record ConstraintWeightConfig(
        String id,
        String activityPlanId,
        String constraintKey,
        String hardOrSoft,
        int weight,
        boolean enabled) {
}
