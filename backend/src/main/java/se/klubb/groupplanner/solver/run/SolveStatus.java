package se.klubb.groupplanner.solver.run;

/**
 * {@code GET /api/plans/{planId}/solve/status} response shape (docs/design/04-solver.md §14.2).
 * {@code status} is one of {@code NOT_SOLVING|SOLVING_SCHEDULED|SOLVING_ACTIVE} (verified {@code
 * SolverStatus} enum names). Score/progress fields are {@code null} when there is no run history
 * for this plan yet.
 */
public record SolveStatus(
        String status,
        String runId,
        Long hard,
        Long medium,
        Long soft,
        Boolean feasible,
        Integer unassignedCount,
        Long elapsedMs,
        Long limitMs,
        Integer improvementCount) {
}
