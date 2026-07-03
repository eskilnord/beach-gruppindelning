import { describe, expect, it } from "vitest";
import { sv } from "../../../i18n/sv";
import { computeGroupQuality, formatBandBoundary, severityColor, type GroupQualityInput } from "./groupQuality";

/** A group with nothing configured beyond its actual size/coach count - every optional bound is
 *  `null`, so no signal should ever be emitted for the fields a caller overrides per test. */
const BASE: GroupQualityInput = {
  size: 8,
  minSize: null,
  targetSize: null,
  maxSize: null,
  requiredCoachCount: null,
  coachCount: 0,
  levelMean: null,
  levelSpread: null,
  levelMin: null,
  levelMax: null,
};

describe("formatBandBoundary", () => {
  it("formats a whole number with a trailing ,0 (backend levelMeanSv convention)", () => {
    expect(formatBandBoundary(6)).toBe("6,0");
  });

  it("formats a fractional value with a Swedish decimal comma, rounded to one decimal", () => {
    expect(formatBandBoundary(3.5)).toBe("3,5");
    expect(formatBandBoundary(3.449)).toBe("3,4");
  });
});

describe("severityColor", () => {
  it("maps bad/warn/ok (and the matching good/warn/bad status) to red/yellow/teal", () => {
    expect(severityColor("bad")).toBe("red");
    expect(severityColor("warn")).toBe("yellow");
    expect(severityColor("ok")).toBe("teal");
    expect(severityColor("good")).toBe("teal");
  });
});

