package se.klubb.groupplanner.fields;

import java.util.List;

/**
 * {@code GET|PUT /api/plans/{planId}/priority-order} response (v0.6.0 milestone B7). See {@link
 * PriorityOrderService} for how every field is computed.
 *
 * @param order the 4 {@link PriorityOrder.Priority} enum names, highest priority first — either the
 *     permutation whose weights EXACTLY match {@code weightsFor} (when {@code matchesOrder}), or a
 *     best-effort inference from the current effective weights (when not)
 * @param defaultOrder {@link PriorityOrder#defaultOrder()}'s enum names, for the frontend's "reset to
 *     default" affordance
 * @param matchesOrder whether some permutation of the 4 priorities exactly reproduces every one of
 *     the 6 bucket keys' effective weight (and none is disabled/reclassified)
 * @param customWeightsActive {@code !matchesOrder} — the 6 bucket keys' weights were hand-edited (via
 *     {@code PUT /constraint-weights}) rather than set through this endpoint
 * @param otherOverridesActive whether any constraint OUTSIDE the 6 bucket keys has a per-plan
 *     override row
 * @param staleSinceLastRun {@code true} when the plan's {@code plan_revision} has moved since its
 *     most recent FINISHED solver run — see {@code PriorityOrderService#isStaleSinceLastRun} javadoc
 *     for exactly what this does and does not detect (it is an "anything changed" signal, not a "the
 *     weights changed" signal — deliberately over-triggering rather than under-triggering, though it
 *     can still under-trigger through a defaults-retempering migration; see that javadoc)
 * @param updatedAt latest {@code updated_at} among the 6 bucket keys' override rows, or {@code null}
 *     when none exist (pure {@code constraint_definition} defaults)
 * @param priorities the 4 priorities, in {@code order}, rank 1 first
 */
public record PriorityOrderView(
        List<String> order,
        List<String> defaultOrder,
        boolean matchesOrder,
        boolean customWeightsActive,
        boolean otherOverridesActive,
        boolean staleSinceLastRun,
        String updatedAt,
        List<PriorityOrderRow> priorities) {
}
