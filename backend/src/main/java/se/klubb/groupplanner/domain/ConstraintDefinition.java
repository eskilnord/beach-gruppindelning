package se.klubb.groupplanner.domain;

/**
 * A standard constraint (spec §7.15 / §10.1-10.24), seeded read-only by {@code
 * V2__seed_constraints_and_standard_fields.sql}. {@code key} is the primary key (camelCase, e.g.
 * {@code sameGroupSoft}). {@code hardOrSoft} is one of {@code HARD|SOFT} for the 24 standard
 * constraints (the model also allows {@code MEDIUM}, reserved for the solver's own
 * {@code unassignedPlayer} waitlist penalty introduced in M6 — not one of these 24 rows).
 */
public record ConstraintDefinition(
        String key,
        String label,
        String description,
        String constraintCategory,
        int defaultWeight,
        String hardOrSoft,
        boolean enabled) {
}
