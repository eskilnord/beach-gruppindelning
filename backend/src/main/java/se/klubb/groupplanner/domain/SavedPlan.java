package se.klubb.groupplanner.domain;

/**
 * A snapshot of one {@link ActivityPlan} taken at "spara plan" / "lås plan" time (spec §14.1/§14.2,
 * docs/design/02-product-data-ui.md §1). Full snapshot semantics (the {@code snapshot_json} shape:
 * groups, players, coaches, blocks, weights, locks, score) are M8 scope
 * (se.klubb.groupplanner.savedplan.SavedPlanService in this milestone only materializes {@link
 * SavedPlanResourceUsage} rows, needed for cross-plan blocking §10.24). {@code status} mirrors
 * {@link ActivityPlan#status()}'s value domain but is tracked independently — see
 * V6__soft_constraints_locks_saved_plan.sql.
 */
public record SavedPlan(
        String id,
        String activityPlanId,
        String name,
        String status,
        String snapshotJson,
        String score,
        String optimizationRunId,
        String createdAt,
        String updatedAt) {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_SAVED = "saved";
    public static final String STATUS_LOCKED = "locked";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_ARCHIVED = "archived";
}
