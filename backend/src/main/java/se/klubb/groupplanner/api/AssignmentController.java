package se.klubb.groupplanner.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.CoachAssignment;
import se.klubb.groupplanner.domain.PlayerAssignment;
import se.klubb.groupplanner.domain.TrainingGroup;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachAssignmentRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.PlayerAssignmentRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.solver.run.SolveCoordinator;

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
 *
 * <p><b>M7 addition (task item 5):</b> {@code POST .../assignments/{pid}/move} — the actual MUTATING
 * counterpart of {@code WhatIfController}'s pure-evaluation endpoints: a council member who has
 * inspected the what-if consequence report clicks "flytta ändå", which becomes a manual move
 * ({@code source=manual}). Bumps {@code activity_plan.plan_revision} (docs/design/04-solver.md
 * §11.6's staleness envelope) and 409s while a solve is running (design §9.4), matching every other
 * plan-mutating endpoint in this codebase.
 */
@RestController
public class AssignmentController {

    private final ActivityPlanRepository activityPlanRepository;
    private final PlayerAssignmentRepository playerAssignmentRepository;
    private final CoachAssignmentRepository coachAssignmentRepository;
    private final ParticipantProfileRepository participantProfileRepository;
    private final TrainingGroupRepository trainingGroupRepository;
    private final SolveCoordinator solveCoordinator;

    public AssignmentController(
            ActivityPlanRepository activityPlanRepository,
            PlayerAssignmentRepository playerAssignmentRepository,
            CoachAssignmentRepository coachAssignmentRepository,
            ParticipantProfileRepository participantProfileRepository,
            TrainingGroupRepository trainingGroupRepository,
            SolveCoordinator solveCoordinator) {
        this.activityPlanRepository = activityPlanRepository;
        this.playerAssignmentRepository = playerAssignmentRepository;
        this.coachAssignmentRepository = coachAssignmentRepository;
        this.participantProfileRepository = participantProfileRepository;
        this.trainingGroupRepository = trainingGroupRepository;
        this.solveCoordinator = solveCoordinator;
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

    /** {@code groupId == null}/blank moves the participant to the waitlist explicitly (a deliberate
     * "flytta till kölista" action, distinct from never having been placed). Refuses a currently
     * LOCKED assignment (400 — unlock first, matching {@code AssignmentLockController}'s own
     * ownership model: a lock is a deliberate hold a manual move must not silently override). */
    @PostMapping("/api/plans/{planId}/assignments/{participantProfileId}/move")
    @Transactional // M7 review fix m5: insert-if-absent + update + revision bump are one atomic unit.
    public PlayerAssignment move(
            @PathVariable String planId, @PathVariable String participantProfileId, @RequestBody(required = false) MoveRequest request) {
        solveCoordinator.assertNoActiveSolve(planId);
        participantProfileRepository.findById(participantProfileId)
                .filter(p -> p.activityPlanId().equals(planId))
                .orElseThrow(() -> new NotFoundException("Participant not found in plan: " + participantProfileId));

        String groupId = request == null ? null : request.groupId();
        String resolvedGroupId = null;
        if (groupId != null && !groupId.isBlank()) {
            TrainingGroup group = trainingGroupRepository.findById(groupId)
                    .filter(g -> g.activityPlanId().equals(planId))
                    .orElseThrow(() -> new BadRequestException("Group not found in plan: " + groupId));
            resolvedGroupId = group.id();
        }

        java.util.Optional<PlayerAssignment> existing = playerAssignmentRepository.findByParticipantProfileId(participantProfileId);
        if (existing.map(PlayerAssignment::locked).orElse(false)) {
            throw new BadRequestException(
                    "Participant " + participantProfileId + " is locked - unlock before moving manually");
        }
        // M7 review fix m5: moving to the group the participant is ALREADY in is a 200 no-op —
        // nothing changed, so neither the source column flips to 'manual' (provenance stays honest)
        // nor does plan_revision bump (no reason to mark every cached explanation stale).
        if (existing.isPresent() && java.util.Objects.equals(existing.get().groupId(), resolvedGroupId)) {
            return existing.get();
        }

        playerAssignmentRepository.insertImportedIfAbsent(participantProfileId);
        playerAssignmentRepository.updateGroupAndSource(participantProfileId, resolvedGroupId, PlayerAssignment.SOURCE_MANUAL);
        activityPlanRepository.bumpRevision(planId);

        return playerAssignmentRepository.findByParticipantProfileId(participantProfileId).orElseThrow();
    }

    public record MoveRequest(String groupId) {
    }

    public record AssignmentsView(List<PlayerAssignment> players, List<CoachAssignment> coaches) {
    }
}
