import { describe, expect, it } from "vitest";
import { computeLevelStats } from "./groupMetrics";

describe("computeLevelStats", () => {
  it("returns null mean/spread for an empty group", () => {
    expect(computeLevelStats([])).toEqual({ mean: null, spread: null });
  });

  it("ignores null/undefined levels (a member whose estimatedLevel hasn't been computed yet)", () => {
    expect(computeLevelStats([null, undefined, 500])).toEqual({ mean: 500, spread: 0 });
  });

  it("computes mean and sum-of-absolute-deviations spread, rounded to whole points", () => {
    // mean = (400+500+600)/3 = 500; SAD = |400-500|+|500-500|+|600-500| = 200.
    expect(computeLevelStats([400, 500, 600])).toEqual({ mean: 500, spread: 200 });
  });

  it("rounds a non-integer mean", () => {
    // mean = (500+501)/2 = 500.5 -> rounds to 501 (banker's-unaware Math.round, display-only).
    const stats = computeLevelStats([500, 501]);
    expect(stats.mean).toBe(501);
  });
});
