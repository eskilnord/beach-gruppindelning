import { describe, expect, it } from "vitest";
import type { OptimizationRun } from "../../api/types";
import { hasUsableResult, latestUsableRun, runHasUsableResult } from "./runStatus";

function run(overrides: Partial<OptimizationRun>): OptimizationRun {
  return {
    id: "run-1",
    activityPlanId: "plan-1",
    status: "FINISHED",
    startedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  } as OptimizationRun;
}

const SUMMARY_JSON = JSON.stringify({ hard: 0, medium: 0, soft: -5, feasible: true, unassignedCount: 0 });

describe("runHasUsableResult", () => {
  it("FINISHED always counts, even without a resultSummaryJson", () => {
    expect(runHasUsableResult(run({ status: "FINISHED", resultSummaryJson: undefined }))).toBe(true);
    expect(runHasUsableResult(run({ status: "FINISHED", resultSummaryJson: SUMMARY_JSON }))).toBe(true);
  });

  it("CANCELLED with a parseable resultSummaryJson counts (best-so-far was persisted)", () => {
    expect(runHasUsableResult(run({ status: "CANCELLED", resultSummaryJson: SUMMARY_JSON }))).toBe(true);
  });

  it("CANCELLED with no resultSummaryJson does NOT count (cancelled before any solution existed)", () => {
    expect(runHasUsableResult(run({ status: "CANCELLED", resultSummaryJson: undefined }))).toBe(false);
  });

  it("CANCELLED with an unparseable resultSummaryJson does NOT count", () => {
    expect(runHasUsableResult(run({ status: "CANCELLED", resultSummaryJson: "not json" }))).toBe(false);
  });

  it("FAILED never counts", () => {
    expect(runHasUsableResult(run({ status: "FAILED", resultSummaryJson: SUMMARY_JSON }))).toBe(false);
  });

  it("a still-solving run never counts", () => {
    expect(runHasUsableResult(run({ status: "SOLVING_ACTIVE", resultSummaryJson: undefined }))).toBe(false);
  });
});

describe("hasUsableResult", () => {
  it("false for undefined/empty run lists", () => {
    expect(hasUsableResult(undefined)).toBe(false);
    expect(hasUsableResult([])).toBe(false);
  });

  it("true when ANY run in the history (not just the latest) has a usable result", () => {
    const runs = [run({ id: "r2", status: "FAILED" }), run({ id: "r1", status: "FINISHED" })];
    expect(hasUsableResult(runs)).toBe(true);
  });

  it("false when every run is unusable (still solving, failed, or an empty cancel)", () => {
    const runs = [
      run({ id: "r3", status: "SOLVING_ACTIVE" }),
      run({ id: "r2", status: "FAILED" }),
      run({ id: "r1", status: "CANCELLED", resultSummaryJson: undefined }),
    ];
    expect(hasUsableResult(runs)).toBe(false);
  });
});

describe("latestUsableRun", () => {
  it("undefined for undefined/empty run lists", () => {
    expect(latestUsableRun(undefined)).toBeUndefined();
    expect(latestUsableRun([])).toBeUndefined();
  });

  it("returns the first (most-recent) usable run, skipping a newer unusable one", () => {
    const finished = run({ id: "r1", status: "FINISHED" });
    const runs = [run({ id: "r2", status: "SOLVING_ACTIVE" }), finished];
    expect(latestUsableRun(runs)).toBe(finished);
  });

  it("returns undefined when no run in the history is usable", () => {
    const runs = [run({ id: "r1", status: "FAILED" })];
    expect(latestUsableRun(runs)).toBeUndefined();
  });
});
