package se.klubb.groupplanner.solver.run;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;

/**
 * In-memory best-score/progress tracking per plan (docs/design/04-solver.md §9.4): Timefold
 * Community 1.33 has no percent-progress API, so {@code GET solve/status} reports {@code
 * {elapsedMs, limitMs, bestScore, feasible, unassignedCount, improvementCount}} from here, plus
 * {@code solverManager.getSolverStatus(problemId)} for the coarse NOT_SOLVING/SOLVING_ACTIVE state
 * (read directly by {@link SolveCoordinator}/the controller, not duplicated here).
 *
 * <p>Updated from {@code withBestSolutionEventConsumer}, which runs on the solver's own thread —
 * every mutation here goes through {@link ConcurrentHashMap#compute} for thread-safety against
 * concurrent status-poll reads from HTTP request threads.
 */
@Component
public class ProgressRegistry {

    /** One immutable snapshot per plan; replaced atomically on every update. */
    public record Progress(
            String runId,
            long hard,
            long medium,
            long soft,
            boolean feasible,
            int unassignedCount,
            long startedAtMillis,
            long limitMs,
            int improvementCount) {

        public long elapsedMs() {
            return System.currentTimeMillis() - startedAtMillis;
        }
    }

    private final Map<String, Progress> byActivityPlanId = new ConcurrentHashMap<>();

    public void start(String activityPlanId, String runId, long limitMs) {
        byActivityPlanId.put(
                activityPlanId, new Progress(runId, 0, 0, 0, false, 0, System.currentTimeMillis(), limitMs, 0));
    }

    public void onBestSolution(String activityPlanId, GroupPlanSolution solution) {
        byActivityPlanId.compute(activityPlanId, (id, previous) -> {
            HardMediumSoftLongScore score = solution.getScore();
            int unassignedCount = (int) solution.getPlayerAssignments().stream()
                    .filter(pa -> pa.getGroup() == null)
                    .count();
            long startedAt = previous != null ? previous.startedAtMillis() : System.currentTimeMillis();
            long limitMs = previous != null ? previous.limitMs() : 0L;
            String runId = previous != null ? previous.runId() : null;
            int improvementCount = (previous != null ? previous.improvementCount() : 0) + 1;
            long hard = score != null ? score.hardScore() : 0L;
            long medium = score != null ? score.mediumScore() : 0L;
            long soft = score != null ? score.softScore() : 0L;
            boolean feasible = score != null && score.isFeasible();
            return new Progress(runId, hard, medium, soft, feasible, unassignedCount, startedAt, limitMs, improvementCount);
        });
    }

    public Optional<Progress> get(String activityPlanId) {
        return Optional.ofNullable(byActivityPlanId.get(activityPlanId));
    }

    public void clear(String activityPlanId) {
        byActivityPlanId.remove(activityPlanId);
    }

    /** Test/diagnostic helper: count of unassigned players in an already-built solution, without a
     * live registry entry (used by {@code SolveCoordinator} at final-solution persistence time). */
    public static int unassignedCount(GroupPlanSolution solution) {
        return (int) solution.getPlayerAssignments().stream().filter(pa -> pa.getGroup() == null).count();
    }
}
