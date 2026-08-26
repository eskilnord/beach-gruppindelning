package se.klubb.groupplanner.fields;

/**
 * One row of {@code GET /api/plans/{planId}/constraint-weights}: a {@code constraint_definition}
 * merged with this plan's {@code constraint_weight_config} override, if any (spec §9.4/§7.16).
 *
 * <p>{@code unit} ({@code "PER_MATCH"|"PER_POINT"}) and {@code direction}
 * ({@code "PENALIZE"|"REWARD"}) are machine-readable semantics looked up from {@link
 * se.klubb.groupplanner.explain.ConstraintMetadata} (WP4) so the UI can render a plain-language
 * "what does this weight mean" sentence per row without hardcoding per-key logic.
 *
 * <p>{@code updatedAt} (v0.6.0 milestone B7) is the override row's {@code updated_at}, or {@code
 * null} when {@code overridden} is {@code false} (pure {@code constraint_definition} default) or the
 * override row predates V14. Consumed by {@code PriorityOrderService} to compute the priority-order
 * GET response's {@code updatedAt} field.
 */
public record ConstraintWeightView(
        String key,
        String label,
        String description,
        String constraintCategory,
        String hardOrSoft,
        int weight,
        boolean enabled,
        boolean overridden,
        String unit,
        String direction,
        String updatedAt) {
}
