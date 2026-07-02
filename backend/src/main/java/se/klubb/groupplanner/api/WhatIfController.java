package se.klubb.groupplanner.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.explain.ExplanationDtos.WhatIfMoveResponse;
import se.klubb.groupplanner.explain.ExplanationDtos.WhatIfWhyNotResponse;
import se.klubb.groupplanner.explain.WhatIfService;

/**
 * What-if endpoints (docs/design/04-solver.md §14.2, task item 5, kravspec §18). Neither endpoint
 * mutates anything — pure evaluation against the plan's current persisted state, per {@code
 * targetGroupId}/{@code groupId}/{@code runId} carried in the request body (design's own shape).
 * The actual mutating counterpart is {@code POST /api/plans/{planId}/assignments/{pid}/move} on
 * {@link AssignmentController}.
 */
@RestController
public class WhatIfController {

    private final WhatIfService whatIfService;

    public WhatIfController(WhatIfService whatIfService) {
        this.whatIfService = whatIfService;
    }

    @PostMapping("/api/plans/{planId}/whatif/move")
    public WhatIfMoveResponse move(@PathVariable String planId, @RequestBody(required = false) WhatIfMoveRequest request) {
        require(request != null && request.participantProfileId() != null && request.runId() != null, "participantProfileId and runId are required");
        return whatIfService.move(planId, request.runId(), request.participantProfileId(), request.targetGroupId());
    }

    @PostMapping("/api/plans/{planId}/whatif/why-not")
    public WhatIfWhyNotResponse whyNot(@PathVariable String planId, @RequestBody(required = false) WhyNotRequest request) {
        require(
                request != null && request.participantProfileId() != null && request.groupId() != null && request.runId() != null,
                "participantProfileId, groupId and runId are required");
        return whatIfService.whyNot(planId, request.runId(), request.participantProfileId(), request.groupId());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new BadRequestException(message);
        }
    }

    // M8 task item 3: renamed from MoveRequest - collided with AssignmentController.MoveRequest's
    // simple class name in the generated OpenAPI schema (see that class's ApplyMoveRequest javadoc
    // for the full explanation).
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WhatIfMoveRequest(String participantProfileId, String targetGroupId, String runId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WhyNotRequest(String participantProfileId, String groupId, String runId) {
    }
}
