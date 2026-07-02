package se.klubb.groupplanner.fields;

import java.util.Map;
import java.util.Set;

/**
 * The {@code field_definition.constraint_type} enum (spec §9.4, seeded examples in
 * V2__seed_constraints_and_standard_fields.sql) and the fieldType -&gt; constraintType compatibility
 * table (M4 addition — the spec gives worked examples in §9.1 but not an exhaustive table; this is
 * this milestone's own design decision, documented in backend/docs/m4-notes.md).
 *
 * <p>The compatibility table intentionally allows {@code NONE} for every field type (an
 * information-only field of any type is always valid) and otherwise only allows the constraint
 * families that a field of that shape could plausibly drive in the M6 solver assembler: a
 * {@code personRelation} field can express "these people should/shouldn't share a group"
 * ({@code SAME_GROUP}/{@code DIFFERENT_GROUP}), a {@code timeRelation} field expresses availability
 * or preference, a {@code coachRelation} field expresses a coach wish/ban, and a {@code number}
 * field can feed the level-balance input or the waitlist priority ordering. {@code groupRelation}
 * has no constraint-type mapping yet (no §10 constraint consumes an arbitrary group reference field
 * in MVP) — it stays information-only until a concrete use case exists.
 */
public final class ConstraintTypes {

    public static final String NONE = "NONE";
    public static final String LEVEL_BALANCE_INPUT = "LEVEL_BALANCE_INPUT";
    public static final String TIME_AVAILABILITY = "TIME_AVAILABILITY";
    public static final String TIME_PREFERENCE = "TIME_PREFERENCE";
    public static final String SAME_GROUP = "SAME_GROUP";
    public static final String DIFFERENT_GROUP = "DIFFERENT_GROUP";
    public static final String COACH_PREFERENCE = "COACH_PREFERENCE";
    public static final String COACH_FORBIDDEN = "COACH_FORBIDDEN";
    public static final String PRIORITY = "PRIORITY";

    public static final Set<String> ALL = Set.of(
            NONE, LEVEL_BALANCE_INPUT, TIME_AVAILABILITY, TIME_PREFERENCE, SAME_GROUP, DIFFERENT_GROUP,
            COACH_PREFERENCE, COACH_FORBIDDEN, PRIORITY);

    private static final Map<String, Set<String>> COMPATIBLE_BY_FIELD_TYPE = Map.ofEntries(
            Map.entry(FieldTypes.TEXT, Set.of(NONE)),
            Map.entry(FieldTypes.NUMBER, Set.of(NONE, LEVEL_BALANCE_INPUT, PRIORITY)),
            Map.entry(FieldTypes.BOOLEAN, Set.of(NONE)),
            Map.entry(FieldTypes.SINGLE_SELECT, Set.of(NONE)),
            Map.entry(FieldTypes.MULTI_SELECT, Set.of(NONE)),
            Map.entry(FieldTypes.PERSON_RELATION, Set.of(NONE, SAME_GROUP, DIFFERENT_GROUP)),
            Map.entry(FieldTypes.COACH_RELATION, Set.of(NONE, COACH_PREFERENCE, COACH_FORBIDDEN)),
            Map.entry(FieldTypes.TIME_RELATION, Set.of(NONE, TIME_AVAILABILITY, TIME_PREFERENCE)),
            Map.entry(FieldTypes.GROUP_RELATION, Set.of(NONE)),
            Map.entry(FieldTypes.TAG, Set.of(NONE)));

    private ConstraintTypes() {
    }

    public static boolean isValid(String constraintType) {
        return constraintType != null && ALL.contains(constraintType);
    }

    public static boolean isCompatible(String fieldType, String constraintType) {
        Set<String> allowed = COMPATIBLE_BY_FIELD_TYPE.get(fieldType);
        return allowed != null && allowed.contains(constraintType);
    }

    public static Set<String> compatibleConstraintTypes(String fieldType) {
        return COMPATIBLE_BY_FIELD_TYPE.getOrDefault(fieldType, Set.of(NONE));
    }
}
