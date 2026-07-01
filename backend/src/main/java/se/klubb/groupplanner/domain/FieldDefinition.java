package se.klubb.groupplanner.domain;

/**
 * A standard or custom field (spec §7.13 / §9). {@code activityPlanId == null} means the field is
 * global/standard (seeded by {@code V2__seed_constraints_and_standard_fields.sql}); per-plan custom
 * fields (non-null {@code activityPlanId}, {@code isStandard == false}) are created starting M4.
 *
 * <p>{@code storageKind} is {@code COLUMN} (the value lives on a {@link ParticipantProfile} column)
 * or {@code CUSTOM} (the value lives in {@code custom_field_value}). For {@code COLUMN} fields,
 * {@code columnName} carries the actual {@code participant_profile} column name (e.g.
 * {@code needsManualReview} maps to {@code manual_review_flag}); it is {@code null} for
 * {@code CUSTOM} fields — enforced by a CHECK constraint in V2, so no camelCase-to-snake_case
 * guessing is ever needed.
 */
public record FieldDefinition(
        String id,
        String activityPlanId,
        String key,
        String label,
        String fieldType,
        boolean isStandard,
        String storageKind,
        String columnName,
        boolean affectsOptimization,
        String constraintType,
        String hardOrSoft,
        Integer weight,
        String direction,
        String explanationText,
        String optionsJson,
        Integer sortOrder) {
}