describe("computeGroupQuality", () => {
  it("is null-tolerant: no target/band/required-coach data at all yields status good and zero signals", () => {
    expect(computeGroupQuality(BASE)).toEqual({ status: "good", signals: [] });
  });

  it("does not emit a coach signal at all when requiredCoachCount is zero or null (coach-optional plan)", () => {
    expect(computeGroupQuality({ ...BASE, requiredCoachCount: 0, coachCount: 0 }).signals).toHaveLength(0);
    expect(computeGroupQuality({ ...BASE, requiredCoachCount: null, coachCount: 0 }).signals).toHaveLength(0);
  });

  it("flags a bad coachMissing signal when a coach is required but none is assigned", () => {
    const quality = computeGroupQuality({ ...BASE, requiredCoachCount: 1, coachCount: 0 });
    expect(quality.status).toBe("bad");
    expect(quality.signals).toEqual([{ key: "coachMissing", severity: "bad", textSv: sv.results.quality.signals.coachMissing }]);
  });

  it("flags a bad coachBelowRequired signal when some but not all required coaches are assigned (review fix 4)", () => {
    const quality = computeGroupQuality({ ...BASE, requiredCoachCount: 2, coachCount: 1 });
    expect(quality.status).toBe("bad");
    expect(quality.signals).toEqual([
      { key: "coachBelowRequired", severity: "bad", textSv: "1 av 2 tränare tilldelade" },
    ]);
  });

  it("flags an ok coachInPlace signal once the required coach count is met", () => {
    const quality = computeGroupQuality({ ...BASE, requiredCoachCount: 1, coachCount: 1 });
    expect(quality.status).toBe("good");
    expect(quality.signals).toEqual([{ key: "coachInPlace", severity: "ok", textSv: sv.results.quality.signals.coachInPlace }]);
    // Meeting a multi-coach requirement exactly (or exceeding it) is equally ok.
    expect(computeGroupQuality({ ...BASE, requiredCoachCount: 2, coachCount: 2 }).signals[0].key).toBe("coachInPlace");
    expect(computeGroupQuality({ ...BASE, requiredCoachCount: 2, coachCount: 3 }).signals[0].key).toBe("coachInPlace");
  });

  it("flags a bad sizeBelowMin signal when size is under the configured minimum", () => {
    const quality = computeGroupQuality({ ...BASE, size: 4, minSize: 6 });
    expect(quality.status).toBe("bad");
    expect(quality.signals).toEqual([
      { key: "sizeBelowMin", severity: "bad", textSv: sv.results.quality.signals.sizeBelowMin(4, 6) },
    ]);
  });

  it("flags a bad sizeAboveMax signal when size is over the configured maximum", () => {
    const quality = computeGroupQuality({ ...BASE, size: 14, maxSize: 12 });
    expect(quality.status).toBe("bad");
    expect(quality.signals).toEqual([
      { key: "sizeAboveMax", severity: "bad", textSv: sv.results.quality.signals.sizeAboveMax(14, 12) },
    ]);
  });

  it("prefers the sizeBelowMin bad signal over a target-size warn when both would apply", () => {
    const quality = computeGroupQuality({ ...BASE, size: 4, minSize: 6, targetSize: 10 });
    expect(quality.signals.map((s) => s.key)).toEqual(["sizeBelowMin"]);
  });

  it("flags a warn sizeBelowTarget signal when size is within min/max but under target", () => {
    const quality = computeGroupQuality({ ...BASE, size: 8, minSize: 6, targetSize: 10, maxSize: 12 });
    expect(quality.status).toBe("warn");
    expect(quality.signals).toEqual([
      { key: "sizeBelowTarget", severity: "warn", textSv: sv.results.quality.signals.sizeBelowTarget(8, 10) },
    ]);
  });

  it("flags a warn sizeAboveTarget signal when size is within min/max but over target", () => {
    const quality = computeGroupQuality({ ...BASE, size: 11, minSize: 6, targetSize: 10, maxSize: 12 });
    expect(quality.status).toBe("warn");
    expect(quality.signals).toEqual([
      { key: "sizeAboveTarget", severity: "warn", textSv: sv.results.quality.signals.sizeAboveTarget(11, 10) },
    ]);
  });

  it("flags an ok sizeAtTarget signal when size exactly matches the target", () => {
    const quality = computeGroupQuality({ ...BASE, size: 10, minSize: 6, targetSize: 10, maxSize: 12 });
    expect(quality.status).toBe("good");
    expect(quality.signals).toEqual([{ key: "sizeAtTarget", severity: "ok", textSv: sv.results.quality.signals.sizeAtTarget }]);
  });

  it("emits no size signal at all when no min/target/max is configured", () => {
    expect(computeGroupQuality({ ...BASE, size: 8 }).signals).toHaveLength(0);
  });

  it("flags a warn levelOutsideBand signal when the mean sits above the band", () => {
    const quality = computeGroupQuality({ ...BASE, levelMean: 8, levelMin: 3, levelMax: 6 });
    expect(quality.status).toBe("warn");
    expect(quality.signals).toEqual([
      {
        key: "levelOutsideBand",
        severity: "warn",
        textSv: sv.results.quality.signals.levelOutsideBand(8, "3,0", "6,0"),
      },
    ]);
  });

  it("flags a warn levelOutsideBand signal when the mean sits below the band", () => {
    const quality = computeGroupQuality({ ...BASE, levelMean: 2, levelMin: 3.5, levelMax: 6 });
    expect(quality.status).toBe("warn");
    expect(quality.signals).toEqual([
      {
        key: "levelOutsideBand",
        severity: "warn",
        textSv: sv.results.quality.signals.levelOutsideBand(2, "3,5", "6,0"),
      },
    ]);
  });

  it("flags an ok levelInsideBand signal when the mean sits inside the band, including at its exact edges", () => {
    expect(computeGroupQuality({ ...BASE, levelMean: 5, levelMin: 3, levelMax: 6 }).signals).toEqual([
      { key: "levelInsideBand", severity: "ok", textSv: sv.results.quality.signals.levelInsideBand },
    ]);
    expect(computeGroupQuality({ ...BASE, levelMean: 3, levelMin: 3, levelMax: 6 }).signals[0].severity).toBe("ok");
    expect(computeGroupQuality({ ...BASE, levelMean: 6, levelMin: 3, levelMax: 6 }).signals[0].severity).toBe("ok");
  });

  it("emits no level signal when the group has no configured band", () => {
    expect(computeGroupQuality({ ...BASE, levelMean: 5, levelMin: null, levelMax: null }).signals).toHaveLength(0);
  });

  it("emits no level signal when the band is configured but the group has no level data yet", () => {
    expect(computeGroupQuality({ ...BASE, levelMean: null, levelMin: 3, levelMax: 6 }).signals).toHaveLength(0);
  });

  it("does not derive any signal from levelSpread alone - only the mean-vs-band check applies", () => {
    // A huge spread with no band configured must not invent a warning of its own.
    expect(computeGroupQuality({ ...BASE, levelMean: 5, levelSpread: 400, levelMin: null, levelMax: null }).signals).toHaveLength(0);
  });

  it("flags a warn topPenalty signal only when penaltySum is present and positive", () => {
    expect(computeGroupQuality({ ...BASE, penaltySum: 120 }).signals).toEqual([
      { key: "topPenalty", severity: "warn", textSv: sv.results.quality.signals.topPenalty },
    ]);
    expect(computeGroupQuality({ ...BASE, penaltySum: 0 }).signals).toHaveLength(0);
    expect(computeGroupQuality({ ...BASE, penaltySum: undefined }).signals).toHaveLength(0);
    expect(computeGroupQuality({ ...BASE, penaltySum: null }).signals).toHaveLength(0);
  });

  it("status is bad when any bad signal is present, even alongside warn/ok ones", () => {
    const quality = computeGroupQuality({
      ...BASE,
      size: 4,
      minSize: 6,
      requiredCoachCount: 1,
      coachCount: 1,
      levelMean: 5,
      levelMin: 3,
      levelMax: 6,
    });
    expect(quality.status).toBe("bad");
    expect(quality.signals.map((s) => s.severity).sort()).toEqual(["bad", "ok", "ok"]);
  });

  it("status is warn when the worst signal present is a warn (no bad signal)", () => {
    const quality = computeGroupQuality({
      ...BASE,
      size: 8,
      minSize: 6,
      targetSize: 10,
      maxSize: 12,
      requiredCoachCount: 1,
      coachCount: 1,
    });
    expect(quality.status).toBe("warn");
  });

  it("status is good when every emitted signal is ok", () => {
    const quality = computeGroupQuality({
      ...BASE,
      size: 10,
      minSize: 6,
      targetSize: 10,
      maxSize: 12,
      requiredCoachCount: 1,
      coachCount: 1,
      levelMean: 5,
      levelMin: 3,
      levelMax: 6,
    });
    expect(quality.status).toBe("good");
    expect(quality.signals.every((s) => s.severity === "ok")).toBe(true);
  });
});
