package se.klubb.groupplanner.solver.run;

import ai.timefold.solver.core.api.score.analysis.ConstraintAnalysis;
import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import se.klubb.groupplanner.domain.OptimizationRun;
import se.klubb.groupplanner.explain.ConstraintMetadata;
import se.klubb.groupplanner.repo.OptimizationRunRepository;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.util.Uuid7;

/**
 * {@code optimization_run} persistence (docs/design/02-product-data-ui.md §1, V5__solver_runs.sql,
 * extended by M7's V7__explainability.sql).
 *
 * <p><b>Privacy (CLAUDE.md, docs/plan.md):</b> {@code input_snapshot_json}/{@code
 * result_summary_json} are built ONLY from {@link GroupPlanSolution} and the {@link ScoreAnalysis}
 * Timefold computes over it — neither type can structurally carry {@code importedComment}/{@code
 * internalNote} (those two columns never reach {@code se.klubb.groupplanner.solver.domain}, and every
 * {@code ConstraintJustification} record in {@code se.klubb.groupplanner.solver.constraints
 * .Justifications} carries only ids/names/counts/levels — never free text a council member wrote).
 * Covered by {@code OptimizationRunSnapshotLeakTest} (M6a) and {@code
 * ConstraintSummaryLeakTest} (M7 — the {@code constraintSummaries} addition below is a new attack
 * surface in principle, so it gets its own explicit assertion even though it structurally cannot
 * leak).
 *
 * <p><b>M7 addition (task item 2, docs/design/04-solver.md §11.1):</b> {@link #finishRun} now takes
 * the run's {@link ScoreAnalysis} (always computed with {@code FETCH_ALL} by the caller — {@code
 * SolveCoordinator}) and folds a compact plan-level {@code constraintSummaries} array (key, label,
 * level, weightApplied, scoreTotal, matchCount) into {@code result_summary_json}, plus the {@code
 * plan_revision} the run's own writeback finished at (see {@code ActivityPlanRepository
 * #bumpRevision} — the "basedOnRevision" half of every explanation/what-if staleness envelope).
 * Match-level justification data itself is NOT persisted here (design §11.1's "indexed by entity ids
 * for per-player/per-group lookup" is realized on-demand by {@code se.klubb.groupplanner.explain
 * .ExplanationService} re-analyzing current state — see backend/docs/m7-notes.md).
 */
@Service
public class OptimizationRunService {

