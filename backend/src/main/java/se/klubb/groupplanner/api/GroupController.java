package se.klubb.groupplanner.api;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.CoachProfile;
import se.klubb.groupplanner.domain.TrainingBlock;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.solver.assemble.GroupGenerator;
import se.klubb.groupplanner.solver.run.SolveCoordinator;

/**
 * Group generation (docs/design/04-solver.md §7) — not explicitly listed among M6a's REST endpoints
 * in the design doc's §14.2 (which only covers solve/explain/what-if), but a necessary addition:
 * without a way to create {@code training_group} rows, {@code SolverInputAssembler} has nothing to
 * assign players into and the solve loop has no groups to test end-to-end. Mirrors the shape of the
 * existing generation endpoint, {@code PUT /api/plans/{planId}/time-slots/{slotId}/courts}.
 *
 * <p>M6b addition: §15.2/§15.3 group-level locks ({@code lock-block}/{@code lock-coach}) — see
 * {@code AssignmentLockController} for the §15.1 player lock, which is plan-scoped rather than
 * group-scoped and therefore lives on its own path prefix. All four lock/unlock endpoints 409 while
 * a solve is running for the group's plan (design §9.4 / M6b review fix F5 — see {@link
 * SolveCoordinator#assertNoActiveSolve}).
 */
@RestController
public class GroupController {

    private final GroupGenerator groupGenerator;
    private final TrainingGroupRepository trainingGroupRepository;
    private final TrainingBlockRepository trainingBlockRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final CoachAssignmentRepository coachAssignmentRepository;
    private final SolveCoordinator solveCoordinator;
    private final ActivityPlanRepository activityPlanRepository;

    public GroupController(
            GroupGenerator groupGenerator,
            TrainingGroupRepository trainingGroupRepository,
            TrainingBlockRepository trainingBlockRepository,
            CoachProfileRepository coachProfileRepository,
            CoachAssignmentRepository coachAssignmentRepository,
            SolveCoordinator solveCoordinator,
            ActivityPlanRepository activityPlanRepository) {
        this.groupGenerator = groupGenerator;
        this.trainingGroupRepository = trainingGroupRepository;
        this.trainingBlockRepository = trainingBlockRepository;
        this.coachProfileRepository = coachProfileRepository;
        this.coachAssignmentRepository = coachAssignmentRepository;
        this.solveCoordinator = solveCoordinator;
        this.activityPlanRepository = activityPlanRepository;
    }

    /** (Re)generates groups per §7's policy: count = clamp(ceil(active/target), 1, activeBlocks);
     * refuses (409) if any existing group/assignment is locked. Bumps {@code plan_revision} (M7
     * review fix M2): regeneration can reshape/clear the very groups an explanation refers to. */
    @PostMapping("/api/plans/{planId}/groups/generate")
    public List<TrainingGroup> generate(@PathVariable String planId) {
        List<TrainingGroup> groups = groupGenerator.generate(planId);
        activityPlanRepository.bumpRevision(planId);
        return groups;
    }

    @GetMapping("/api/plans/{planId}/groups")
    public List<TrainingGroup> list(@PathVariable String planId) {
        return trainingGroupRepository.findByActivityPlanId(planId);
    }

    /** WI-C ("re-run doesn't feel like it re-runs" user feedback v0.4 #4, root cause A): lets the
     * frontend warn "grupperna är inte längre i synk med planens inställningar" instead of silently
     * solving against stale group definitions - see {@link GroupGenerator#checkSyncStatus}. */
    @GetMapping("/api/plans/{planId}/groups/sync-status")
    public GroupGenerator.SyncStatus syncStatus(@PathVariable String planId) {
        return groupGenerator.checkSyncStatus(planId);
    }

    /** §15.2 "Lås gruppens tid/bana" (spec §15.2): pins the group's {@code GroupSchedule} to a
     * specific training block. */
    @PutMapping("/api/groups/{groupId}/lock-block")
    public TrainingGroup lockBlock(@PathVariable String groupId, @RequestBody LockBlockRequest request) {
        TrainingGroup group = requireGroup(groupId);
        solveCoordinator.assertNoActiveSolve(group.activityPlanId());
        if (request == null || request.trainingBlockId() == null || request.trainingBlockId().isBlank()) {
            throw new BadRequestException("trainingBlockId is required");
        }
        TrainingBlock block = trainingBlockRepository.findById(request.trainingBlockId())
                .filter(b -> b.activityPlanId().equals(group.activityPlanId()))
                .orElseThrow(() -> new BadRequestException("Training block not found in plan: " + request.trainingBlockId()));
        trainingGroupRepository.lockToBlock(groupId, block.id());
        activityPlanRepository.bumpRevision(group.activityPlanId());
        return requireGroup(groupId);
    }

    @DeleteMapping("/api/groups/{groupId}/lock-block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlockBlock(@PathVariable String groupId) {
        TrainingGroup group = requireGroup(groupId);
        solveCoordinator.assertNoActiveSolve(group.activityPlanId());
        trainingGroupRepository.unlockBlock(groupId);
        activityPlanRepository.bumpRevision(group.activityPlanId());
    }

    /** §15.3 "Lås tränare" (spec §15.3): pins a coach to the group. {@code slotIndex} (optional) is
     * validated against {@code requiredCoachCount} only — actual slot assignment stays positional
     * (Nth {@code coach_assignment} row by id order fills {@code CoachSlot} slot N, per {@code
     * SolverInputAssembler}); see {@code CoachAssignmentRepository#lockToGroup}'s javadoc. */
    @PutMapping("/api/groups/{groupId}/lock-coach")
    public TrainingGroup lockCoach(@PathVariable String groupId, @RequestBody LockCoachRequest request) {
        TrainingGroup group = requireGroup(groupId);
        solveCoordinator.assertNoActiveSolve(group.activityPlanId());
        if (request == null || request.coachProfileId() == null || request.coachProfileId().isBlank()) {
            throw new BadRequestException("coachProfileId is required");
        }
        CoachProfile coach = coachProfileRepository.findById(request.coachProfileId())
                .filter(c -> c.activityPlanId().equals(group.activityPlanId()))
                .orElseThrow(() -> new BadRequestException("Coach not found in plan: " + request.coachProfileId()));
        if (request.slotIndex() != null && (request.slotIndex() < 0 || request.slotIndex() >= group.requiredCoachCount())) {
            throw new BadRequestException(
                    "slotIndex " + request.slotIndex() + " is out of range for requiredCoachCount " + group.requiredCoachCount());
        }
        coachAssignmentRepository.lockToGroup(coach.id(), groupId);
        activityPlanRepository.bumpRevision(group.activityPlanId());
        return group;
    }

    @DeleteMapping("/api/groups/{groupId}/lock-coach")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlockCoach(@PathVariable String groupId, @RequestParam String coachProfileId) {
        TrainingGroup group = requireGroup(groupId);
        solveCoordinator.assertNoActiveSolve(group.activityPlanId());
        coachAssignmentRepository.unlockForCoachAndGroup(coachProfileId, groupId);
        activityPlanRepository.bumpRevision(group.activityPlanId());
    }

    private TrainingGroup requireGroup(String groupId) {
        return trainingGroupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group not found: " + groupId));
    }

    public record LockBlockRequest(String trainingBlockId) {
    }

    public record LockCoachRequest(String coachProfileId, Integer slotIndex) {
    }
}
