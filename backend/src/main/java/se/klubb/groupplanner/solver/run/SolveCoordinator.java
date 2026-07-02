package se.klubb.groupplanner.solver.run;

import ai.timefold.solver.core.api.solver.SolverConfigOverride;
import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.api.solver.SolverStatus;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
import se.klubb.groupplanner.solver.assemble.SolverInputAssembler;
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;

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
 */
@Service
public class SolveCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SolveCoordinator.class);

    private final SolverManager<GroupPlanSolution, String> solverManager;
    private final SolverInputAssembler assembler;
    private final OptimizationRunService optimizationRunService;
    private final ProgressRegistry progressRegistry;
    private final PlayerAssignmentRepository playerAssignmentRepository;
    private final TrainingGroupRepository trainingGroupRepository;
    private final CoachAssignmentRepository coachAssignmentRepository;

    private final Map<String, AssembledProblem> assembledByActivityPlanId = new ConcurrentHashMap<>();
    private final Map<String, Boolean> cancelledByActivityPlanId = new ConcurrentHashMap<>();

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
            SolverInputAssembler assembler,
            OptimizationRunService optimizationRunService,
            ProgressRegistry progressRegistry,
            PlayerAssignmentRepository playerAssignmentRepository,
            TrainingGroupRepository trainingGroupRepository,
            CoachAssignmentRepository coachAssignmentRepository) {
        this.solverManager = solverManager;
        this.assembler = assembler;
        this.optimizationRunService = optimizationRunService;
        this.progressRegistry = progressRegistry;
        this.playerAssignmentRepository = playerAssignmentRepository;
        this.trainingGroupRepository = trainingGroupRepository;
        this.coachAssignmentRepository = coachAssignmentRepository;
    }

    /** Starts an async solve; returns the new {@code optimization_run} id. 409 if already solving
     * (including a concurrent racing start — see {@link #startingByActivityPlanId}), 404/400 (via
     * {@code SolverInputAssembler}) if the plan/pins/weights are invalid. */
    public String startSolve(String activityPlanId, SolveProfile profile) {
        if (startingByActivityPlanId.putIfAbsent(activityPlanId, Boolean.TRUE) != null) {
            throw new ConflictException("A solve is already being started for plan " + activityPlanId);
        }
        try {
            if (solverManager.getSolverStatus(activityPlanId) != SolverStatus.NOT_SOLVING) {
                throw new ConflictException("A solve is already running for plan " + activityPlanId);
            }

            AssembledProblem assembled = assembler.assemble(activityPlanId);
            OptimizationRun run = optimizationRunService.startRun(activityPlanId, assembled.solution());
            try {
                assembledByActivityPlanId.put(activityPlanId, assembled);
                cancelledByActivityPlanId.remove(activityPlanId);
                progressRegistry.start(activityPlanId, run.id(), profile.limitMs());

                solverManager.solveBuilder()
                        .withProblemId(activityPlanId)
                        .withProblem(assembled.solution())
                        .withBestSolutionEventConsumer(event -> progressRegistry.onBestSolution(activityPlanId, event.solution()))
                        .withFinalBestSolutionEventConsumer(event -> onFinalBestSolution(activityPlanId, run.id(), event.solution()))
                        .withExceptionHandler((id, ex) -> onException(id, run.id(), ex))
                        .withConfigOverride(new SolverConfigOverride<GroupPlanSolution>().withTerminationConfig(profile.terminationConfig()))
                        .run();
            } catch (RuntimeException e) {
                // The run row was already inserted as SOLVING - never leave it orphaned.
                assembledByActivityPlanId.remove(activityPlanId);
                progressRegistry.clear(activityPlanId);
                optimizationRunService.failRun(run.id(), e);
                throw e;
            }

            return run.id();
        } finally {
            startingByActivityPlanId.remove(activityPlanId);
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

    /** Requests early termination; the final-best-solution consumer still fires and persists the
     * best-so-far (spec: användaren ska kunna avbryta). 400 if nothing is solving for this plan. */
    public String cancelSolve(String activityPlanId) {
        if (solverManager.getSolverStatus(activityPlanId) == SolverStatus.NOT_SOLVING) {
            throw new BadRequestException("No active solve to cancel for plan " + activityPlanId);
        }
        cancelledByActivityPlanId.put(activityPlanId, Boolean.TRUE);
        String runId = progressRegistry.get(activityPlanId).map(ProgressRegistry.Progress::runId).orElse(null);
        solverManager.terminateEarly(activityPlanId);
        return runId;
    }

    private void onFinalBestSolution(String activityPlanId, String runId, GroupPlanSolution finalSolution) {
        boolean cancelled = Boolean.TRUE.equals(cancelledByActivityPlanId.remove(activityPlanId));
        AssembledProblem assembled = assembledByActivityPlanId.remove(activityPlanId);
        try {
            if (assembled != null) {
                persistResult(assembled, finalSolution);
            }
        } catch (RuntimeException e) {
            log.error("Failed to persist solver result for plan {}", activityPlanId, e);
            optimizationRunService.failRun(runId, e);
            progressRegistry.clear(activityPlanId);
            return;
        }
        int unassignedCount = ProgressRegistry.unassignedCount(finalSolution);
        optimizationRunService.finishRun(runId, finalSolution.getScore(), unassignedCount, cancelled);
        progressRegistry.clear(activityPlanId);
    }

    private void onException(String activityPlanId, String runId, Throwable ex) {
        log.error("Solve failed for plan {}", activityPlanId, ex);
        assembledByActivityPlanId.remove(activityPlanId);
        cancelledByActivityPlanId.remove(activityPlanId);
        optimizationRunService.failRun(runId, ex);
        progressRegistry.clear(activityPlanId);
    }

    /** Writes the solved result back to {@code player_assignment}/{@code training_group}/{@code
     * coach_assignment} (source={@code solver}); locked rows are left untouched (both repository
     * methods used here already scope their UPDATE/DELETE to unlocked rows). */
    @Transactional
    void persistResult(AssembledProblem assembled, GroupPlanSolution solution) {
        for (se.klubb.groupplanner.solver.domain.PlayerAssignment pa : solution.getPlayerAssignments()) {
            String participantDbId = assembled.participantProfileDbIdByLongId().get(pa.getId());
            if (participantDbId == null) {
                continue;
            }
            Group group = pa.getGroup();
            String groupDbId = group == null ? null : assembled.trainingGroupDbIdByLongId().get(group.id());
            playerAssignmentRepository.updateGroupAndSource(
                    participantDbId, groupDbId, se.klubb.groupplanner.domain.PlayerAssignment.SOURCE_SOLVER);
        }

        for (GroupSchedule gs : solution.getGroupSchedules()) {
            String groupDbId = assembled.trainingGroupDbIdByLongId().get(gs.getGroup().id());
            if (groupDbId == null) {
                continue;
            }
            String blockDbId = gs.getTrainingBlock() == null
                    ? null
                    : assembled.trainingBlockDbIdByLongId().get(gs.getTrainingBlock().id());
            trainingGroupRepository.updateAssignedTrainingBlock(groupDbId, blockDbId);
        }

        for (Group group : solution.getGroups()) {
            String groupDbId = assembled.trainingGroupDbIdByLongId().get(group.id());
            if (groupDbId != null) {
                coachAssignmentRepository.deleteUnlockedByGroupId(groupDbId);
            }
        }
        for (CoachSlot cs : solution.getCoachSlots()) {
            if (cs.getCoach() == null || cs.isPinned()) {
                continue; // null: no assignment to write; pinned: pre-existing locked row survives untouched.
            }
            String groupDbId = assembled.trainingGroupDbIdByLongId().get(cs.getGroup().id());
            String coachDbId = assembled.coachProfileDbIdByLongId().get(cs.getCoach().coachProfileId());
            if (groupDbId != null && coachDbId != null) {
                coachAssignmentRepository.insert(coachDbId, groupDbId, false, CoachAssignment.SOURCE_SOLVER);
            }
        }
    }
}
