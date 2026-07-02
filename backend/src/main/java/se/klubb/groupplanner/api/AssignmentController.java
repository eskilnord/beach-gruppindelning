package se.klubb.groupplanner.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.CoachAssignment;
import se.klubb.groupplanner.domain.PlayerAssignment;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;

/**
 * {@code GET /api/plans/{planId}/assignments} — read-only listing of every {@link PlayerAssignment}/
 * {@link CoachAssignment} row for a plan (frontend M6b addition, filling a gap left by the backend
 * M6b session: {@code PlayerAssignmentRepository#findByActivityPlanId}/{@code
 * CoachAssignmentRepository#findByActivityPlanId} were already used internally by {@code
 * SolverInputAssembler}, {@code GroupGenerator}, {@code ConflictService} and {@code
 * SavedPlanService}, but never wired to REST — the Resultatvy/Planeringskarta UI (spec §19.10/§19.11)
 * needs to read solver writeback (group_id + source per participant/coach) the exact same way those
 * internal services already do; see backend/docs/m6b-notes.md for the full M6b scope and
 * frontend/docs (this milestone's notes) for the discovery trail). Pure pass-through, no new domain
 * logic — mirrors {@link GroupController#list}'s shape (return the domain record list directly).
 */
@RestController
public class AssignmentController {

    private final ActivityPlanRepository activityPlanRepository;
    private final PlayerAssignmentRepository playerAssignmentRepository;
    private final CoachAssignmentRepository coachAssignmentRepository;

    public AssignmentController(
            ActivityPlanRepository activityPlanRepository,
            PlayerAssignmentRepository playerAssignmentRepository,
            CoachAssignmentRepository coachAssignmentRepository) {
        this.activityPlanRepository = activityPlanRepository;
        this.playerAssignmentRepository = playerAssignmentRepository;
        this.coachAssignmentRepository = coachAssignmentRepository;
    }

    @GetMapping("/api/plans/{planId}/assignments")
    public AssignmentsView list(@PathVariable String planId) {
        if (activityPlanRepository.findById(planId).isEmpty()) {
            throw new NotFoundException("Activity plan not found: " + planId);
        }
        return new AssignmentsView(
                playerAssignmentRepository.findByActivityPlanId(planId),
                coachAssignmentRepository.findByActivityPlanId(planId));
    }

    public record AssignmentsView(List<PlayerAssignment> players, List<CoachAssignment> coaches) {
    }
}
