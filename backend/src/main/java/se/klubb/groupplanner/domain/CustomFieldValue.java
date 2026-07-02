package se.klubb.groupplanner.domain;

/**
 * A single field value for a {@code CUSTOM}-storage {@link FieldDefinition} (spec §7.14), attached
 * to some entity (participant/coach/group) by {@code entityType}/{@code entityId}. {@code
 * valueJson} is a JSON-encoded value (string/number/boolean/array depending on the field's {@code
 * fieldType}) — free-form on purpose, since custom fields can be created ad hoc (spec §9, M4).
 */
public record CustomFieldValue(
        String id,
        String fieldDefinitionId,
        String entityType,
        String entityId,
        String valueJson) {

    /** {@code entityType} value used for participant-scoped custom field values. */
    public static final String ENTITY_TYPE_PARTICIPANT = "participant";

    /** {@code entityType} value used for coach-scoped custom field values (M5). */
    public static final String ENTITY_TYPE_COACH = "coach";
}
