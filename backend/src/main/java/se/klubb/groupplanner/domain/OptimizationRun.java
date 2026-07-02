package se.klubb.groupplanner.domain;

/**
 * One solve attempt for an {@link ActivityPlan} (docs/design/02-product-data-ui.md §1, M6a
 * V5__solver_runs.sql). {@code status} is one of {@code QUEUED|SOLVING|FINISHED|CANCELLED|FAILED}.
 *
 * <p><b>Privacy (CLAUDE.md, docs/plan.md):</b> {@code inputSnapshotJson}/{@code
 * resultSummaryJson} must NEVER contain {@code importedComment}/{@code internalNote} — those two
 * columns never reach the solver input in the first place ({@code SolverInputAssembler} never reads
 * them), so there is nothing sensitive to leak into these snapshots by construction. Covered by
 * {@code OptimizationRunSnapshotLeakTest}.
 */
public record OptimizationRun(
        String id,
        String activityPlanId,
        String inputSnapshotJson,
        String constraintWeightsJson,
        String score,
        String status,
        String startedAt,
        String finishedAt,
        Integer durationMs,
        String resultSummaryJson) {

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_SOLVING = "SOLVING";
    public static final String STATUS_FINISHED = "FINISHED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_FAILED = "FAILED";
}
