package se.klubb.groupplanner.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.ParticipantProfile;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.solver.run.SolveCoordinator;

/**
 * §15.1 "Lås spelare" (spec §15.1, docs/design/04-solver.md §5): {@code PUT|DELETE
 * /api/plans/{planId}/assignments/{participantProfileId}/lock}. Plan-scoped (unlike §15.2/§15.3's
 * group-scoped locks in {@link GroupController}) because the participant/target-group relationship
 * only makes sense within one plan. 409 while a solve is running for the plan (design §9.4 / M6b
 * review fix F5 — see {@link SolveCoordinator#assertNoActiveSolve}).
 */
@RestController
public class AssignmentLockController {

    private final ParticipantProfileRepository participantProfileRepository;
    private final TrainingGroupRepository trainingGroupRepository;
    private final PlayerAssignmentRepository playerAssignmentRepository;
    private final SolveCoordinator solveCoordinator;

    public AssignmentLockController(
            ParticipantProfileRepository participantProfileRepository,
            TrainingGroupRepository trainingGroupRepository,
            PlayerAssignmentRepository playerAssignmentRepository,
            SolveCoordinator solveCoordinator) {
        this.participantProfileRepository = participantProfileRepository;
        this.trainingGroupRepository = trainingGroupRepository;
        this.playerAssignmentRepository = playerAssignmentRepository;
        this.solveCoordinator = solveCoordinator;
    }

    @PutMapping("/api/plans/{planId}/assignments/{participantProfileId}/lock")
    public void lock(
            @PathVariable String planId, @PathVariable String participantProfileId, @RequestBody LockRequest request) {
        solveCoordinator.assertNoActiveSolve(planId);
        requireParticipant(planId, participantProfileId);
        if (request == null || request.groupId() == null || request.groupId().isBlank()) {
            throw new BadRequestException("groupId is required");
        }
        TrainingGroup group = trainingGroupRepository.findById(request.groupId())
                .filter(g -> g.activityPlanId().equals(planId))
                .orElseThrow(() -> new BadRequestException("Group not found in plan: " + request.groupId()));
        playerAssignmentRepository.lockToGroup(participantProfileId, group.id());
    }

    @DeleteMapping("/api/plans/{planId}/assignments/{participantProfileId}/lock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlock(@PathVariable String planId, @PathVariable String participantProfileId) {
        solveCoordinator.assertNoActiveSolve(planId);
        requireParticipant(planId, participantProfileId);
        playerAssignmentRepository.unlock(participantProfileId);
    }

    private ParticipantProfile requireParticipant(String planId, String participantProfileId) {
        return participantProfileRepository.findById(participantProfileId)
                .filter(p -> p.activityPlanId().equals(planId))
                .orElseThrow(() -> new NotFoundException("Participant not found in plan: " + participantProfileId));
    }

    public record LockRequest(String groupId) {
    }
}
