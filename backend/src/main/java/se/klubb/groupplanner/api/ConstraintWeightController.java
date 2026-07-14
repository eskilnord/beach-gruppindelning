package se.klubb.groupplanner.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.fields.ConstraintWeightOverrideRequest;
import se.klubb.groupplanner.fields.ConstraintWeightService;
import se.klubb.groupplanner.fields.ConstraintWeightView;
import se.klubb.groupplanner.repo.ActivityPlanRepository;

/**
 * Per-plan constraint weight overrides (spec §9.4/§7.16, docs/design/02-product-data-ui.md §8):
 * {@code GET|PUT /api/plans/{planId}/constraint-weights}. See {@link ConstraintWeightService} for
 * the default-merge and guardrail logic.
 */
@RestController
public class ConstraintWeightController {

    private final ConstraintWeightService constraintWeightService;
    private final ActivityPlanRepository activityPlanRepository;

    public ConstraintWeightController(ConstraintWeightService constraintWeightService, ActivityPlanRepository activityPlanRepository) {
        this.constraintWeightService = constraintWeightService;
        this.activityPlanRepository = activityPlanRepository;
    }

    @GetMapping("/api/plans/{planId}/constraint-weights")
    public List<ConstraintWeightView> list(@PathVariable String planId) {
        requirePlanExists(planId);
        return constraintWeightService.listForPlan(planId);
    }

    @PutMapping("/api/plans/{planId}/constraint-weights")
    public List<ConstraintWeightView> update(
            @PathVariable String planId, @RequestBody List<ConstraintWeightOverrideRequest> requests) {
        requirePlanExists(planId);
        // M7 (design §11.6 lists "weight change" explicitly): explanations recompute against the
        // plan's CURRENT weight config (SolverInputAssembler.buildConstraintWeightOverrides), so a
        // weight edit changes appliedWeights and every probe's score delta for any cached/older run.
        // The revision bump now happens inside ConstraintWeightService#applyOverrides, in the same
        // @Transactional boundary as the upserts (M7 review fix: atomic batch persistence).
        return constraintWeightService.applyOverrides(planId, requests);
    }

    private void requirePlanExists(String planId) {
        if (activityPlanRepository.findById(planId).isEmpty()) {
            throw new NotFoundException("Activity plan not found: " + planId);
        }
    }
}
