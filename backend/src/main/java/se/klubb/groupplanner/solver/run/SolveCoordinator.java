package se.klubb.groupplanner.solver.run;

import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import ai.timefold.solver.core.api.solver.ScoreAnalysisFetchPolicy;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverConfigOverride;
import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.api.solver.SolverStatus;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.api.error.ConflictException;
import se.klubb.groupplanner.domain.CoachAssignment;
import se.klubb.groupplanner.domain.OptimizationRun;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.solver.assemble.AssembledProblem;
import se.klubb.groupplanner.solver.assemble.BlockingOptions;
import se.klubb.groupplanner.solver.assemble.OptimizeSelection;
import se.klubb.groupplanner.solver.assemble.SolverInputAssembler;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;

/**
 * Solve lifecycle orchestration (docs/design/04-solver.md §9.4): start/status/cancel, backed by
 * Timefold's {@link SolverManager}. One solve per plan at a time (mutating endpoints 409 while
 * {@code SOLVING_ACTIVE}/{@code SOLVING_SCHEDULED}); cancel persists the best-so-far (the
 * final-best-solution consumer still fires after {@code terminateEarly}, per the verified API).
 *
 * <p>Uses the plan's own {@code activity_plan.id} (a TEXT UUIDv7) directly as Timefold's {@code
 * ProblemId_} — simpler than forcing it through the {@code long} id mapping every solver-domain fact
 * uses (see {@code SolverIdIndex}'s javadoc), since {@code ProblemId_} is solely a lookup key for
 * {@link SolverManager}, never a value the constraint model reasons about.
 *
 * <p><b>Finishing latch:</b> Timefold 1.33.0 flips {@code SolverStatus} to {@code NOT_SOLVING}
 * BEFORE the final-best-solution consumer runs (see {@link LiveSolutionRegistry}'s javadoc for the
 * verified {@code ConsumerSupport} detail this is built on) — so a bare {@code
 * solverManager.getSolverStatus() == NOT_SOLVING} check is not sufficient to know a plan is safe to
 * mutate or re-solve; the writeback for the PREVIOUS run may still be in flight. {@link
 * #activeByActivityPlanId}'s entry for a plan is the actual latch: inserted the moment {@link
 * #startSolve} registers the run, removed only once {@link #onFinalBestSolution}/{@link
 * #onException} have finished writing back and the run row is terminal. Every 409 gate in this
 * class (and {@code api.guard.ActiveSolveGuardInterceptor}, via {@link #assertNoActiveSolve})
 * checks this map, not just {@code SolverManager}'s transient status.
 */
