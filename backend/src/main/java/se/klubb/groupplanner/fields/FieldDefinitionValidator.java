package se.klubb.groupplanner.fields;

import org.springframework.stereotype.Component;
import se.klubb.groupplanner.api.error.BadRequestException;

/**
 * Validates the optimization-facing shape of a field definition (spec §9.3/§9.4), shared by field
 * CRUD (create + patch, both custom and the restricted standard-field patch) and by
 * {@link ConstraintWeightService} (the MEDIUM-rejection rule is identical in spirit).
 *
 * <p>Mirroring {@code ConstraintWeightService.validateReclassification}, there are two paths:
 * {@link #validate} for ordinary fields, where MEDIUM may never be <em>introduced</em>; and
 * {@link #validateReservedMediumPatch} for fields whose <em>existing</em> classification is already
 * MEDIUM (today only the seeded standard {@code priority} field, which scales M6's
 * {@code unassignedPlayer} waitlist penalty per ADR-006) — those must <em>stay</em> MEDIUM and
 * optimization-affecting, while weight/direction/explanationText remain editable.
 */
@Component
public class FieldDefinitionValidator {

    /**
     * Validates the combination of {@code fieldType}/{@code affectsOptimization}/{@code
     * constraintType}/{@code hardOrSoft}/{@code weight} that a create-or-patch request would result
     * in, for a field that is NOT currently MEDIUM-classified. Throws {@link BadRequestException}
     * with a message safe to show verbatim in the Swedish UI.
     */
    public void validate(String fieldType, boolean affectsOptimization, String constraintType, String hardOrSoft, Integer weight) {
        if (!FieldTypes.isValid(fieldType)) {
            throw new BadRequestException("Unknown fieldType: " + fieldType);
        }
        if (constraintType == null || !ConstraintTypes.isValid(constraintType)) {
            throw new BadRequestException("Unknown constraintType: " + constraintType);
        }
        if (HardOrSoft.MEDIUM.equals(hardOrSoft)) {
            throw new BadRequestException(
                    "hardOrSoft MEDIUM is reserved for the system waitlist penalty (ADR-006) and cannot be set here");
        }

        if (!affectsOptimization) {
            if (!ConstraintTypes.NONE.equals(constraintType)) {
                throw new BadRequestException("constraintType must be NONE when affectsOptimization is false");
            }
            if (hardOrSoft != null && !HardOrSoft.INFO.equals(hardOrSoft)) {
                throw new BadRequestException("hardOrSoft must be INFO (or omitted) when affectsOptimization is false");
            }
            if (weight != null) {
                throw new BadRequestException("weight must be omitted when affectsOptimization is false");
            }
            return;
        }

        // affectsOptimization == true from here on.
        if (ConstraintTypes.NONE.equals(constraintType)) {
            throw new BadRequestException("constraintType is required (not NONE) when affectsOptimization is true");
        }
        if (!ConstraintTypes.isCompatible(fieldType, constraintType)) {
            throw new BadRequestException(
                    "constraintType " + constraintType + " is not compatible with fieldType " + fieldType
                            + " - compatible types: " + ConstraintTypes.compatibleConstraintTypes(fieldType));
        }
        if (hardOrSoft == null || !HardOrSoft.USER_SETTABLE_FOR_OPTIMIZING_FIELDS.contains(hardOrSoft)) {
            throw new BadRequestException("hardOrSoft must be HARD or SOFT when affectsOptimization is true");
        }
        if (HardOrSoft.SOFT.equals(hardOrSoft)) {
            if (weight == null || weight < WeightLimits.MIN_WEIGHT) {
                throw new BadRequestException("weight must be a positive integer when hardOrSoft is SOFT");
            }
            if (weight > WeightLimits.MAX_WEIGHT) {
                // The solver's score overflow-headroom analysis assumes this ceiling (WeightLimits javadoc).
                throw new BadRequestException("weight must be <= " + WeightLimits.MAX_WEIGHT);
            }
        } else if (weight != null) {
            throw new BadRequestException("weight must be omitted when hardOrSoft is HARD");
        }
    }

    /**
     * Validates a PATCH against a field whose existing classification is MEDIUM (reserved,
     * ADR-006: today only the standard {@code priority} field, whose weight scales the solver's
     * {@code unassignedPlayer} waitlist penalty in M6). The reservation is a two-way door that must
     * be closed on both sides: {@link #validate} stops MEDIUM being <em>introduced</em>; this stops
     * it being <em>removed</em> — the field must stay MEDIUM and optimization-affecting, while
     * weight (floor &ge; 1), direction and explanationText stay editable.
     *
     * @param requestedHardOrSoft the raw requested value, {@code null} if the PATCH omitted it.
     * @param requestedAffectsOptimization the raw requested value, {@code null} if omitted.
     * @param existingConstraintType the field's CURRENT {@code constraintType} before this patch
     *     (e.g. {@code PRIORITY} for the seeded {@code priority} field) - {@code constraintType} must
     *     equal this (M6a carryover fix, backend/docs/m6a-notes.md item 8): a plain fieldType/
     *     constraintType compatibility check alone is NOT enough here, because {@code NUMBER} fields
     *     are compatible with both {@code PRIORITY} and {@code LEVEL_BALANCE_INPUT} - without this
     *     check a PATCH could silently retarget the reserved field away from PRIORITY, which
     *     {@code SolverInputAssembler} keys off by {@code constraintType} (not by field {@code key}),
     *     silently defaulting every participant's priority to 3 and breaking §2's "shed
     *     lowest-priority-first" waitlist guarantee without ever touching {@code hardOrSoft}.
     */
    public void validateReservedMediumPatch(
            String fieldType,
            Boolean requestedAffectsOptimization,
            String requestedHardOrSoft,
            String constraintType,
            String existingConstraintType,
            Integer weight) {
        if (requestedAffectsOptimization != null && !requestedAffectsOptimization) {
            throw new BadRequestException(
                    "This field's MEDIUM classification is reserved for the system waitlist penalty (ADR-006)"
                            + " - affectsOptimization cannot be turned off");
        }
        if (requestedHardOrSoft != null && !HardOrSoft.MEDIUM.equals(requestedHardOrSoft)) {
            throw new BadRequestException(
                    "This field is reserved as MEDIUM for the system waitlist penalty (ADR-006)"
                            + " and cannot be reclassified to " + requestedHardOrSoft);
        }
        if (constraintType == null || !ConstraintTypes.isCompatible(fieldType, constraintType)) {
            throw new BadRequestException(
                    "constraintType " + constraintType + " is not compatible with fieldType " + fieldType
                            + " - compatible types: " + ConstraintTypes.compatibleConstraintTypes(fieldType));
        }
        if (!constraintType.equals(existingConstraintType)) {
            throw new BadRequestException(
                    "This field's constraintType is reserved as " + existingConstraintType
                            + " for the system waitlist penalty (ADR-006) and cannot be changed to " + constraintType);
        }
        if (weight == null || weight < WeightLimits.MIN_WEIGHT) {
            throw new BadRequestException("weight must be >= " + WeightLimits.MIN_WEIGHT + " for a MEDIUM (reserved) field");
        }
        if (weight > WeightLimits.MAX_WEIGHT) {
            throw new BadRequestException("weight must be <= " + WeightLimits.MAX_WEIGHT);
        }
    }
}