    /** v0.2.0 (COACH-OPTIONAL SOLVING): the UI-facing note surfaced in {@code result_summary_json}
     * ONLY when the solved plan had zero coach profiles (see {@link #finishRun}'s {@code noCoaches}
     * parameter and backend/docs/v020-notes.md) - the same wording for both a real solve and the
     * GREEDY baseline, since both funnel through this one method. */
    public static final String NOTE_NO_COACHES = "Inga tränare registrerade — grupperna optimerades utan tränartilldelning";

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
                null,
                0);
        return optimizationRunRepository.insert(run);
    }

    /**
     * Finishes a run. {@code analysis} is the {@code FETCH_ALL} {@link ScoreAnalysis} of the FINAL
     * persisted solution ({@code null} only tolerated for callers that genuinely have no solution to
     * analyze — none currently do; {@code SolveCoordinator} always supplies one). {@code
     * planRevisionAtFinish} is {@code activity_plan.plan_revision} immediately AFTER this run's own
     * writeback (+ bump) — see {@code ActivityPlanRepository#bumpRevision}. {@code noCoaches}
     * (v0.2.0) is {@code true} when the solved {@code GroupPlanSolution} had zero {@code CoachFact}s
     * (i.e. the plan has no coach_profile rows at all - see {@code SolverInputAssembler}'s CoachSlot
     * skip and backend/docs/v020-notes.md); it adds a {@code note} field to the persisted summary the
     * UI can show instead of silently looking like coaches were simply forgotten.
     *
     * <p>{@code unchangedFromPrevious} (WI-C, v0.4 user feedback #4, root cause C) is {@code true}
     * only when {@code SolveCoordinator} determined the writeback left every player/coach/block
     * assignment byte-for-byte identical to how the plan looked right before this run started, AND a
     * prior FINISHED run already existed to compare against - added to the summary only when
     * {@code true} (mirrors {@code noCoaches}/{@code note}'s conditional-key convention), so the
     * frontend can explain why re-running right after a successful solve can legitimately look like
     * "nothing happened" (deterministic warm start + {@code randomSeed 0}, docs/plan.md ADR-007).
     */
    public OptimizationRun finishRun(
            String runId,
            HardMediumSoftLongScore score,
            int unassignedCount,
            boolean cancelled,
            ScoreAnalysis<HardMediumSoftLongScore> analysis,
            int planRevisionAtFinish,
            boolean noCoaches,
            boolean unchangedFromPrevious) {
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
        summary.put("constraintSummaries", constraintSummariesOf(analysis));
        if (noCoaches) {
            summary.put("note", NOTE_NO_COACHES);
        }
        if (unchangedFromPrevious) {
            summary.put("unchangedFromPrevious", true);
        }
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
                writeJson(summary),
                planRevisionAtFinish);
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
                writeJson(Map.of("error", String.valueOf(error.getMessage()))),
                run.planRevision());
        return optimizationRunRepository.update(updated);
    }

    /** WI-C unchanged-result detection: whether {@code activityPlanId} already has a prior FINISHED
     * run - checked by {@code SolveCoordinator} BEFORE the new run's own SOLVING row is inserted, so
     * a plan's first-ever completed solve can never be reported as "unchanged from previous" (there
     * is nothing yet to compare against). */
    public boolean hasFinishedRun(String activityPlanId) {
        return optimizationRunRepository.existsFinishedByActivityPlanId(activityPlanId);
    }

    /** Current status of a run row, for the cancel path's did-the-event-fire probe. */
    public String runStatus(String runId) {
        return optimizationRunRepository.findById(runId)
                .map(OptimizationRun::status)
                .orElseThrow(() -> new IllegalStateException("optimization_run vanished mid-cancel: " + runId));
    }

    /** Finalizes a run whose solver job was terminated before it ever produced a solution — a
     * cancel while the job is still SOLVING_SCHEDULED never fires the final-best-solution event,
     * so no writeback/finishRun happens and the row would otherwise sit in SOLVING forever (bug
     * found by the M9 ActiveSolveCleanup test extension; the startup sweep only cures it after a
     * restart). No-op if the row already reached a terminal status (the event won the race). */
    public void cancelRunWithoutResult(String runId) {
        OptimizationRun run = optimizationRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("optimization_run vanished mid-cancel: " + runId));
        if (!OptimizationRun.STATUS_SOLVING.equals(run.status()) && !OptimizationRun.STATUS_QUEUED.equals(run.status())) {
            return;
        }
        Instant finishedAt = Instant.now();
        int durationMs = (int) Math.max(0, finishedAt.toEpochMilli() - Instant.parse(run.startedAt()).toEpochMilli());
        OptimizationRun updated = new OptimizationRun(
                run.id(),
                run.activityPlanId(),
                run.inputSnapshotJson(),
                run.constraintWeightsJson(),
                run.score(),
                OptimizationRun.STATUS_CANCELLED,
                run.startedAt(),
                finishedAt.toString(),
                durationMs,
                writeJson(Map.of("note", "avbruten innan lösaren hann starta")),
                run.planRevision());
        optimizationRunRepository.update(updated);
    }

    /** One compact row per constraint that fired or is configured (docs/design/04-solver.md §11.1):
     * {@code key, label, level, weightApplied, scoreTotal, matchCount} — the plan-level analysis
     * persisted eagerly after every solve/greedy run (M7 task item 2), extending what M6a stored
     * (score + unassignedCount only). {@code level}/{@code label} come from {@link ConstraintMetadata}
     * (not from the possibly-zero {@code weight()}, which alone can't distinguish "disabled" from
     * "no matches this solve") so a disabled constraint still reports its normal level, with {@code
     * weightApplied == 0} signaling "excluded from processing" per {@code ConstraintWeightOverrides}'
     * documented zero-weight semantics. */
    private List<Map<String, Object>> constraintSummariesOf(ScoreAnalysis<HardMediumSoftLongScore> analysis) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (analysis == null) {
            return rows;
        }
        for (ConstraintAnalysis<HardMediumSoftLongScore> ca : analysis.constraintAnalyses()) {
            String key = ca.constraintName();
            ConstraintMetadata.Meta meta = ConstraintMetadata.of(key);
            Map<String, Object> row = new TreeMap<>();
            row.put("key", key);
            row.put("label", meta.label());
            row.put("level", meta.level());
            row.put("weightApplied", Math.abs(primaryComponent(ca.weight())));
            row.put("scoreTotal", primaryComponent(ca.score()));
            row.put("matchCount", ca.matchCount());
            rows.add(row);
        }
        rows.sort((a, b) -> String.valueOf(a.get("key")).compareTo(String.valueOf(b.get("key"))));
        return rows;
    }

    /** The nonzero hard/medium/soft component of a {@link HardMediumSoftLongScore} — every score this
     * method sees carries weight/impact in exactly one level by construction (a constraint is HARD,
     * MEDIUM or SOFT, never a mix), so "the nonzero one" is unambiguous; an all-zero score (a
     * configured-but-never-matched constraint) returns 0. */
    private static long primaryComponent(HardMediumSoftLongScore score) {
        if (score.hardScore() != 0) {
            return score.hardScore();
        }
        if (score.mediumScore() != 0) {
            return score.mediumScore();
        }
        return score.softScore();
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
