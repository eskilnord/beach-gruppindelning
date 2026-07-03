import { describe, expect, it } from "vitest";
import type { OptimizationRun } from "../../../api/types";
import { parseResultSummary } from "./runSummary";

function runWith(resultSummaryJson: string | null): OptimizationRun {
  return {
    id: "run-1",
    activityPlanId: "plan-1",
    status: "FINISHED",
    startedAt: "2026-07-02T10:00:00Z",
    resultSummaryJson: resultSummaryJson ?? undefined,
  } as OptimizationRun;
}

describe("parseResultSummary", () => {
  it("parses the v0.2.0 coach-less note when present", () => {
    const summary = parseResultSummary(
      runWith(
        '{"hard":0,"medium":0,"soft":-100,"feasible":true,"unassignedCount":0,"note":"Inga tränare registrerade — grupperna optimerades utan tränartilldelning"}',
      ),
    );
    expect(summary?.note).toBe("Inga tränare registrerade — grupperna optimerades utan tränartilldelning");
    expect(summary?.feasible).toBe(true);
  });

  it("returns note: null when the summary has no note field", () => {
    const summary = parseResultSummary(runWith('{"hard":0,"medium":0,"soft":-100,"feasible":true,"unassignedCount":0}'));
    expect(summary?.note).toBeNull();
  });

  it("returns null for a missing or malformed summary blob", () => {
    expect(parseResultSummary(runWith(null))).toBeNull();
    expect(parseResultSummary(runWith("not json"))).toBeNull();
  });

  // WI-C ("re-run doesn't feel like it re-runs" user feedback v0.4 #4).
  it("parses unchangedFromPrevious: true when present", () => {
    const summary = parseResultSummary(
      runWith('{"hard":0,"medium":0,"soft":-100,"feasible":true,"unassignedCount":0,"unchangedFromPrevious":true}'),
    );
    expect(summary?.unchangedFromPrevious).toBe(true);
  });

  it("defaults unchangedFromPrevious to false when absent", () => {
    const summary = parseResultSummary(runWith('{"hard":0,"medium":0,"soft":-100,"feasible":true,"unassignedCount":0}'));
    expect(summary?.unchangedFromPrevious).toBe(false);
  });
});
