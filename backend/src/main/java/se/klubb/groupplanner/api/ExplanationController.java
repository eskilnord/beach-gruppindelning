package se.klubb.groupplanner.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.explain.ExplanationDtos.GroupExplanationResponse;
import se.klubb.groupplanner.explain.ExplanationDtos.ImprovementSuggestionsResponse;
import se.klubb.groupplanner.explain.ExplanationDtos.PersonExplanationResponse;
import se.klubb.groupplanner.explain.ExplanationDtos.PlanExplanationResponse;
import se.klubb.groupplanner.explain.ExplanationService;
import se.klubb.groupplanner.explain.ImprovementSuggestionService;

/**
 * Explainability endpoints (docs/design/04-solver.md §14.2, task item 4): person/group/plan levels,
 * all scoped under one run so every response carries the staleness envelope ({@code runId,
 * basedOnRevision, currentRevision, stale}). {@code .../suggestions} (WI-D) is the same family: a
 * run-scoped, staleness-enveloped read, just answering "what SMALL change would help" instead of
 * "why is it like this".
 */
@RestController
public class ExplanationController {

    private final ExplanationService explanationService;
    private final ImprovementSuggestionService improvementSuggestionService;

    public ExplanationController(ExplanationService explanationService, ImprovementSuggestionService improvementSuggestionService) {
        this.explanationService = explanationService;
        this.improvementSuggestionService = improvementSuggestionService;
    }

    @GetMapping("/api/plans/{planId}/runs/{runId}/explanations/plan")
    public PlanExplanationResponse plan(@PathVariable String planId, @PathVariable String runId) {
        return explanationService.explainPlan(planId, runId);
    }

    @GetMapping("/api/plans/{planId}/runs/{runId}/explanations/groups/{groupId}")
    public GroupExplanationResponse group(@PathVariable String planId, @PathVariable String runId, @PathVariable String groupId) {
        return explanationService.explainGroup(planId, runId, groupId);
    }

    @GetMapping("/api/plans/{planId}/runs/{runId}/explanations/players/{participantProfileId}")
    public PersonExplanationResponse player(
            @PathVariable String planId, @PathVariable String runId, @PathVariable String participantProfileId) {
        return explanationService.explainPerson(planId, runId, participantProfileId);
    }

    @GetMapping("/api/plans/{planId}/runs/{runId}/suggestions")
    public ImprovementSuggestionsResponse suggestions(@PathVariable String planId, @PathVariable String runId) {
        return improvementSuggestionService.suggestions(planId, runId);
    }
}
