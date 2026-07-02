/**
 * "Standardvärden för grupper" (v0.3.0 user feedback - target/max/min-nivå defaults were already
 * stored on ActivityPlan but had no UI): shared form-values shape, validation, and the
 * request-mappings used by CreatePlanModal (omit unset) and EditPlanModal (explicit null to clear),
 * plus the fallback-size formula mirrored from the backend so OptimizePanel's "Standard: ..." hint
 * always agrees with what a "Generera grupper" click will actually use.
 */
import { sv } from "../i18n/sv";

/** Mirrors GroupGenerator.FALLBACK_TARGET_SIZE (backend/.../solver/assemble/GroupGenerator.java). */
export const FALLBACK_GROUP_TARGET_SIZE = 10;

export interface PlanDefaultsFormValues {
  defaultGroupTargetSize: number | "";
  defaultGroupMinSize: number | "";
  defaultGroupMaxSize: number | "";
  defaultLevelMin: number | "";
}

export const PLAN_DEFAULTS_EMPTY_VALUES: PlanDefaultsFormValues = {
  defaultGroupTargetSize: "",
  defaultGroupMinSize: "",
  defaultGroupMaxSize: "",
  defaultLevelMin: "",
};

const num = (value: number | ""): number | null => (value === "" ? null : value);

/** The effective post-fallback size triple for the current form values - what group generation
 *  would actually use if the form were saved as-is. */
function effectiveOf(values: PlanDefaultsFormValues): { target: number; min: number; max: number } {
  return effectiveGroupSizeDefaults(
    num(values.defaultGroupTargetSize),
    num(values.defaultGroupMinSize),
    num(values.defaultGroupMaxSize),
  );
}

/**
 * Mantine `useForm` per-field validators, mirroring the backend's
 * `ActivityPlanController#requireValidDefaults` exactly: the EFFECTIVE post-fallback triple must
 * satisfy min <= target <= max (v0.3.0 review fix - a partially-set value like "only min=20" is
 * contradictory against the fallback target 10 / max 12 and must be caught inline, not as a
 * generic 400 toast; the same effective check also covers explicit min > max with target blank),
 * and min-nivå must be on the 0-1000 level scale. Each size field only reports the violated
 * inequalities it participates in, and only when it is explicitly set, so the error lands under
 * the field(s) the user actually typed in.
 */
export const planDefaultsValidation = {
  defaultGroupMinSize: (value: number | "", values: PlanDefaultsFormValues) => {
    if (value === "") {
      return null;
    }
    const eff = effectiveOf(values);
    return eff.min > eff.target || eff.min > eff.max
      ? sv.planDefaults.effectiveSizeError(eff.min, eff.target, eff.max)
      : null;
  },
  defaultGroupTargetSize: (value: number | "", values: PlanDefaultsFormValues) => {
    if (value === "") {
      return null;
    }
    const eff = effectiveOf(values);
    return eff.min > eff.target || eff.target > eff.max
      ? sv.planDefaults.effectiveSizeError(eff.min, eff.target, eff.max)
      : null;
  },
  defaultGroupMaxSize: (value: number | "", values: PlanDefaultsFormValues) => {
    if (value === "") {
      return null;
    }
    const eff = effectiveOf(values);
    return eff.target > eff.max || eff.min > eff.max
      ? sv.planDefaults.effectiveSizeError(eff.min, eff.target, eff.max)
      : null;
  },
  defaultLevelMin: (value: number | "") =>
    value !== "" && (value < 0 || value > 1000) ? sv.planDefaults.levelMinRangeError : null,
};

export function planDefaultsFromPlan(plan: {
  defaultGroupTargetSize?: number;
  defaultGroupMinSize?: number;
  defaultGroupMaxSize?: number;
  defaultLevelMin?: number;
}): PlanDefaultsFormValues {
  return {
    defaultGroupTargetSize: plan.defaultGroupTargetSize ?? "",
    defaultGroupMinSize: plan.defaultGroupMinSize ?? "",
    defaultGroupMaxSize: plan.defaultGroupMaxSize ?? "",
    defaultLevelMin: plan.defaultLevelMin ?? "",
  };
}

/** CREATE mapping: an empty input is OMITTED (undefined - JSON.stringify drops it), meaning "not
 *  configured" on the new plan. */
export function planDefaultsToCreateRequest(values: PlanDefaultsFormValues) {
  return {
    defaultGroupTargetSize: values.defaultGroupTargetSize === "" ? undefined : Number(values.defaultGroupTargetSize),
    defaultGroupMinSize: values.defaultGroupMinSize === "" ? undefined : Number(values.defaultGroupMinSize),
    defaultGroupMaxSize: values.defaultGroupMaxSize === "" ? undefined : Number(values.defaultGroupMaxSize),
    defaultLevelMin: values.defaultLevelMin === "" ? undefined : Number(values.defaultLevelMin),
  };
}

/** PATCH mapping (v0.3.0 review fix): an empty input is sent as an EXPLICIT JSON null so an
 *  already-saved default can be CLEARED - the backend's UpdateActivityPlanRequest is three-state
 *  per field (absent = keep, null = clear, value = set) and `undefined` would be dropped by
 *  JSON.stringify, silently keeping the old value forever. The edit form always renders the
 *  plan's current defaults, so "empty on submit" reliably means "the user wants this unset". */
export function planDefaultsToPatchRequest(values: PlanDefaultsFormValues) {
  return {
    defaultGroupTargetSize: num(values.defaultGroupTargetSize),
    defaultGroupMinSize: num(values.defaultGroupMinSize),
    defaultGroupMaxSize: num(values.defaultGroupMaxSize),
    defaultLevelMin: num(values.defaultLevelMin),
  };
}

/** Mirrors GroupGenerator#effectiveSizes exactly (target ?? 10, max ?? target+2, min ?? max(1,
 *  target-2)) - used by the modals' NumberInput placeholders, the validators above, and
 *  OptimizePanel's "Standard: ..." hint so all of them always describe what a "Generera grupper"
 *  click will actually do. */
export function effectiveGroupSizeDefaults(
  target: number | null | undefined,
  min: number | null | undefined,
  max: number | null | undefined,
): { target: number; min: number; max: number } {
  const effectiveTarget = target ?? FALLBACK_GROUP_TARGET_SIZE;
  const effectiveMax = max ?? effectiveTarget + 2;
  const effectiveMin = min ?? Math.max(1, effectiveTarget - 2);
  return { target: effectiveTarget, min: effectiveMin, max: effectiveMax };
}
