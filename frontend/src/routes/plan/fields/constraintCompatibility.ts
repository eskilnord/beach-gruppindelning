import { sv } from "../../../i18n/sv";

/**
 * Mirrors backend/src/main/java/se/klubb/groupplanner/fields/{FieldTypes,ConstraintTypes}.java
 * exactly, so the "Nytt fält" modal and the Fältbyggare table only ever offer combinations the
 * backend will accept (`FieldDefinitionValidator`/`ConstraintTypes.isCompatible`) - the frontend
 * mirror must be kept in sync if that table changes.
 */

export const FIELD_TYPES = [
  "text",
  "number",
  "boolean",
  "singleSelect",
  "multiSelect",
  "personRelation",
  "coachRelation",
  "timeRelation",
  "groupRelation",
  "tag",
] as const;

export type FieldType = (typeof FIELD_TYPES)[number];

export const CONSTRAINT_FAMILIES = [
  "NONE",
  "LEVEL_BALANCE_INPUT",
  "TIME_AVAILABILITY",
  "TIME_PREFERENCE",
  "SAME_GROUP",
  "DIFFERENT_GROUP",
  "COACH_PREFERENCE",
  "COACH_FORBIDDEN",
  "PRIORITY",
] as const;

export type ConstraintFamily = (typeof CONSTRAINT_FAMILIES)[number];

/** Field types whose value is a list of the plan's own entities (participants/coaches/groups)
 *  rather than a scalar/free-form value - mirrors FieldTypes.RELATION_TYPES. */
export const RELATION_FIELD_TYPES = new Set<FieldType>(["personRelation", "coachRelation", "groupRelation"]);

const COMPATIBLE_BY_FIELD_TYPE: Record<FieldType, ConstraintFamily[]> = {
  text: ["NONE"],
  number: ["NONE", "LEVEL_BALANCE_INPUT", "PRIORITY"],
  boolean: ["NONE"],
  singleSelect: ["NONE"],
  multiSelect: ["NONE"],
  personRelation: ["NONE", "SAME_GROUP", "DIFFERENT_GROUP"],
  coachRelation: ["NONE", "COACH_PREFERENCE", "COACH_FORBIDDEN"],
  timeRelation: ["NONE", "TIME_AVAILABILITY", "TIME_PREFERENCE"],
  groupRelation: ["NONE"],
  tag: ["NONE"],
};

/** Every constraintType compatible with a fieldType (spec §9.4), including NONE. */
export function compatibleConstraintFamilies(fieldType: string): ConstraintFamily[] {
  return COMPATIBLE_BY_FIELD_TYPE[fieldType as FieldType] ?? ["NONE"];
}

/** True if a field of this type could ever have `affectsOptimization: true` - i.e. at least one
 *  non-NONE constraint family is compatible with it. Fields whose only compatible family is NONE
 *  (text/boolean/singleSelect/multiSelect/groupRelation/tag) can never affect optimization; the
 *  "Nytt fält" modal disables that switch for them instead of offering a dead-end constraint
 *  select with only NONE (which the backend itself would then reject since a NONE constraintType
 *  requires affectsOptimization=false, see FieldDefinitionValidator). */
export function canAffectOptimization(fieldType: string): boolean {
  return compatibleConstraintFamilies(fieldType).some((family) => family !== "NONE");
}

export function fieldTypeLabel(fieldType: string): string {
  return (sv.fieldTypes as Record<string, string>)[fieldType] ?? fieldType;
}

export function constraintFamilyLabel(constraintFamily: string): string {
  return (sv.constraintFamilies as Record<string, string>)[constraintFamily] ?? constraintFamily;
}

export function hardOrSoftLabel(hardOrSoft: string | null | undefined): string {
  if (!hardOrSoft) {
    return "—";
  }
  return (sv.hardOrSoft as Record<string, string>)[hardOrSoft] ?? hardOrSoft;
}
