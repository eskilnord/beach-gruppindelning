import { describe, expect, it } from "vitest";
import { describeLevelConfidence } from "./levelConfidence";

describe("describeLevelConfidence", () => {
  it("maps null/undefined/zero to saknas", () => {
    expect(describeLevelConfidence(null).label).toBe("Saknas");
    expect(describeLevelConfidence(undefined).bucket).toBe("none");
    expect(describeLevelConfidence(0).bucket).toBe("none");
  });

  it("maps LevelService.CONFIDENCE_HIGH (1.0) to hög", () => {
    const badge = describeLevelConfidence(1.0);
    expect(badge.bucket).toBe("high");
    expect(badge.label).toBe("Hög");
  });

  it("maps LevelService.CONFIDENCE_MEDIUM (0.6) to medel", () => {
    const badge = describeLevelConfidence(0.6);
    expect(badge.bucket).toBe("medium");
    expect(badge.label).toBe("Medel");
  });

  it("maps LevelService.CONFIDENCE_LOW (0.3) to låg", () => {
    const badge = describeLevelConfidence(0.3);
    expect(badge.bucket).toBe("low");
    expect(badge.label).toBe("Låg");
  });

  it("uses threshold bands, not exact equality", () => {
    expect(describeLevelConfidence(0.95).bucket).toBe("high");
    expect(describeLevelConfidence(0.5).bucket).toBe("medium");
    expect(describeLevelConfidence(0.1).bucket).toBe("low");
  });
});