@Service
public class SolveCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SolveCoordinator.class);

    private final SolverManager<GroupPlanSolution, String> solverManager;
    private final SolutionManager<GroupPlanSolution, HardMediumSoftLongScore> solutionManager;
    private final SolverInputAssembler assembler;
    private final OptimizationRunService optimizationRunService;
    private final ProgressRegistry progressRegistry;
    private final LiveSolutionRegistry liveSolutionRegistry;
    private final GreedyBaselineService greedyBaselineService;
    private final SolveResultWriteback solveResultWriteback;
    private final PlayerAssignmentRepository playerAssignmentRepository;
    private final TrainingGroupRepository trainingGroupRepository;
    private final CoachAssignmentRepository coachAssignmentRepository;

    /** Per-run state kept for the whole lifetime of a solve — from {@link #startSolve} registering
     * the run until its writeback/finishRun (or failRun) has landed. Its mere PRESENCE in {@link
     * #activeByActivityPlanId} is the finishing latch described in the class javadoc; {@code
     * runId} lets a stale event from an already-superseded/replaced entry be told apart from the
     * current one. */
    private record ActiveSolve(
            String runId, AssembledProblem assembled, AssignmentSnapshot preSnapshot, boolean hadPreviousFinishedRun,
            AtomicBoolean cancelled) {
    }

    private final Map<String, ActiveSolve> activeByActivityPlanId = new ConcurrentHashMap<>();

    /** TOCTOU guard (review fix 3): held for the duration of {@link #startSolve}'s critical section
     * so two racing POSTs cannot both pass the {@code getSolverStatus == NOT_SOLVING} check — the
     * loser would otherwise create an orphan SOLVING {@code optimization_run} row and then blow up
     * inside {@code SolverManager} (problemId already registered). {@code putIfAbsent} makes the
     * second racer fail fast with a clean 409; the guard is always released in {@code finally},
     * after which {@code getSolverStatus} (SOLVING_SCHEDULED/ACTIVE the moment {@code run()}
     * returns) takes over as the steady-state check. */
    private final Map<String, Boolean> startingByActivityPlanId = new ConcurrentHashMap<>();

    public SolveCoordinator(
            SolverManager<GroupPlanSolution, String> solverManager,
            SolutionManager<GroupPlanSolution, HardMediumSoftLongScore> solutionManager,
            SolverInputAssembler assembler,
            OptimizationRunService optimizationRunService,
            ProgressRegistry progressRegistry,
            LiveSolutionRegistry liveSolutionRegistry,
            GreedyBaselineService greedyBaselineService,
            SolveResultWriteback solveResultWriteback,
            PlayerAssignmentRepository playerAssignmentRepository,
            TrainingGroupRepository trainingGroupRepository,
            CoachAssignmentRepository coachAssignmentRepository) {
        this.solverManager = solverManager;
        this.solutionManager = solutionManager;
        this.assembler = assembler;
        this.optimizationRunService = optimizationRunService;
        this.progressRegistry = progressRegistry;
        this.liveSolutionRegistry = liveSolutionRegistry;
        this.greedyBaselineService = greedyBaselineService;
        this.solveResultWriteback = solveResultWriteback;
        this.playerAssignmentRepository = playerAssignmentRepository;
        this.trainingGroupRepository = trainingGroupRepository;
        this.coachAssignmentRepository = coachAssignmentRepository;
    }

    /** Convenience overload: full optimize, no cross-plan blocking, warm-started (M6a's exact
     * behavior). */
    public String startSolve(String activityPlanId, SolveProfile profile) {
        return startSolve(activityPlanId, profile, null, OptimizeSelection.ALL, BlockingOptions.NONE, false);
    }

    /** Convenience overload for a fixed preset profile (not {@link SolveProfile#CUSTOM}) — M6b's
     * exact signature, kept for existing non-CUSTOM callers; delegates with no custom duration,
     * warm-started. */
    public String startSolve(String activityPlanId, SolveProfile profile, OptimizeSelection optimize, BlockingOptions blocking) {
        return startSolve(activityPlanId, profile, null, optimize, blocking, false);
    }

    /**
     * Starts an async solve; returns the new {@code optimization_run} id. 409 if already solving
     * (including a concurrent racing start — see {@link #startingByActivityPlanId} — or a previous
     * run still in its finishing window — see {@link #activeByActivityPlanId}), 404/400 (via
     * {@code SolverInputAssembler}) if the plan/pins/weights are invalid.
     *
     * <p>{@code customDurationSeconds} (v0.2.0, SUGGESTED OPTIMIZATION TIME) is required exactly when
     * {@code profile == SolveProfile.CUSTOM} (re-validated here via {@link
     * SolveProfile#requireValidCustomDuration(Integer)} even though {@code SolveController} already
     * validated it — defensive re-check, same convention as {@code SolverInputAssembler}'s
     * reserved-MEDIUM guardrail) and ignored for every other profile.
     *
     * <p>{@code coldStart} (WI-C, v0.4 user feedback #4) is threaded straight through to {@link
     * SolverInputAssembler#assemble(String, OptimizeSelection, BlockingOptions, boolean)}. This
     * method also snapshots the pre-solve assignment state and whether the plan already has a prior
     * FINISHED run, both consumed by {@link #onFinalBestSolution} to fill in {@code
     * resultSummaryJson.unchangedFromPrevious} once the solve settles (same WI, root cause C: a
     * re-solve of unchanged input reproduces the identical result almost instantly with the default
     * warm start + {@code randomSeed 0}, which reads as "nothing happened" without this signal).
     */
    public String startSolve(
            String activityPlanId, SolveProfile profile, Integer customDurationSeconds, OptimizeSelection optimize,
            BlockingOptions blocking, boolean coldStart) {
        if (profile == SolveProfile.CUSTOM) {
            SolveProfile.requireValidCustomDuration(customDurationSeconds);
        }
        if (startingByActivityPlanId.putIfAbsent(activityPlanId, Boolean.TRUE) != null) {
            throw new ConflictException("A solve is already being started for plan " + activityPlanId);
        }
        try {
            if (solverManager.getSolverStatus(activityPlanId) != SolverStatus.NOT_SOLVING
                    || activeByActivityPlanId.containsKey(activityPlanId)) {
                throw new ConflictException("A solve is already running for plan " + activityPlanId);
            }

            AssembledProblem assembled = assembler.assemble(activityPlanId, optimize, blocking, coldStart);
            boolean hadPreviousFinishedRun = optimizationRunService.hasFinishedRun(activityPlanId);
            AssignmentSnapshot preSnapshot = currentAssignmentSnapshot(activityPlanId);
            OptimizationRun run = optimizationRunService.startRun(activityPlanId, assembled.solution());
            ActiveSolve active = new ActiveSolve(run.id(), assembled, preSnapshot, hadPreviousFinishedRun, new AtomicBoolean(false));
            try {
                // The entry's presence IS the finishing latch (class javadoc) - inserted right after
                // the run row exists, removed only once its writeback has fully landed.
                activeByActivityPlanId.put(activityPlanId, active);
                TerminationConfig termination = profile == SolveProfile.CUSTOM
                        ? SolveProfile.customTerminationConfig(customDurationSeconds)
                        : profile.terminationConfig();
                long limitMs = profile == SolveProfile.CUSTOM ? SolveProfile.customLimitMs(customDurationSeconds) : profile.limitMs();
                progressRegistry.start(activityPlanId, run.id(), limitMs);
                // WI-2 (v0.3.0, "se det live"): seed the live view from the pre-solve problem so it
                // isn't empty for the first few seconds before the construction heuristic's first
                // best-solution event fires - see LiveSolutionRegistry's javadoc.
                liveSolutionRegistry.start(activityPlanId, run.id(), assembled);

                solverManager.solveBuilder()
                        .withProblemId(activityPlanId)
                        .withProblem(assembled.solution())
                        .withBestSolutionEventConsumer(event -> {
                            progressRegistry.onBestSolution(activityPlanId, event.solution());
                            liveSolutionRegistry.onBestSolution(activityPlanId, run.id(), event.solution(), assembled);
                        })
                        .withFinalBestSolutionEventConsumer(event -> onFinalBestSolution(activityPlanId, run.id(), event.solution()))
                        .withExceptionHandler((id, ex) -> onException(id, run.id(), ex))
                        .withConfigOverride(new SolverConfigOverride<GroupPlanSolution>().withTerminationConfig(termination))
                        .run();
            } catch (RuntimeException e) {
                // The run row was already inserted as SOLVING - never leave it orphaned.
                activeByActivityPlanId.remove(activityPlanId, active);
                progressRegistry.clear(activityPlanId);
                optimizationRunService.failRun(run.id(), e);
                throw e;
            }

            return run.id();
        } finally {
            startingByActivityPlanId.remove(activityPlanId);
        }
    }

    /** The synchronous GREEDY run's outcome (M6b review fix F6): greedy is a naive baseline that
     * routinely produces hard violations by design (spec §16.7's "enklast möjligt"), so its HTTP
     * response must surface the score honestly rather than let a bare {@code FINISHED} read as
     * success — the UI needs {@code feasible}/{@code hardViolations} to banner a hard-violating
     * baseline correctly. {@code hardViolations} is {@code -hardScore} (exact violation count at
     * the seeded weight-1 HARD defaults; an upper-bound proxy if a HARD weight was raised). */
    public record GreedyResult(String runId, HardMediumSoftLongScore score) {

        public long hardViolations() {
            return Math.max(0, -score.hardScore());
        }

        public boolean feasible() {
            return score.isFeasible();
        }
    }

    /**
     * Guard for mutating endpoints (design §9.4: "mutating endpoints return 409 while
     * SOLVING_ACTIVE"; M6b review fix F5 applies it to the lock/unlock endpoints specifically — a
     * lock flipped mid-solve would be invisible to the running solve's already-assembled snapshot
     * AND could be overwritten by its writeback, so the DB change must wait until the solve is
     * done; also the default-deny guard {@code api.guard.ActiveSolveGuardInterceptor} calls this
     * for every other mutating endpoint). Covers SOLVING_SCHEDULED, SOLVING_ACTIVE, the brief
     * racing-start window ({@link #startingByActivityPlanId}) and the post-solve finishing window
     * ({@link #activeByActivityPlanId} — see the class javadoc) alike.
     */
    public void assertNoActiveSolve(String activityPlanId) {
        if (startingByActivityPlanId.containsKey(activityPlanId)
                || activeByActivityPlanId.containsKey(activityPlanId)
                || solverManager.getSolverStatus(activityPlanId) != SolverStatus.NOT_SOLVING) {
            throw new ConflictException(
                    "A solve is currently running for plan " + activityPlanId + " - wait for it to finish or cancel it first");
        }
    }

    /**
     * Runs the deterministic {@link GreedyBaselineService} synchronously (no Timefold, no {@code
     * SolverManager} job) and writes the result back exactly like a normal solve (spec §16.7: greedy
     * output "uses the same persistence shape"). 409 if a real solve is currently running (or still
     * finishing — see {@link #activeByActivityPlanId}) for this plan — greedy and Timefold never
     * touch the plan's rows concurrently. Does not support §15.5 class-pinning or §14.4 cross-plan
     * blocking (out of scope for a simple baseline; see {@code GreedyBaselineService}'s javadoc).
     */
    @Transactional
    public GreedyResult runGreedy(String activityPlanId) {
        if (solverManager.getSolverStatus(activityPlanId) != SolverStatus.NOT_SOLVING
                || activeByActivityPlanId.containsKey(activityPlanId)) {
            throw new ConflictException("A solve is already running for plan " + activityPlanId);
        }
        AssembledProblem assembled = assembler.assemble(activityPlanId);
        boolean hadPreviousFinishedRun = optimizationRunService.hasFinishedRun(activityPlanId);
        AssignmentSnapshot preSnapshot = currentAssignmentSnapshot(activityPlanId);
        OptimizationRun run = optimizationRunService.startRun(activityPlanId, assembled.solution());
        try {
            GroupPlanSolution result = greedyBaselineService.run(assembled.solution());
            HardMediumSoftLongScore score = solutionManager.update(result); // sets result.getScore() too
            int planRevisionAtFinish = solveResultWriteback.persist(assembled, result);
            boolean unchangedFromPrevious = hadPreviousFinishedRun && currentAssignmentSnapshot(activityPlanId).equals(preSnapshot);
            int unassignedCount = ProgressRegistry.unassignedCount(result);
            ScoreAnalysis<HardMediumSoftLongScore> analysis =
                    solutionManager.analyze(result, ScoreAnalysisFetchPolicy.FETCH_ALL);
            optimizationRunService.finishRun(run.id(), score, unassignedCount, false, analysis, planRevisionAtFinish,
                    result.getCoaches().isEmpty(), unchangedFromPrevious);
            return new GreedyResult(run.id(), score);
        } catch (RuntimeException e) {
            optimizationRunService.failRun(run.id(), e);
            throw e;
        }
    }

    public SolveStatus status(String activityPlanId) {
        SolverStatus solverStatus = solverManager.getSolverStatus(activityPlanId);
        var progress = progressRegistry.get(activityPlanId);
        if (progress.isEmpty()) {
            return new SolveStatus(solverStatus.name(), null, null, null, null, null, null, null, null, null);
        }
        ProgressRegistry.Progress p = progress.get();
        return new SolveStatus(
                solverStatus.name(), p.runId(), p.hard(), p.medium(), p.soft(), p.feasible(), p.unassignedCount(),
                p.elapsedMs(), p.limitMs(), p.improvementCount());
    }

    /** Requests early termination. For a job that already STARTED solving, the final-best-solution
     * consumer fires and persists the best-so-far (spec: användaren ska kunna avbryta). For a job
     * cancelled while still SOLVING_SCHEDULED, that event never fires — no solution ever existed —
     * so after a short grace we finalize the run row as CANCELLED ourselves and clear the per-plan
     * state that {@link #onFinalBestSolution} would otherwise have cleaned up. Without this, the
     * run sits in SOLVING until the next restart's recovery sweep. 400 if nothing is solving. */
    public String cancelSolve(String activityPlanId) {
        if (solverManager.getSolverStatus(activityPlanId) == SolverStatus.NOT_SOLVING) {
            throw new BadRequestException("No active solve to cancel for plan " + activityPlanId);
        }
        ActiveSolve active = activeByActivityPlanId.get(activityPlanId);
        if (active != null) {
            active.cancelled().set(true);
        }
        String runId = progressRegistry.get(activityPlanId).map(ProgressRegistry.Progress::runId).orElse(null);
        solverManager.terminateEarly(activityPlanId);
        if (runId != null) {
            finalizeIfEventNeverFires(activityPlanId, runId);
        }
        return runId;
    }

    private void finalizeIfEventNeverFires(String activityPlanId, String runId) {
        // Grace window: a started job's final-best event lands within this comfortably; a
        // never-started job produces nothing at all, ever. 2s keeps the cancel endpoint snappy.
        for (int i = 0; i < 20; i++) {
            String status = optimizationRunService.runStatus(runId);
            if (!OptimizationRun.STATUS_SOLVING.equals(status) && !OptimizationRun.STATUS_QUEUED.equals(status)) {
                return; // the event fired (or a writeback failure already marked it FAILED).
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Solve for plan {} was cancelled before the solver started; finalizing run {} as CANCELLED without a result",
                activityPlanId, runId);
        optimizationRunService.cancelRunWithoutResult(runId);
        ActiveSolve active = activeByActivityPlanId.get(activityPlanId);
        if (active != null && active.runId().equals(runId)) {
            activeByActivityPlanId.remove(activityPlanId, active);
        }
        progressRegistry.clear(activityPlanId);
    }

    /**
     * Fires on Timefold's own consumer thread once a run settles (finished, cancelled-but-started,
     * or — per the class javadoc's finishing-latch note — even after {@code SolverStatus} has
     * already flipped to {@code NOT_SOLVING} and a new solve may already be starting for this plan).
     * Looks up this run's {@link ActiveSolve} entry by {@code runId} rather than trusting "whatever
     * is currently in the map for this plan" — a stale/duplicate event from an OLDER run (already
     * finalized, e.g. by {@link #finalizeIfEventNeverFires}) must never touch a NEWER run's state or
     * overwrite a CANCELLED row.
     */
    private void onFinalBestSolution(String activityPlanId, String runId, GroupPlanSolution finalSolution) {
        ActiveSolve active = activeByActivityPlanId.get(activityPlanId);
        if (active == null || !active.runId().equals(runId)) {
            log.warn("Final-best-solution event for plan {} run {} arrived with no matching ActiveSolve entry "
                    + "(already finalized elsewhere, e.g. by cancel) - ignoring", activityPlanId, runId);
            return;
        }
        AssembledProblem assembled = active.assembled();
        // EVERYTHING fallible after the lookup lives inside this try: the finally's entry removal
        // is what re-opens the plan (the entry IS the finishing latch, class javadoc), so a throw
        // outside it would leave the plan permanently 409-locked (and cancelSolve unusable - the
        // solver already reports NOT_SOLVING) until a restart.
        try {
            // WI-2 final-frame guarantee: withBestSolutionEventConsumer calls can be coalesced under
            // a fast burst of improvements (Timefold's own event delivery is best-effort/async), so
            // the live view's last kept frame (never cleared - see LiveSolutionRegistry's javadoc)
            // might otherwise lag behind the actual persisted result. Re-project the true final
            // solution here so what stays on screen always matches what got written to the DB.
            liveSolutionRegistry.onBestSolution(activityPlanId, runId, finalSolution, assembled);
            boolean cancelled = active.cancelled().get();
            int planRevisionAtFinish;
            try {
                planRevisionAtFinish = solveResultWriteback.persist(assembled, finalSolution);
            } catch (RuntimeException e) {
                log.error("Failed to persist solver result for plan {}", activityPlanId, e);
                optimizationRunService.failRun(runId, e);
                return;
            }
            // WI-C unchanged-result detection (root cause C): only meaningful once a prior FINISHED
            // run exists to compare against. A cancelled run never sets the flag - its search was
            // cut short, not exhausted, so the UI note ("no better solution found") would be a lie.
            boolean unchangedFromPrevious = !cancelled && active.hadPreviousFinishedRun()
                    && currentAssignmentSnapshot(activityPlanId).equals(active.preSnapshot());
            int unassignedCount = ProgressRegistry.unassignedCount(finalSolution);
            ScoreAnalysis<HardMediumSoftLongScore> analysis =
                    solutionManager.analyze(finalSolution, ScoreAnalysisFetchPolicy.FETCH_ALL);
            optimizationRunService.finishRun(runId, finalSolution.getScore(), unassignedCount, cancelled, analysis,
                    planRevisionAtFinish, finalSolution.getCoaches().isEmpty(), unchangedFromPrevious);
        } finally {
            activeByActivityPlanId.remove(activityPlanId, active);
            progressRegistry.clear(activityPlanId);
        }
    }

    /**
     * WI-C unchanged-result detection: a plain read of the same three tables {@link
     * SolveResultWriteback#persist} writes (player_assignment.group_id,
     * training_group.assigned_training_block_id, coach_assignment), taken once BEFORE a solve starts
     * and once AFTER its writeback - equal snapshots mean the solve reproduced the exact previous
     * placement. Deliberately NOT derived from the in-memory {@link GroupPlanSolution}: Timefold
     * mutates the SAME entity instances in place while solving, so a reference captured before
     * solving would already reflect the post-solve values by the time anything compared it.
     */
    private AssignmentSnapshot currentAssignmentSnapshot(String activityPlanId) {
        Map<String, String> playerGroupByParticipantId = new HashMap<>();
        for (se.klubb.groupplanner.domain.PlayerAssignment pa : playerAssignmentRepository.findByActivityPlanId(activityPlanId)) {
            playerGroupByParticipantId.put(pa.participantProfileId(), pa.groupId());
        }
        List<String> coachGroupPairs = new ArrayList<>();
        for (CoachAssignment ca : coachAssignmentRepository.findByActivityPlanId(activityPlanId)) {
            coachGroupPairs.add(ca.coachProfileId() + "|" + ca.groupId());
        }
        Collections.sort(coachGroupPairs);
        Map<String, String> blockByGroupId = new HashMap<>();
        for (se.klubb.groupplanner.domain.TrainingGroup g : trainingGroupRepository.findByActivityPlanId(activityPlanId)) {
            blockByGroupId.put(g.id(), g.assignedTrainingBlockId());
        }
        return new AssignmentSnapshot(playerGroupByParticipantId, coachGroupPairs, blockByGroupId);
    }

    /** Structural equality is exactly "unchanged from previous" (WI-C) - a plain record so {@code
     *  Map}/{@code List} equality does the comparison for free. */
    private record AssignmentSnapshot(
            Map<String, String> playerGroupByParticipantId, List<String> coachGroupPairs, Map<String, String> blockByGroupId) {
    }

    private void onException(String activityPlanId, String runId, Throwable ex) {
        log.error("Solve failed for plan {}", activityPlanId, ex);
        ActiveSolve active = activeByActivityPlanId.get(activityPlanId);
        if (active == null || !active.runId().equals(runId)) {
            log.warn("Exception event for plan {} run {} arrived with no matching ActiveSolve entry - ignoring",
                    activityPlanId, runId);
            return;
        }
        try {
            optimizationRunService.failRun(runId, ex);
        } finally {
            activeByActivityPlanId.remove(activityPlanId, active);
            progressRegistry.clear(activityPlanId);
        }
    }
}
