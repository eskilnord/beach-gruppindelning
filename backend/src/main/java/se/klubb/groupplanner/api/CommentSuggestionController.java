package se.klubb.groupplanner.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;
import se.klubb.groupplanner.suggest.CommentSuggestion.ParticipantSuggestions;
import se.klubb.groupplanner.suggest.CommentSuggestionService;
import se.klubb.groupplanner.suggest.ParticipantSuggestionCount;

/**
 * "Tolkningsförslag" (WP2) read endpoints — {@code GET
 * /api/plans/{planId}/participants/{pid}/comment-suggestions} (one participant, backs the drawer,
 * full detail incl. {@code matchedText}) and {@code GET /api/plans/{planId}/comment-suggestions}
 * (the whole plan, backs the Deltagarvy grid's per-row suggestion COUNT badge only — review fix
 * MAJOR 6 "comment minimization": the plan-wide response never ships verbatim comment spans or
 * candidate names for the entire roster, only {@link ParticipantSuggestionCount}). Same
 * plan/participant existence guards as {@link ParticipantFieldValueController} — deliberately
 * duplicated rather than shared, matching that controller's own style.
 *
 * <p>Both endpoints only ever READ {@code imported_comment} and structured field values already
 * exposed elsewhere (participant GET, field-values GET) — see {@link CommentSuggestionService}'s
 * class javadoc for the full privacy contract.
 */
@RestController
public class CommentSuggestionController {

    private final CommentSuggestionService commentSuggestionService;
    private final ActivityPlanRepository activityPlanRepository;
    private final ParticipantProfileRepository participantProfileRepository;

    public CommentSuggestionController(
            CommentSuggestionService commentSuggestionService,
            ActivityPlanRepository activityPlanRepository,
            ParticipantProfileRepository participantProfileRepository) {
        this.commentSuggestionService = commentSuggestionService;
        this.activityPlanRepository = activityPlanRepository;
        this.participantProfileRepository = participantProfileRepository;
    }

    @GetMapping("/api/plans/{planId}/participants/{pid}/comment-suggestions")
    public ParticipantSuggestions forParticipant(@PathVariable String planId, @PathVariable String pid) {
        requireParticipantInPlan(planId, pid);
        return commentSuggestionService.suggestionsForParticipant(planId, pid);
    }

    @GetMapping("/api/plans/{planId}/comment-suggestions")
    public List<ParticipantSuggestionCount> forPlan(@PathVariable String planId) {
        requirePlan(planId);
        return commentSuggestionService.suggestionCountsForPlan(planId);
    }

    private void requirePlan(String planId) {
        if (activityPlanRepository.findById(planId).isEmpty()) {
            throw new NotFoundException("Activity plan not found: " + planId);
        }
    }

    private void requireParticipantInPlan(String planId, String pid) {
        requirePlan(planId);
        boolean belongsToPlan = participantProfileRepository.findById(pid)
                .filter(p -> p.activityPlanId().equals(planId))
                .isPresent();
        if (!belongsToPlan) {
            throw new NotFoundException("Participant not found in plan " + planId + ": " + pid);
        }
    }
}
