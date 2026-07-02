package se.klubb.groupplanner.fields;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.domain.CustomFieldValue;
import se.klubb.groupplanner.domain.FieldDefinition;
import se.klubb.groupplanner.importer.parse.SwedishTimeParser;
import se.klubb.groupplanner.repo.CoachProfileRepository;
import se.klubb.groupplanner.repo.CustomFieldValueRepository;
import se.klubb.groupplanner.repo.FieldDefinitionRepository;
import se.klubb.groupplanner.repo.ParticipantProfileRepository;

/**
 * Generic bulk read/write of {@code custom_field_value} rows for one entity (spec §7.14, §9.1
 * structured-entry flow). Generic over {@code entityType} on purpose (docs/plan.md M4 brief:
 * "design the repo generically (entity_type)") — M4 only wires the participant-scoped controller
 * endpoint, but this service works unchanged for coaches once M5 adds that controller.
 *
 * <p>Only {@code CUSTOM}-storage fields are ever written here; {@code COLUMN}-storage fields (e.g.
 * {@code manualLevelScore}) are edited through the owning entity's own PATCH endpoint (participant
 * PATCH), since their value lives on that table's own column, not in {@code custom_field_value}.
 *
 * <p>Value shape per {@code fieldType} (JSON, spec §9.3):
 * <ul>
 *   <li>{@code text} — JSON string</li>
 *   <li>{@code number} — JSON number</li>
 *   <li>{@code boolean} — JSON boolean</li>
 *   <li>{@code singleSelect} — JSON string, must be one of {@code options_json} if the field
 *       declares options</li>
 *   <li>{@code multiSelect} — JSON array of strings, each must be one of {@code options_json} if
 *       declared</li>
 *   <li>{@code tag} — JSON array of free-form strings (no predefined option list)</li>
 *   <li>{@code personRelation} — JSON array of {@code participant_profile} ids, each validated to
 *       exist in the same plan; self-reference rejected</li>
 *   <li>{@code coachRelation} — JSON array of {@code coach_profile} ids, each validated to exist in
 *       the same plan</li>
 *   <li>{@code groupRelation} — JSON array of {@code training_group} ids, each validated to exist in
 *       the same plan (the group CRUD/repository itself arrives in M5; this is a minimal direct
 *       existence check)</li>
 *   <li>{@code timeRelation} — JSON array of free-text time expressions, each validated with the
 *       existing {@link SwedishTimeParser} grammar (spec §8.6 "ogiltiga tider") rather than
 *       resolved against {@code time_slot} rows, since structured TimeSlot CRUD is M5 — see
 *       backend/docs/m4-notes.md</li>
 * </ul>
 *
 * <p>A JSON {@code null} value clears the field (deletes/nulls the stored value) — same
 * absent-vs-null distinction as the participant PATCH endpoint, except here every field in the
 * request body is by definition "present", so plain JSON {@code null} unambiguously means "clear
 * this one".
 */
