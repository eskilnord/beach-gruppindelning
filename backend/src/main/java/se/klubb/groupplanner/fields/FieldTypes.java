package se.klubb.groupplanner.fields;

import java.util.Set;

/**
 * The 10 field types supported by the field builder (spec §9.3), as stored verbatim (lowerCamelCase)
 * in {@code field_definition.field_type}.
 */
public final class FieldTypes {

    public static final String TEXT = "text";
    public static final String NUMBER = "number";
    public static final String BOOLEAN = "boolean";
    public static final String SINGLE_SELECT = "singleSelect";
    public static final String MULTI_SELECT = "multiSelect";
    public static final String PERSON_RELATION = "personRelation";
    public static final String COACH_RELATION = "coachRelation";
    public static final String TIME_RELATION = "timeRelation";
    public static final String GROUP_RELATION = "groupRelation";
    public static final String TAG = "tag";

    public static final Set<String> ALL = Set.of(
            TEXT, NUMBER, BOOLEAN, SINGLE_SELECT, MULTI_SELECT,
            PERSON_RELATION, COACH_RELATION, TIME_RELATION, GROUP_RELATION, TAG);

    /** Field types whose value is a list of the plan's own entities (participants/coaches/groups)
     * rather than a scalar/free-form value — relevant both for value validation (M4) and for the
     * solver input assembler (M6), which reads these off {@code custom_field_value} generically. */
    public static final Set<String> RELATION_TYPES = Set.of(PERSON_RELATION, COACH_RELATION, GROUP_RELATION);

    private FieldTypes() {
    }

    public static boolean isValid(String fieldType) {
        return fieldType != null && ALL.contains(fieldType);
    }
}
