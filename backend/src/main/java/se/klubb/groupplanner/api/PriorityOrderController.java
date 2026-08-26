package se.klubb.groupplanner.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.fields.PriorityOrderService;
import se.klubb.groupplanner.fields.PriorityOrderView;
import se.klubb.groupplanner.repo.ActivityPlanRepository;

/**
 * v0.6.0 milestone B7: {@code GET|PUT /api/plans/{planId}/priority-order} — the 4-priority ranking
 * (TRAIN_TOGETHER/PREVIOUS_GROUP/PREFERRED_TIME/LEVEL) UI over {@link
 * se.klubb.groupplanner.fields.ConstraintWeightService}'s 6 bucket-key weights. See {@link
 * PriorityOrderService} for the merge/inference/staleness logic.
 *
 * <p>Plan-scoped mutation: {@code PUT} is covered by {@code ActiveSolveGuardInterceptor}'s generic
 * {@code /api/plans/{planId}/**} rule (409 during an active solve) - no interceptor changes needed,
 * since {@code "priority-order"} is not in that interceptor's small exempt list (solve start/cancel,
 * what-if). {@code GET} is naturally exempt (the interceptor only guards mutating HTTP methods).
 */
@RestController
public class PriorityOrderController {

    private final PriorityOrderService priorityOrderService;
    private final ActivityPlanRepository activityPlanRepository;

    public PriorityOrderController(PriorityOrderService priorityOrderService, ActivityPlanRepository activityPlanRepository) {
        this.priorityOrderService = priorityOrderService;
        this.activityPlanRepository = activityPlanRepository;
    }

    @GetMapping("/api/plans/{planId}/priority-order")
    public PriorityOrderView get(@PathVariable String planId) {
        requirePlanExists(planId);
        return priorityOrderService.getForPlan(planId);
    }

    @PutMapping("/api/plans/{planId}/priority-order")
    public PriorityOrderView update(@PathVariable String planId, @RequestBody PriorityOrderUpdateRequest request) {
        requirePlanExists(planId);
        return priorityOrderService.updateForPlan(planId, request == null ? null : request.order());
    }

    private void requirePlanExists(String planId) {
        if (activityPlanRepository.findById(planId).isEmpty()) {
            throw new NotFoundException("Activity plan not found: " + planId);
        }
    }

    /** {@code PUT} body: the 4 {@link se.klubb.groupplanner.fields.PriorityOrder.Priority} enum
     * names, highest priority first. Named distinctly from every other {@code *Request} record in
     * this package (springdoc keys {@code components.schemas} by simple class name — see {@code
     * OpenApiSchemaTest}'s history of collisions). */
    public record PriorityOrderUpdateRequest(List<String> order) {
    }
}