@Service
public class FieldValueService {

    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final CustomFieldValueRepository customFieldValueRepository;
    private final ParticipantProfileRepository participantProfileRepository;
    private final CoachProfileRepository coachProfileRepository;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public FieldValueService(
            FieldDefinitionRepository fieldDefinitionRepository,
            CustomFieldValueRepository customFieldValueRepository,
            ParticipantProfileRepository participantProfileRepository,
            CoachProfileRepository coachProfileRepository,
            JdbcClient jdbcClient,
            ObjectMapper objectMapper) {
        this.fieldDefinitionRepository = fieldDefinitionRepository;
        this.customFieldValueRepository = customFieldValueRepository;
        this.participantProfileRepository = participantProfileRepository;
        this.coachProfileRepository = coachProfileRepository;
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public List<FieldValueView> getValues(String planId, String entityType, String entityId) {
        Map<String, CustomFieldValue> byFieldId = new HashMap<>();
        for (CustomFieldValue value : customFieldValueRepository.findByEntity(entityType, entityId)) {
            byFieldId.put(value.fieldDefinitionId(), value);
        }
        List<FieldValueView> views = new ArrayList<>();
        for (FieldDefinition field : fieldDefinitionRepository.findVisibleToPlan(planId)) {
            if (!"CUSTOM".equals(field.storageKind())) {
                continue;
            }
            CustomFieldValue stored = byFieldId.get(field.id());
            JsonNode value = (stored == null || stored.valueJson() == null)
                    ? NullNode.getInstance()
                    : parseJson(stored.valueJson());
            views.add(new FieldValueView(field.id(), field.key(), field.label(), field.fieldType(), value));
        }
        return views;
    }

    @Transactional
    public List<FieldValueView> putValues(String planId, String entityType, String entityId, Map<String, JsonNode> values) {
        if (values == null || values.isEmpty()) {
            throw new BadRequestException("Request body must be a non-empty map of fieldKey -> value");
        }
        for (Map.Entry<String, JsonNode> entry : values.entrySet()) {
            FieldDefinition field = fieldDefinitionRepository.findByKeyVisibleToPlan(planId, entry.getKey())
                    .orElseThrow(() -> new BadRequestException("Unknown field key: " + entry.getKey()));
            if (!"CUSTOM".equals(field.storageKind())) {
                throw new BadRequestException(
                        "'" + entry.getKey() + "' is a structured (COLUMN-storage) field - update it via the "
                                + "owning entity's PATCH endpoint instead of field-values");
            }
            String valueJson = validateAndEncode(field, entry.getValue(), planId, entityId);
            customFieldValueRepository.upsert(field.id(), entityType, entityId, valueJson);
        }
        return getValues(planId, entityType, entityId);
    }

    private String validateAndEncode(FieldDefinition field, JsonNode value, String planId, String selfEntityId) {
        if (value == null || value.isNull()) {
            return null; // clears the value.
        }
        return switch (field.fieldType()) {
            case FieldTypes.TEXT -> {
                requireType(field, value.isTextual(), "a string");
                yield value.toString();
            }
            case FieldTypes.NUMBER -> {
                requireType(field, value.isNumber(), "a number");
                yield value.toString();
            }
            case FieldTypes.BOOLEAN -> {
                requireType(field, value.isBoolean(), "a boolean");
                yield value.toString();
            }
            case FieldTypes.SINGLE_SELECT -> {
                requireType(field, value.isTextual(), "a string");
                requireOption(field, value.asText());
                yield value.toString();
            }
            case FieldTypes.MULTI_SELECT -> {
                List<String> items = requireStringArray(field, value);
                for (String item : items) {
                    requireOption(field, item);
                }
                yield value.toString();
            }
            case FieldTypes.TAG -> {
                requireStringArray(field, value);
                yield value.toString();
            }
            case FieldTypes.PERSON_RELATION -> {
                List<String> ids = requireStringArray(field, value);
                for (String id : ids) {
                    if (id.equals(selfEntityId)) {
                        throw new BadRequestException("Field '" + field.key() + "': cannot reference itself");
                    }
                    if (participantProfileRepository.findById(id)
                            .filter(p -> p.activityPlanId().equals(planId))
                            .isEmpty()) {
                        throw new BadRequestException(
                                "Field '" + field.key() + "': unknown participant id in this plan: " + id);
                    }
                }
                yield value.toString();
            }
            case FieldTypes.COACH_RELATION -> {
                List<String> ids = requireStringArray(field, value);
                for (String id : ids) {
                    if (coachProfileRepository.findById(id)
                            .filter(c -> c.activityPlanId().equals(planId))
                            .isEmpty()) {
                        throw new BadRequestException(
                                "Field '" + field.key() + "': unknown coach id in this plan: " + id);
                    }
                }
                yield value.toString();
            }
            case FieldTypes.GROUP_RELATION -> {
                List<String> ids = requireStringArray(field, value);
                for (String id : ids) {
                    if (!groupExistsInPlan(id, planId)) {
                        throw new BadRequestException(
                                "Field '" + field.key() + "': unknown group id in this plan: " + id);
                    }
                }
                yield value.toString();
            }
            case FieldTypes.TIME_RELATION -> {
                List<String> expressions = requireStringArray(field, value);
                for (String expression : expressions) {
                    if (!SwedishTimeParser.isValidTimeExpression(expression)) {
                        throw new BadRequestException(
                                "Field '" + field.key() + "': ogiltig tid - kontrollera manuellt: '" + expression + "'");
                    }
                }
                yield value.toString();
            }
            default -> throw new IllegalStateException("Unhandled fieldType: " + field.fieldType());
        };
    }

    private boolean groupExistsInPlan(String groupId, String planId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM training_group WHERE id = :id AND activity_plan_id = :planId")
                .param("id", groupId)
                .param("planId", planId)
                .query(Integer.class)
                .single() > 0;
    }

    private void requireOption(FieldDefinition field, String candidate) {
        List<String> options = parseOptions(field);
        if (!options.isEmpty() && !options.contains(candidate)) {
            throw new BadRequestException(
                    "Field '" + field.key() + "': value '" + candidate + "' is not one of the declared options " + options);
        }
    }

    private List<String> parseOptions(FieldDefinition field) {
        if (field.optionsJson() == null || field.optionsJson().isBlank()) {
            return List.of();
        }
        JsonNode node = parseJson(field.optionsJson());
        if (!node.isArray()) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        node.forEach(n -> options.add(n.asText()));
        return options;
    }

    private static List<String> requireStringArray(FieldDefinition field, JsonNode value) {
        if (!value.isArray()) {
            throw new BadRequestException("Field '" + field.key() + "' requires a JSON array value");
        }
        List<String> items = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isTextual()) {
                throw new BadRequestException("Field '" + field.key() + "': array elements must be strings");
            }
            items.add(element.asText());
        }
        return items;
    }

    private static void requireType(FieldDefinition field, boolean matches, String expectedDescription) {
        if (!matches) {
            throw new BadRequestException("Field '" + field.key() + "' requires " + expectedDescription + " value");
        }
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt value_json for a custom field value", e);
        }
    }
}
