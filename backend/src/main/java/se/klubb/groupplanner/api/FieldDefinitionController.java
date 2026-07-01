package se.klubb.groupplanner.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.FieldDefinition;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;

/**
 * Read-only listing of field definitions for a plan (spec §9) — docs/design/02-product-data-ui.md
 * §8: {@code GET /api/plans/{planId}/field-definitions}.
 *
 * <p>M1 scope only returns the global standard fields (seeded by
 * {@code V2__seed_constraints_and_standard_fields.sql}); activity-plan-scoped custom fields (the
 * Fältbyggare CRUD flow) arrive in M4. {@code planId} is still validated so the endpoint 404s for an
 * unknown plan rather than silently returning the global list.
 */
@RestController
public class FieldDefinitionController {

    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final ActivityPlanRepository activityPlanRepository;

    public FieldDefinitionController(
            FieldDefinitionRepository fieldDefinitionRepository, ActivityPlanRepository activityPlanRepository) {
        this.fieldDefinitionRepository = fieldDefinitionRepository;
        this.activityPlanRepository = activityPlanRepository;
    }

    @GetMapping("/api/plans/{planId}/field-definitions")
    public List<FieldDefinition> listForPlan(@PathVariable String planId) {
        if (activityPlanRepository.findById(planId).isEmpty()) {
            throw new NotFoundException("Activity plan not found: " + planId);
        }
        return fieldDefinitionRepository.findStandardFields();
    }
}
