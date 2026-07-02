import { describe, expect, it } from "vitest";
import { sv } from "../../../../i18n/sv";
import { originBadge, verdictBadge, weightBadgeLabel } from "./badges";

describe("verdictBadge", () => {
  it("maps WOULD_BREAK_HARD to red with the 'skulle bryta hård regel' label", () => {
    const badge = verdictBadge("WOULD_BREAK_HARD");
    expect(badge.color).toBe("red");
    expect(badge.label).toBe(sv.results.explain.verdict.wouldBreakHard);
  });

  it("maps BETTER to green", () => {
    const badge = verdictBadge("BETTER");
    expect(badge.color).toBe("green");
    expect(badge.label).toBe(sv.results.explain.verdict.better);
  });

  it("maps NEUTRAL (exactly-zero score delta, M7-review backend extension) to gray 'Ingen påverkan'", () => {
    const badge = verdictBadge("NEUTRAL");
    expect(badge.color).toBe("gray");
    expect(badge.label).toBe(sv.results.explain.verdict.neutral);
    expect(badge.label).toBe("Ingen påverkan");
  });

  it("maps WORSE to a neutral gray", () => {
    const badge = verdictBadge("WORSE");
    expect(badge.color).toBe("gray");
    expect(badge.label).toBe(sv.results.explain.verdict.worse);
  });

  it("falls back to the raw string in gray for an unrecognized verdict", () => {
    const badge = verdictBadge("SOMETHING_NEW");
    expect(badge.color).toBe("gray");
    expect(badge.label).toBe("SOMETHING_NEW");
  });
});

describe("originBadge", () => {
  it("maps FRIEND_WISH to Kompisönskemål", () => {
    const badge = originBadge("FRIEND_WISH");
    expect(badge.label).toBe(sv.results.explain.origin.friendWish);
    expect(badge.color).toBe("grape");
  });

  it("maps FRIEND_VIA_COACH (v0.3.0 WI-5) to Kompis knuten via tränare", () => {
    const badge = originBadge("FRIEND_VIA_COACH");
    expect(badge.label).toBe(sv.results.explain.origin.friendViaCoach);
    expect(badge.color).toBe("violet");
  });

  it("maps COACH_WISH to Tränarönskemål", () => {
    const badge = originBadge("COACH_WISH");
    expect(badge.label).toBe(sv.results.explain.origin.coachWish);
    expect(badge.color).toBe("indigo");
  });

  it("maps PREVIOUS_GROUP to Tidigare grupp", () => {
    const badge = originBadge("PREVIOUS_GROUP");
    expect(badge.label).toBe(sv.results.explain.origin.previousGroup);
    expect(badge.color).toBe("cyan");
  });

  it("maps TOP_SCORE to Näst bäst", () => {
    const badge = originBadge("TOP_SCORE");
    expect(badge.label).toBe(sv.results.explain.origin.topScore);
    expect(badge.color).toBe("teal");
  });

  it("falls back to the raw string in gray for an unrecognized origin", () => {
    const badge = originBadge("SOMETHING_NEW");
    expect(badge.color).toBe("gray");
    expect(badge.label).toBe("SOMETHING_NEW");
  });
});

describe("weightBadgeLabel", () => {
  it("renders 'soft <weight>' verbatim, matching kravspec §17.2's own worked example", () => {
    expect(weightBadgeLabel("SOFT", 80)).toBe("soft 80");
    expect(weightBadgeLabel("SOFT", 60)).toBe("soft 60");
  });

  it("renders 'medium <weight>' the same way", () => {
    expect(weightBadgeLabel("MEDIUM", 40)).toBe("medium 40");
  });

  it("renders bare 'hard' with no numeric weight (the magnitude isn't meaningful to show)", () => {
    expect(weightBadgeLabel("HARD", 1)).toBe("hard");
    expect(weightBadgeLabel("HARD", 999)).toBe("hard");
  });
});
