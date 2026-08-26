package se.klubb.groupplanner.fields;

import java.util.List;
import java.util.Map;

/**
 * One row of {@code GET|PUT /api/plans/{planId}/priority-order}'s {@code priorities} list — one of
 * the four {@link PriorityOrder.Priority} families, at its current rank, with the effective weights
 * (from {@link ConstraintWeightService#listForPlan}) of the {@link ConstraintKeys} it expands into.
 *
 * @param key the {@link PriorityOrder.Priority} enum name (e.g. {@code "TRAIN_TOGETHER"})
 * @param rank 1-based rank in the response's {@code order} (1 = highest priority)
 * @param labelSv {@link PriorityOrder#labelSv}
 * @param summarySv one finished Swedish sentence describing this priority's current effect, phrased
 *     rank-aware (see {@code PriorityOrderService#summarySv})
 * @param constraintKeys the {@link ConstraintKeys} constants this priority expands into (fixed order,
 *     not {@link PriorityOrder#constraintKeysOf}'s unordered {@code Set})
 * @param weights effective weight per entry of {@code constraintKeys}, same order — 0 for any key
 *     that is currently disabled (see {@code PriorityOrderService#weightOf}), regardless of what
 *     weight is configured for it
 * @param enabled {@code true} only when EVERY one of {@code constraintKeys} is currently enabled
 *     (B7 review fix) — {@code false} means the solver ignores at least one of this priority's
 *     constraints entirely, so the frontend should grey this row out rather than imply it is fully
 *     live at its displayed rank/weights
 */
public record PriorityOrderRow(
        String key,
        int rank,
        String labelSv,
        String summarySv,
        List<String> constraintKeys,
        Map<String, Integer> weights,
        boolean enabled) {
}
