import { describe, expect, it } from "vitest";
import { sv } from "../i18n/sv";
import {
  effectiveGroupSizeDefaults,
  planDefaultsFromPlan,
  planDefaultsToCreateRequest,
  planDefaultsToPatchRequest,
  planDefaultsValidation,
  type PlanDefaultsFormValues,
} from "./planDefaults";

describe("effectiveGroupSizeDefaults", () => {
  it("falls back to 10/8/12 when nothing is set (mirrors GroupGenerator.effectiveSizes)", () => {
    expect(effectiveGroupSizeDefaults(null, null, null)).toEqual({ target: 10, min: 8, max: 12 });
    expect(effectiveGroupSizeDefaults(undefined, undefined, undefined)).toEqual({ target: 10, min: 8, max: 12 });
  });

  it("derives min/max from a custom target when only the target is set", () => {
    expect(effectiveGroupSizeDefaults(5, null, null)).toEqual({ target: 5, min: 3, max: 7 });
  });

  it("never lets the derived min go below 1", () => {
    expect(effectiveGroupSizeDefaults(2, null, null)).toEqual({ target: 2, min: 1, max: 4 });
  });

  it("uses explicit min/max as-is when all three are set", () => {
    expect(effectiveGroupSizeDefaults(10, 8, 12)).toEqual({ target: 10, min: 8, max: 12 });
  });
});

describe("planDefaultsFromPlan / request mappers", () => {
  it("round-trips a plan's set defaults through the form-values shape", () => {
    const values = planDefaultsFromPlan({
      defaultGroupTargetSize: 10,
      defaultGroupMinSize: 8,
      defaultGroupMaxSize: 12,
      defaultLevelMin: 300,
    });
    expect(values).toEqual({
      defaultGroupTargetSize: 10,
      defaultGroupMinSize: 8,
      defaultGroupMaxSize: 12,
      defaultLevelMin: 300,
    });
    expect(planDefaultsToCreateRequest(values)).toEqual({
      defaultGroupTargetSize: 10,
      defaultGroupMinSize: 8,
      defaultGroupMaxSize: 12,
      defaultLevelMin: 300,
    });
    expect(planDefaultsToPatchRequest(values)).toEqual({
      defaultGroupTargetSize: 10,
      defaultGroupMinSize: 8,
      defaultGroupMaxSize: 12,
      defaultLevelMin: 300,
    });
  });

  it("CREATE mapping omits unset fields (undefined - dropped by JSON.stringify)", () => {
    const values = planDefaultsFromPlan({});
    expect(values).toEqual({
      defaultGroupTargetSize: "",
      defaultGroupMinSize: "",
      defaultGroupMaxSize: "",
      defaultLevelMin: "",
    });
    expect(planDefaultsToCreateRequest(values)).toEqual({
      defaultGroupTargetSize: undefined,
      defaultGroupMinSize: undefined,
      defaultGroupMaxSize: undefined,
      defaultLevelMin: undefined,
    });
  });

  it("PATCH mapping sends unset fields as EXPLICIT null so a saved default can be cleared", () => {
    // v0.3.0 review fix (Finding 1): undefined is dropped by JSON.stringify, which made clearing
    // impossible - the backend's three-state PATCH needs a literal null in the body.
    const values: PlanDefaultsFormValues = {
      defaultGroupTargetSize: 10,
      defaultGroupMinSize: "",
      defaultGroupMaxSize: "",
      defaultLevelMin: "",
    };
    const body = planDefaultsToPatchRequest(values);
    expect(body).toEqual({
      defaultGroupTargetSize: 10,
      defaultGroupMinSize: null,
      defaultGroupMaxSize: null,
      defaultLevelMin: null,
    });
    // The nulls must survive serialization - that is the whole point of the fix.
    expect(JSON.parse(JSON.stringify(body))).toEqual(body);
  });
});

describe("planDefaultsValidation (effective post-fallback triple, v0.3.0 review fix)", () => {
  const values = (partial: Partial<PlanDefaultsFormValues>): PlanDefaultsFormValues => ({
    defaultGroupTargetSize: "",
    defaultGroupMinSize: "",
    defaultGroupMaxSize: "",
    defaultLevelMin: "",
    ...partial,
  });

  it("accepts a fully consistent explicit triple and the all-blank form", () => {
    const ok = values({ defaultGroupTargetSize: 10, defaultGroupMinSize: 8, defaultGroupMaxSize: 12 });
    expect(planDefaultsValidation.defaultGroupMinSize(8, ok)).toBeNull();
    expect(planDefaultsValidation.defaultGroupTargetSize(10, ok)).toBeNull();
    expect(planDefaultsValidation.defaultGroupMaxSize(12, ok)).toBeNull();

    const blank = values({});
    expect(planDefaultsValidation.defaultGroupMinSize("", blank)).toBeNull();
    expect(planDefaultsValidation.defaultGroupTargetSize("", blank)).toBeNull();
    expect(planDefaultsValidation.defaultGroupMaxSize("", blank)).toBeNull();
  });

  it("flags min set above an explicit target, with the effective values in the message", () => {
    const v = values({ defaultGroupMinSize: 9, defaultGroupTargetSize: 5 });
    // Effective triple: min 9, target 5, max 7 (max derived from target).
    expect(planDefaultsValidation.defaultGroupMinSize(9, v)).toBe(sv.planDefaults.effectiveSizeError(9, 5, 7));
    expect(planDefaultsValidation.defaultGroupTargetSize(5, v)).toBe(sv.planDefaults.effectiveSizeError(9, 5, 7));
  });

  it("flags min=20 alone against the fallback target 10 / max 12 (Finding 2)", () => {
    const v = values({ defaultGroupMinSize: 20 });
    expect(planDefaultsValidation.defaultGroupMinSize(20, v)).toBe(sv.planDefaults.effectiveSizeError(20, 10, 12));
    // Blank fields never carry the error themselves.
    expect(planDefaultsValidation.defaultGroupTargetSize("", v)).toBeNull();
    expect(planDefaultsValidation.defaultGroupMaxSize("", v)).toBeNull();
  });

  it("flags min=8/max=5 with target blank via the fallback target 10 (Finding 3)", () => {
    const v = values({ defaultGroupMinSize: 8, defaultGroupMaxSize: 5 });
    // Effective triple: min 8, target 10 (fallback), max 5.
    expect(planDefaultsValidation.defaultGroupMaxSize(5, v)).toBe(sv.planDefaults.effectiveSizeError(8, 10, 5));
    expect(planDefaultsValidation.defaultGroupMinSize(8, v)).toBe(sv.planDefaults.effectiveSizeError(8, 10, 5));
  });

  it("flags max=5 alone against the fallback target 10", () => {
    const v = values({ defaultGroupMaxSize: 5 });
    expect(planDefaultsValidation.defaultGroupMaxSize(5, v)).toBe(sv.planDefaults.effectiveSizeError(8, 10, 5));
  });

  it("flags a min-nivå outside the 0-1000 range, allows the boundaries and empty", () => {
    expect(planDefaultsValidation.defaultLevelMin(-1)).toBe(sv.planDefaults.levelMinRangeError);
    expect(planDefaultsValidation.defaultLevelMin(1001)).toBe(sv.planDefaults.levelMinRangeError);
    expect(planDefaultsValidation.defaultLevelMin(0)).toBeNull();
    expect(planDefaultsValidation.defaultLevelMin(1000)).toBeNull();
    expect(planDefaultsValidation.defaultLevelMin("")).toBeNull();
  });
});
