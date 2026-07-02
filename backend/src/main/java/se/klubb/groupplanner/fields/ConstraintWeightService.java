package se.klubb.groupplanner.fields;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.domain.ConstraintDefinition;
import se.klubb.groupplanner.domain.ConstraintWeightConfig;
import se.klubb.groupplanner.repo.ConstraintDefinitionRepository;
import se.klubb.groupplanner.repo.ConstraintWeightConfigRepository;

/**
 * Per-plan constraint weight overrides (spec §9.4, §7.16) — {@code GET|PUT
 * /api/plans/{planId}/constraint-weights}. Merges the 24 seeded {@code constraint_definition}
 * defaults with this plan's {@code constraint_weight_config} rows; unset rows fall back to the
 * definition's default weight/classification/enabled state.
 *
 * <p><b>Guardrail hook (spec/ADR-006, docs/plan.md M4 row):</b> the real {@code unassignedPlayer}
 * waitlist constraint (MEDIUM-classified, not disableable, weight floor &ge; 1) does not exist until
 * M6a seeds it, but {@link #validateReclassification} already enforces those rules generically for
 * ANY constraint_definition row classified MEDIUM, so M6a needs zero changes here when that row
 * lands. For today's 24 HARD/SOFT rows this only enforces "HARD/SOFT only, weight &ge; 1".
 */
@Service
public class ConstraintWeightService {

    /**
     * Structural feasibility constraints that may never be disabled (keys exactly as seeded by
     * V2__seed_constraints_and_standard_fields.sql). Disabling any of these would let the solver
     * produce a physically impossible or contract-breaking schedule: two groups on one court/time,
     * a group with no court/time, a player or coach in two places at once, a player/coach placed
     * on a time they cannot attend, an overridden lock, or a double-booking against an already
     * locked plan. They stay reclassifiable HARD&lt;-&gt;SOFT (the spec's own worked examples do
     * that), but {@code enabled=false} is rejected. Everything else (size targets, level balance,
     * continuity, preferences, group ordering, late-time policy) is freely disableable per spec §9.
     */
    private static final java.util.Set<String> NEVER_DISABLEABLE_KEYS = java.util.Set.of(
            "trainingBlockCapacity",
            "groupRequiresTrainingBlock",
            "groupMaxSizeHard",
            "playerNoOverlap",
            "coachNoOverlap",
            "coachCannotTrainAndCoachSameTime",
            "timeAvailabilityHard",
            "coachAvailabilityHard",
            "lockedAssignmentHard",
            "savedPlanResourceBlock");

    private final ConstraintDefinitionRepository constraintDefinitionRepository;
    private final ConstraintWeightConfigRepository constraintWeightConfigRepository;

    public ConstraintWeightService(
            ConstraintDefinitionRepository constraintDefinitionRepository,
            ConstraintWeightConfigRepository constraintWeightConfigRepository) {
        this.constraintDefinitionRepository = constraintDefinitionRepository;
        this.constraintWeightConfigRepository = constraintWeightConfigRepository;
    }

    public List<ConstraintWeightView> listForPlan(String planId) {
        Map<String, ConstraintWeightConfig> overrides = overridesByKey(planId);
        return constraintDefinitionRepository.findAll().stream()
                .map(def -> mergeView(def, overrides.get(def.key())))
                .toList();
    }

    /** Applies each override entry (merged onto the currently-effective row), validates it, persists
     * it, and returns the full merged list (same shape as {@link #listForPlan}). */
    public List<ConstraintWeightView> applyOverrides(String planId, List<ConstraintWeightOverrideRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new BadRequestException("Request body must be a non-empty list of overrides");
        }
        Map<String, ConstraintDefinition> defsByKey = new HashMap<>();
        for (ConstraintDefinition def : constraintDefinitionRepository.findAll()) {
            defsByKey.put(def.key(), def);
        }

        for (ConstraintWeightOverrideRequest request : requests) {
            if (request == null || request.key() == null || request.key().isBlank()) {
                throw new BadRequestException("key is required for every constraint-weight override entry");
            }
            ConstraintDefinition def = defsByKey.get(request.key());
            if (def == null) {
                throw new BadRequestException("Unknown constraint key: " + request.key());
            }

            ConstraintWeightConfig existing = constraintWeightConfigRepository
                    .findByActivityPlanIdAndKey(planId, request.key())
                    .orElse(null);
            String baseHardOrSoft = existing != null ? existing.hardOrSoft() : def.hardOrSoft();
            int baseWeight = existing != null ? existing.weight() : def.defaultWeight();
            boolean baseEnabled = existing != null ? existing.enabled() : def.enabled();

            String newHardOrSoft = request.hardOrSoft() != null ? request.hardOrSoft() : baseHardOrSoft;
            int newWeight = request.weight() != null ? request.weight() : baseWeight;
            boolean newEnabled = request.enabled() != null ? request.enabled() : baseEnabled;

            validateReclassification(def, newHardOrSoft, newWeight, newEnabled);

            constraintWeightConfigRepository.upsert(planId, request.key(), newHardOrSoft, newWeight, newEnabled);
        }
        return listForPlan(planId);
    }

    private void validateReclassification(ConstraintDefinition def, String hardOrSoft, int weight, boolean enabled) {
        if (HardOrSoft.MEDIUM.equals(def.hardOrSoft())) {
            // Reserved system constraint (M6a's unassignedPlayer will be the first row like this).
            if (!enabled) {
                throw new BadRequestException(
                        "Constraint '" + def.key() + "' cannot be disabled (reserved system constraint)");
            }
            if (!HardOrSoft.MEDIUM.equals(hardOrSoft)) {
                throw new BadRequestException(
                        "Constraint '" + def.key() + "' cannot be reclassified away from MEDIUM (reserved system constraint)");
            }
        } else if (!HardOrSoft.USER_SETTABLE_FOR_OPTIMIZING_FIELDS.contains(hardOrSoft)) {
            throw new BadRequestException(
                    "hardOrSoft must be HARD or SOFT for constraint '" + def.key() + "' (MEDIUM is reserved, ADR-006)");
        }
        if (!enabled && NEVER_DISABLEABLE_KEYS.contains(def.key())) {
            throw new BadRequestException(
                    "Constraint '" + def.key() + "' cannot be disabled - it prevents physically impossible schedules");
        }
        if (weight < 1) {
            throw new BadRequestException("weight must be >= 1 for constraint '" + def.key() + "'");
        }
    }

    private Map<String, ConstraintWeightConfig> overridesByKey(String planId) {
        Map<String, ConstraintWeightConfig> byKey = new HashMap<>();
        for (ConstraintWeightConfig config : constraintWeightConfigRepository.findByActivityPlanId(planId)) {
            byKey.put(config.constraintKey(), config);
        }
        return byKey;
    }

    private static ConstraintWeightView mergeView(ConstraintDefinition def, ConstraintWeightConfig override) {
        if (override == null) {
            return new ConstraintWeightView(
                    def.key(), def.label(), def.description(), def.constraintCategory(),
                    def.hardOrSoft(), def.defaultWeight(), def.enabled(), false);
        }
        return new ConstraintWeightView(
                def.key(), def.label(), def.description(), def.constraintCategory(),
                override.hardOrSoft(), override.weight(), override.enabled(), true);
    }
}
