package se.klubb.groupplanner.solver.run;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import se.klubb.groupplanner.domain.OptimizationRun;
import se.klubb.groupplanner.repo.OptimizationRunRepository;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.util.Uuid7;

/**
 * {@code optimization_run} persistence (docs/design/02-product-data-ui.md §1, V5__solver_runs.sql).
 *
 * <p><b>Privacy (CLAUDE.md, docs/plan.md):</b> {@code input_snapshot_json}/{@code
 * result_summary_json} are built ONLY from {@link GroupPlanSolution} — a type that structurally
 * cannot carry {@code importedComment}/{@code internalNote} (neither field exists anywhere in {@code
 * se.klubb.groupplanner.solver.domain}; {@code SolverInputAssembler} never reads those two columns).
 * Covered by {@code OptimizationRunSnapshotLeakTest}.
 */
@Service
public class OptimizationRunService {

    private final OptimizationRunRepository optimizationRunRepository;
    private final ObjectMapper objectMapper;

    public OptimizationRunService(OptimizationRunRepository optimizationRunRepository, ObjectMapper objectMapper) {
        this.optimizationRunRepository = optimizationRunRepository;
        this.objectMapper = objectMapper;
    }

    public OptimizationRun startRun(String activityPlanId, GroupPlanSolution problem) {
        OptimizationRun run = new OptimizationRun(
                Uuid7.generate(),
                activityPlanId,
                writeJson(inputSnapshot(problem)),
                writeJson(weightsSnapshot(problem)),
                null,
                OptimizationRun.STATUS_SOLVING,
                Instant.now().toString(),
                null,
                null,
                null);
        return optimizationRunRepository.insert(run);
    }

    public OptimizationRun finishRun(String runId, HardMediumSoftLongScore score, int unassignedCount, boolean cancelled) {
        OptimizationRun run = optimizationRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("optimization_run vanished mid-solve: " + runId));
        Instant finishedAt = Instant.now();
        int durationMs = (int) Math.max(0, finishedAt.toEpochMilli() - Instant.parse(run.startedAt()).toEpochMilli());
        Map<String, Object> summary = new TreeMap<>();
        summary.put("hard", score.hardScore());
        summary.put("medium", score.mediumScore());
        summary.put("soft", score.softScore());
        summary.put("feasible", score.isFeasible());
        summary.put("unassignedCount", unassignedCount);
        OptimizationRun updated = new OptimizationRun(
                run.id(),
                run.activityPlanId(),
                run.inputSnapshotJson(),
                run.constraintWeightsJson(),
                score.toString(),
                cancelled ? OptimizationRun.STATUS_CANCELLED : OptimizationRun.STATUS_FINISHED,
                run.startedAt(),
                finishedAt.toString(),
                durationMs,
                writeJson(summary));
        return optimizationRunRepository.update(updated);
    }

    public OptimizationRun failRun(String runId, Throwable error) {
        OptimizationRun run = optimizationRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("optimization_run vanished mid-solve: " + runId));
        Instant finishedAt = Instant.now();
        int durationMs = (int) Math.max(0, finishedAt.toEpochMilli() - Instant.parse(run.startedAt()).toEpochMilli());
        OptimizationRun updated = new OptimizationRun(
                run.id(),
                run.activityPlanId(),
                run.inputSnapshotJson(),
                run.constraintWeightsJson(),
                run.score(),
                OptimizationRun.STATUS_FAILED,
                run.startedAt(),
                finishedAt.toString(),
                durationMs,
                writeJson(Map.of("error", String.valueOf(error.getMessage()))));
        return optimizationRunRepository.update(updated);
    }

    /** Counts-only snapshot (never the full entity graph - unnecessary for M6a and keeps the JSON
     * small); still built exclusively from {@link GroupPlanSolution}, so it is leak-free by
     * construction regardless of level of detail. */
    private Map<String, Object> inputSnapshot(GroupPlanSolution problem) {
        Map<String, Object> snapshot = new TreeMap<>();
        snapshot.put("activityPlanId", problem.getActivityPlanId());
        snapshot.put("participantCount", problem.getPlayerAssignments().size());
        snapshot.put("groupCount", problem.getGroups().size());
        snapshot.put("trainingBlockCount", problem.getTrainingBlocks().size());
        snapshot.put("coachCount", problem.getCoaches().size());
        snapshot.put("coachSlotCount", problem.getCoachSlots().size());
        snapshot.put("personPairWishCount", problem.getPersonPairWishes().size());
        snapshot.put("coachWishCount", problem.getCoachWishes().size());
        return snapshot;
    }

    private Map<String, String> weightsSnapshot(GroupPlanSolution problem) {
        Map<String, String> weights = new TreeMap<>();
        for (String key : problem.getConstraintWeightOverrides().getKnownConstraintNames()) {
            weights.put(key, problem.getConstraintWeightOverrides().getConstraintWeight(key).toString());
        }
        return weights;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize optimization_run JSON", e);
        }
    }
}
