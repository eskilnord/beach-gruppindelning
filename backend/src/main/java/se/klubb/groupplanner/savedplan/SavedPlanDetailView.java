package se.klubb.groupplanner.savedplan;

/**
 * {@code GET /api/plans/{planId}/saved-plans/{savedPlanId}} response (spec §14.1/§14.2): the raw
 * {@link se.klubb.groupplanner.domain.SavedPlan} fields plus {@code snapshot} — {@code
 * snapshot_json} parsed back into a plain object tree (Jackson {@code Map}/{@code List}/scalar
 * structure) so callers don't have to double-decode a JSON-string-inside-JSON. {@code snapshot} is
 * {@code null} only for a (should-be-impossible in practice) row whose snapshot was never built.
 */
public record SavedPlanDetailView(
        String id,
        String activityPlanId,
        String name,
        String status,
        String score,
        String optimizationRunId,
        String createdAt,
        String updatedAt,
        Object snapshot) {
}
