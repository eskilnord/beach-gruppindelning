package se.klubb.groupplanner.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;

/**
 * Comment delete/anonymize actions (spec §21.2 "ska kunna raderas/anonymiseras"; docs/plan.md
 * "Privacy corrections": "Radera/anonymisera kommentarer" actions per participant and per plan").
 * Both null out {@code imported_comment}/{@code internal_note} on {@code participant_profile} —
 * irreversible, so the plan-wide action requires an explicit {@code {"confirm": true}} body.
 */
@RestController
public class CommentPrivacyController {

    private final ParticipantProfileRepository participantProfileRepository;
    private final ActivityPlanRepository activityPlanRepository;

    public CommentPrivacyController(
            ParticipantProfileRepository participantProfileRepository, ActivityPlanRepository activityPlanRepository) {
        this.participantProfileRepository = participantProfileRepository;
        this.activityPlanRepository = activityPlanRepository;
    }

    /** Clears one participant's comments. */
    @DeleteMapping("/api/plans/{planId}/participants/{pid}/comments")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOne(@PathVariable String planId, @PathVariable String pid) {
        requirePlanExists(planId);
        boolean belongsToPlan = participantProfileRepository.findById(pid)
                .filter(p -> p.activityPlanId().equals(planId))
                .isPresent();
        if (!belongsToPlan) {
            throw new NotFoundException("Participant not found in plan " + planId + ": " + pid);
        }
        participantProfileRepository.clearComments(pid);
    }

    /** Clears comments for every participant in the plan. Requires {@code {"confirm": true}}. */
    @PostMapping("/api/plans/{planId}/comments/anonymize")
    public AnonymizeResult anonymizeAll(@PathVariable String planId, @RequestBody(required = false) AnonymizeRequest request) {
        requirePlanExists(planId);
        if (request == null || request.confirm() == null || !request.confirm()) {
            throw new BadRequestException("Request body {\"confirm\": true} is required to anonymize all comments for this plan");
        }
        int cleared = participantProfileRepository.clearAllCommentsForPlan(planId);
        return new AnonymizeResult(cleared);
    }

    private void requirePlanExists(String planId) {
        if (activityPlanRepository.findById(planId).isEmpty()) {
            throw new NotFoundException("Activity plan not found: " + planId);
        }
    }

    public record AnonymizeRequest(Boolean confirm) {
    }

    public record AnonymizeResult(int clearedCount) {
    }
}
