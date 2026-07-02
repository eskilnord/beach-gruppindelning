package se.klubb.groupplanner.fields;

/**
 * One row of {@code GET /api/plans/{planId}/constraint-weights}: a {@code constraint_definition}
 * merged with this plan's {@code constraint_weight_config} override, if any (spec §9.4/§7.16).
 */
public record ConstraintWeightView(
        String key,
        String label,
        String description,
        String constraintCategory,
        String hardOrSoft,
        int weight,
        boolean enabled,
        boolean overridden) {
}
