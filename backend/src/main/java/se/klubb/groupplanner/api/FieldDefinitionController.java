package se.klubb.groupplanner.api;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.api.error.NotFoundException;
import se.klubb.groupplanner.domain.FieldDefinition;
import se.klubb.groupplanner.fields.ConstraintTypes;
import se.klubb.groupplanner.fields.FieldDefinitionValidator;
import se.klubb.groupplanner.fields.FieldTypes;
import se.klubb.groupplanner.fields.HardOrSoft;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * Field builder REST surface (spec §9, docs/design/02-product-data-ui.md §3/§8):
 *
 * <ul>
 *   <li>{@code GET /api/plans/{planId}/field-definitions} — every field visible to the plan
 *       (globals + the plan's own custom fields), M1 scope.</li>
 *   <li>{@code POST /api/plans/{planId}/field-definitions} — create a plan-scoped {@code CUSTOM}
 *       field (M4).</li>
 *   <li>{@code PATCH /api/field-definitions/{id}} — edit a field. Standard/global fields only allow
 *       changing the optimization-facing subset ({@code affectsOptimization}/{@code
 *       constraintType}/{@code hardOrSoft}/{@code weight}/{@code direction}/{@code
 *       explanationText}) — never {@code key}/{@code fieldType}/{@code storageKind}/{@code label}/
 *       {@code optionsJson}/{@code sortOrder}. Custom fields additionally allow {@code label}/{@code
 *       optionsJson}/{@code sortOrder}.</li>
 *   <li>{@code DELETE /api/field-definitions/{id}} — custom fields only; standard/global fields are
 *       permanent (403-equivalent 400, "Standard/global fields cannot be deleted").</li>
 * </ul>
 */
@RestController
public class FieldDefinitionController {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9]*$");

    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final ActivityPlanRepository activityPlanRepository;
    private final FieldDefinitionValidator fieldDefinitionValidator;

    public FieldDefinitionController(
            FieldDefinitionRepository fieldDefinitionRepository,
            ActivityPlanRepository activityPlanRepository,
            FieldDefinitionValidator fieldDefinitionValidator) {
        this.fieldDefinitionRepository = fieldDefinitionRepository;
        this.activityPlanRepository = activityPlanRepository;
        this.fieldDefinitionValidator = fieldDefinitionValidator;
    }

    @GetMapping("/api/plans/{planId}/field-definitions")
    public List<FieldDefinition> listForPlan(@PathVariable String planId) {
        requirePlanExists(planId);
        return fieldDefinitionRepository.findVisibleToPlan(planId);
    }

    @PostMapping("/api/plans/{planId}/field-definitions")
    @ResponseStatus(HttpStatus.CREATED)
    public FieldDefinition create(@PathVariable String planId, @RequestBody CreateFieldDefinitionRequest request) {
        requirePlanExists(planId);
        if (request == null || request.key() == null || request.key().isBlank()) {
            throw new BadRequestException("key is required");
        }
        if (!KEY_PATTERN.matcher(request.key()).matches()) {
            throw new BadRequestException("key must start with a letter and contain only letters/digits (camelCase)");
        }
        if (request.label() == null || request.label().isBlank()) {
            throw new BadRequestException("label is required");
        }
        if (!FieldTypes.isValid(request.fieldType())) {
            throw new BadRequestException("Unknown fieldType: " + request.fieldType());
        }
        if (fieldDefinitionRepository.findGlobalByKey(request.key()).isPresent()) {
            throw new BadRequestException("key '" + request.key() + "' is already used by a standard field");
        }
        // A plan's own custom field with the same key would otherwise fail opaquely on the
        // UNIQUE(activity_plan_id, key) constraint (409) - checked here first for a clearer message.
        if (fieldDefinitionRepository.findCustomFieldsForPlan(planId).stream().anyMatch(f -> f.key().equals(request.key()))) {
            throw new BadRequestException("key '" + request.key() + "' is already used by another field on this plan");
        }

        boolean affectsOptimization = request.affectsOptimization() != null && request.affectsOptimization();
        String constraintType = request.constraintType() != null ? request.constraintType() : ConstraintTypes.NONE;
        String hardOrSoft = request.hardOrSoft() != null ? request.hardOrSoft() : (affectsOptimization ? null : HardOrSoft.INFO);
        Integer weight = affectsOptimization ? request.weight() : null;

        fieldDefinitionValidator.validate(request.fieldType(), affectsOptimization, constraintType, hardOrSoft, weight);

        if ((FieldTypes.SINGLE_SELECT.equals(request.fieldType()) || FieldTypes.MULTI_SELECT.equals(request.fieldType()))
                && (request.optionsJson() == null || request.optionsJson().isBlank())) {
            throw new BadRequestException("optionsJson (a JSON array of option strings) is required for " + request.fieldType());
        }

        int sortOrder = request.sortOrder() != null ? request.sortOrder() : fieldDefinitionRepository.nextSortOrderForPlan(planId);

        FieldDefinition field = new FieldDefinition(
                Uuid7.generate(), planId, request.key(), request.label(), request.fieldType(), false, "CUSTOM", null,
                affectsOptimization, constraintType, hardOrSoft, weight, request.direction(), request.explanationText(),
                request.optionsJson(), sortOrder);
        return fieldDefinitionRepository.insert(field);
    }

    @PatchMapping("/api/field-definitions/{id}")
    public FieldDefinition update(@PathVariable String id, @RequestBody UpdateFieldDefinitionRequest request) {
        FieldDefinition existing = findOrThrow(id);
        if (request == null) {
            return existing;
        }

        if (existing.isStandard()) {
            if (request.label() != null) {
                throw new BadRequestException("label cannot be changed on a standard field");
            }
            if (request.optionsJson() != null) {
                throw new BadRequestException("optionsJson cannot be changed on a standard field");
            }
            if (request.sortOrder() != null) {
                throw new BadRequestException("sortOrder cannot be changed on a standard field");
            }
        }

        boolean affectsOptimization = request.affectsOptimization() != null
                ? request.affectsOptimization()
                : existing.affectsOptimization();

        String constraintType;
        String hardOrSoft;
        Integer weight;
        if (HardOrSoft.MEDIUM.equals(existing.hardOrSoft())) {
            // The field is already MEDIUM-classified (reserved, ADR-006 - the seeded standard
            // `priority` field). It must STAY MEDIUM and optimization-affecting; weight (floor >= 1),
            // direction and explanationText remain editable. Mirrors
            // ConstraintWeightService.validateReclassification's MEDIUM branch.
            constraintType = request.constraintType() != null ? request.constraintType() : existing.constraintType();
            hardOrSoft = HardOrSoft.MEDIUM;
            weight = request.weight() != null ? request.weight() : existing.weight();
            fieldDefinitionValidator.validateReservedMediumPatch(
                    existing.fieldType(), request.affectsOptimization(), request.hardOrSoft(), constraintType, weight);
            affectsOptimization = true;
        } else if (!affectsOptimization) {
            constraintType = ConstraintTypes.NONE;
            hardOrSoft = HardOrSoft.INFO;
            weight = null;
            fieldDefinitionValidator.validate(existing.fieldType(), false, constraintType, hardOrSoft, weight);
        } else {
            constraintType = request.constraintType() != null ? request.constraintType() : existing.constraintType();
            hardOrSoft = request.hardOrSoft() != null ? request.hardOrSoft() : existing.hardOrSoft();
            weight = HardOrSoft.HARD.equals(hardOrSoft)
                    ? null
                    : (request.weight() != null ? request.weight() : existing.weight());
            fieldDefinitionValidator.validate(existing.fieldType(), true, constraintType, hardOrSoft, weight);
        }

        FieldDefinition updated = new FieldDefinition(
                existing.id(), existing.activityPlanId(), existing.key(),
                !existing.isStandard() && request.label() != null ? request.label() : existing.label(),
                existing.fieldType(), existing.isStandard(), existing.storageKind(), existing.columnName(),
                affectsOptimization, constraintType, hardOrSoft, weight,
                request.direction() != null ? request.direction() : existing.direction(),
                request.explanationText() != null ? request.explanationText() : existing.explanationText(),
                !existing.isStandard() && request.optionsJson() != null ? request.optionsJson() : existing.optionsJson(),
                !existing.isStandard() && request.sortOrder() != null ? request.sortOrder() : existing.sortOrder());
        return fieldDefinitionRepository.update(updated);
    }

    @DeleteMapping("/api/field-definitions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        FieldDefinition existing = findOrThrow(id);
        if (existing.isStandard() || existing.activityPlanId() == null) {
            throw new BadRequestException("Standard/global fields cannot be deleted");
        }
        fieldDefinitionRepository.deleteById(id);
    }

    private void requirePlanExists(String planId) {
        if (activityPlanRepository.findById(planId).isEmpty()) {
            throw new NotFoundException("Activity plan not found: " + planId);
        }
    }

    private FieldDefinition findOrThrow(String id) {
        return fieldDefinitionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Field definition not found: " + id));
    }

    public record CreateFieldDefinitionRequest(
            String key,
            String label,
            String fieldType,
            Boolean affectsOptimization,
            String constraintType,
            String hardOrSoft,
            Integer weight,
            String direction,
            String explanationText,
            String optionsJson,
            Integer sortOrder) {
    }

    public record UpdateFieldDefinitionRequest(
            String label,
            Boolean affectsOptimization,
            String constraintType,
            String hardOrSoft,
            Integer weight,
            String direction,
            String explanationText,
            String optionsJson,
            Integer sortOrder) {
    }
}
