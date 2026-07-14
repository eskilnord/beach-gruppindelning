package se.klubb.groupplanner.api.guard;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.repo.TimeSlotRepository;
import se.klubb.groupplanner.repo.TrainingBlockRepository;
import se.klubb.groupplanner.repo.TrainingGroupRepository;
import se.klubb.groupplanner.solver.run.SolveCoordinator;

/**
 * Default-deny mutation guard (docs/design/04-solver.md §9.4 generalized): before M9, only {@code
 * AssignmentController#move}, {@code AssignmentLockController} and {@code GroupController}'s
 * lock/unlock endpoints called {@link SolveCoordinator#assertNoActiveSolve} — every OTHER mutating
 * endpoint (edit a participant, change constraint weights, delete a time slot, ...) could silently
 * race a running solve: the change would be invisible to the already-assembled problem AND could be
 * clobbered by the solve's own writeback (see {@code SolveResultWriteback}'s revision CAS, which
 * only catches the DB-write side of that race — this interceptor stops the mutation from ever
 * reaching the DB in the first place, the cheaper and clearer fix).
 *
 * <p>Applies to every {@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE} under {@code /api/**},
 * resolves the request to a plan id (directly from a {@code /api/plans/{planId}/...} path, or via a
 * repository lookup for the "by resource id" routes below), and calls {@link
 * SolveCoordinator#assertNoActiveSolve} for it — a 409 ({@code ConflictException}, mapped by {@code
 * se.klubb.groupplanner.api.error.ApiExceptionHandler}) if a solve is active or still finishing.
 *
 * <ul>
 *   <li>{@code /api/plans/{planId}/**} — {@code planId} straight from the path, EXCEPT the solve
 *       start/cancel endpoints (own gate, must surface their own distinct 409) and the read-only
 *       what-if endpoints (no writes at all — {@code WhatIfService} only evaluates).</li>
 *   <li>{@code /api/groups/{id}/**}, {@code /api/participants/{id}**}, {@code /api/coaches/{id}**},
 *       {@code /api/time-slots/{id}}, {@code /api/training-blocks/{id}} — the entity's own {@code
 *       activityPlanId} via the matching repository. A 404-bound id (entity not found) is simply
 *       not guarded — the controller's own lookup returns its normal 404.</li>
 *   <li>{@code /api/field-definitions/{id}} — same, but a standard/global field ({@code
 *       activityPlanId == null}) is not plan-scoped at all and is skipped.</li>
 *   <li>{@code /api/seasons/{id}} — every plan in the season is guarded (a season-level PATCH/DELETE
 *       can affect all of them).</li>
 * </ul>
 *
 * <p>Everything else under {@code /api/**} (courts, persons, venues, constraint-definitions, demo
 * data, system) is not plan-scoped and is left unguarded.
 */
@Component
public class ActiveSolveGuardInterceptor implements HandlerInterceptor {

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final SolveCoordinator solveCoordinator;
    private final TrainingGroupRepository trainingGroupRepository;
    private final ParticipantProfileRepository participantProfileRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final TrainingBlockRepository trainingBlockRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final ActivityPlanRepository activityPlanRepository;

    public ActiveSolveGuardInterceptor(
            SolveCoordinator solveCoordinator,
            TrainingGroupRepository trainingGroupRepository,
            ParticipantProfileRepository participantProfileRepository,
            CoachProfileRepository coachProfileRepository,
            TimeSlotRepository timeSlotRepository,
            TrainingBlockRepository trainingBlockRepository,
            FieldDefinitionRepository fieldDefinitionRepository,
            ActivityPlanRepository activityPlanRepository) {
        this.solveCoordinator = solveCoordinator;
        this.trainingGroupRepository = trainingGroupRepository;
        this.participantProfileRepository = participantProfileRepository;
        this.coachProfileRepository = coachProfileRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.trainingBlockRepository = trainingBlockRepository;
        this.fieldDefinitionRepository = fieldDefinitionRepository;
        this.activityPlanRepository = activityPlanRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!MUTATING_METHODS.contains(request.getMethod())) {
            return true;
        }
        String[] segments = request.getRequestURI().split("/");
        // segments[0] is always "" (leading slash); segments[1] == "api" for every route this
        // interceptor is registered against (see ActiveSolveGuardConfig).
        if (segments.length < 4 || !"api".equals(segments[1])) {
            return true;
        }

        String resource = segments[2];
        if ("plans".equals(resource)) {
            String planId = segments[3];
            String rest = String.join("/", List.of(segments).subList(4, segments.length));
            if ("solve".equals(rest) || "solve/cancel".equals(rest) || "whatif/move".equals(rest) || "whatif/why-not".equals(rest)) {
                return true; // own gate / read-only - see class javadoc.
            }
            solveCoordinator.assertNoActiveSolve(planId);
            return true;
        }
        if (segments.length < 4) {
            return true;
        }
        String id = segments[3];
        switch (resource) {
            case "groups" -> trainingGroupRepository.findById(id)
                    .ifPresent(g -> solveCoordinator.assertNoActiveSolve(g.activityPlanId()));
            case "participants" -> participantProfileRepository.findById(id)
                    .ifPresent(p -> solveCoordinator.assertNoActiveSolve(p.activityPlanId()));
            case "coaches" -> coachProfileRepository.findById(id)
                    .ifPresent(c -> solveCoordinator.assertNoActiveSolve(c.activityPlanId()));
            case "time-slots" -> timeSlotRepository.findById(id)
                    .ifPresent(t -> solveCoordinator.assertNoActiveSolve(t.activityPlanId()));
            case "training-blocks" -> trainingBlockRepository.findById(id)
                    .ifPresent(b -> solveCoordinator.assertNoActiveSolve(b.activityPlanId()));
            case "field-definitions" -> fieldDefinitionRepository.findById(id)
                    .filter(f -> f.activityPlanId() != null)
                    .ifPresent(f -> solveCoordinator.assertNoActiveSolve(f.activityPlanId()));
            // Exact /api/seasons/{id} only (PATCH/DELETE, SeasonPlanController) - NOT the nested
            // POST .../seasons/{id}/plans (creating a new, unrelated ActivityPlan under the season
            // is not itself a mutation of any EXISTING plan's rows, so it has nothing to guard).
            case "seasons" -> {
                if (segments.length == 4) {
                    activityPlanRepository.findBySeasonPlanId(id)
                            .forEach(plan -> solveCoordinator.assertNoActiveSolve(plan.id()));
                }
            }
            default -> {
                // Not a plan-scoped resource (courts, persons, venues, ...) - nothing to guard.
            }
        }
        return true;
    }
}
