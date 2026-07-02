package se.klubb.groupplanner.fields;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One {@code CUSTOM}-storage field's current value for some entity (spec §7.14) — the shape
 * returned by {@code GET /api/plans/{planId}/participants/{pid}/field-values}. {@code value} is
 * {@code null} (JSON {@code null}) when no {@code custom_field_value} row exists yet for this
 * entity/field pair.
 */
public record FieldValueView(String fieldDefinitionId, String key, String label, String fieldType, JsonNode value) {
}
