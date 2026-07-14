package se.klubb.groupplanner.solver.run;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.klubb.groupplanner.domain.CoachAssignment;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.solver.assemble.AssembledProblem;
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;

/**
 * Writes the solved result back to {@code player_assignment}/{@code training_group}/{@code
 * coach_assignment} (source={@code solver}); locked rows are left untouched (both repository
 * methods used here already scope their UPDATE/DELETE to unlocked rows). A dedicated {@code
 * @Service} (not a package-private method on {@code SolveCoordinator}) so {@link #persist} always
 * runs through Spring's transactional proxy — {@code SolveCoordinator#onFinalBestSolution} used to
 * self-invoke a {@code @Transactional} method on itself, which Spring's proxy-based AOP silently
 * bypasses (same precedent as {@code se.klubb.groupplanner.importer.ImportCommitService} being its
 * own top-level {@code @Transactional} service rather than a method the importer controller calls
 * into on itself).
 *
 * <p>Also the CAS (compare-and-swap) chokepoint for the plan's {@code plan_revision}: the FIRST
 * statement in {@link #persist} re-reads the current revision and compares it against the revision
 * the problem was assembled against ({@link AssembledProblem#planRevisionAtAssemble()}). A mismatch
 * means some OTHER mutation (a manual move, a lock/unlock, ...) committed while this solve was
 * running — writing the solved result back over it would silently discard that other change, so the
 * writeback is refused entirely (the whole method throws before touching any row) and the caller
 * (both {@code SolveCoordinator} call sites) routes the failure to {@code
 * OptimizationRunService#failRun}.
 */
@Service
public class SolveResultWriteback {

    private final PlayerAssignmentRepository playerAssignmentRepository;
    private final TrainingGroupRepository trainingGroupRepository;
    private final CoachAssignmentRepository coachAssignmentRepository;
    private final ActivityPlanRepository activityPlanRepository;

    public SolveResultWriteback(
            PlayerAssignmentRepository playerAssignmentRepository,
            TrainingGroupRepository trainingGroupRepository,
            CoachAssignmentRepository coachAssignmentRepository,
            ActivityPlanRepository activityPlanRepository) {
        this.playerAssignmentRepository = playerAssignmentRepository;
        this.trainingGroupRepository = trainingGroupRepository;
        this.coachAssignmentRepository = coachAssignmentRepository;
        this.activityPlanRepository = activityPlanRepository;
    }

    /** Returns the plan's {@code plan_revision} immediately AFTER this writeback (M7,
     * docs/design/04-solver.md §11.6): a fresh solve's writeback invalidates the meaning of
     * "current DB state" for any previously cached explanation of an OLDER run (see
     * backend/docs/m7-notes.md's "invalidation surface" note for the full correctness argument —
     * {@code se.klubb.groupplanner.explain.ExplanationService}/{@code WhatIfService} always compute
     * against current {@code player_assignment}/{@code training_group}/{@code coach_assignment}
     * rows, so a solve that overwrites them must bump the revision exactly like a manual move or a
     * lock change does). Throws {@link StaleSolveResultException} (no rows touched) if the plan's
     * revision moved since {@code assembled} was built. */
    @Transactional
    public int persist(AssembledProblem assembled, GroupPlanSolution solution) {
        String activityPlanId = assembled.solution().getActivityPlanId();
        int currentRevision = activityPlanRepository.getPlanRevision(activityPlanId);
        if (currentRevision != assembled.planRevisionAtAssemble()) {
            throw new StaleSolveResultException(assembled.planRevisionAtAssemble(), currentRevision);
        }

        for (se.klubb.groupplanner.solver.domain.PlayerAssignment pa : solution.getPlayerAssignments()) {
            String participantDbId = assembled.participantProfileDbIdByLongId().get(pa.getId());
            if (participantDbId == null) {
                continue;
            }
            Group group = pa.getGroup();
            String groupDbId = group == null ? null : assembled.trainingGroupDbIdByLongId().get(group.id());
            // M8 (found by the M8 jar E2E): updateGroupAndSource is an UPDATE scoped to an existing
            // row - a participant that never got its "awaiting placement" player_assignment row
            // (possible historically via POST /api/plans/{id}/participants, which didn't create one
            // until the M8 fix in ParticipantProfileController#create) would silently LOSE its
            // solver placement here. Same insert-if-absent convention AssignmentController#move has
            // used since M7; a no-op for the normal import-commit-seeded case.
            playerAssignmentRepository.insertImportedIfAbsent(participantDbId);
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

        return activityPlanRepository.bumpRevision(activityPlanId);
    }
}
