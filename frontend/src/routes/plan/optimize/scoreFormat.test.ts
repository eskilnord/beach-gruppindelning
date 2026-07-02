import { describe, expect, it } from "vitest";
import { formatScoreLine, formatThousands } from "./scoreFormat";

describe("formatThousands", () => {
  it("groups by thousands with a plain space, no separator for values under 1000", () => {
    expect(formatThousands(0)).toBe("0");
    expect(formatThousands(940)).toBe("940");
    expect(formatThousands(1000)).toBe("1 000");
  });

  it("groups large numbers with a plain ASCII space (spec §19.9 worked example)", () => {
    expect(formatThousands(2344940)).toBe("2 344 940");
  });

  it("uses a plain ASCII hyphen for negative numbers, not the Unicode minus sign", () => {
    expect(formatThousands(-2344940)).toBe("-2 344 940");
    expect(formatThousands(-2344940).charCodeAt(0)).toBe(45); // '-' U+002D, not U+2212
  });

  it("truncates fractional input defensively (score fields are always integers on the wire)", () => {
    expect(formatThousands(1234.9)).toBe("1 234");
  });
});

describe("formatScoreLine", () => {
  it("matches the spec §19.9 worked example verbatim", () => {
    expect(formatScoreLine({ hard: 0, unassignedCount: 3, soft: -2344940 })).toBe(
      "0 hårda brott · 3 på kölista · -2 344 940 mjukt",
    );
  });

  it("shows the singular form for exactly one hard violation", () => {
    expect(formatScoreLine({ hard: -1, unassignedCount: 0, soft: 0 })).toBe(
      "1 hårt brott · 0 på kölista · 0 mjukt",
    );
  });

  it("shows the plural form for more than one hard violation, using the magnitude not the raw sign", () => {
    expect(formatScoreLine({ hard: -17, unassignedCount: 0, soft: -212100 })).toBe(
      "17 hårda brott · 0 på kölista · -212 100 mjukt",
    );
  });

  it("treats missing/null score fields as zero (a plan with no run history yet)", () => {
    expect(formatScoreLine({ hard: null, unassignedCount: null, soft: null })).toBe(
      "0 hårda brott · 0 på kölista · 0 mjukt",
    );
  });

  it("renders a positive soft score without a leading sign", () => {
    expect(formatScoreLine({ hard: 0, unassignedCount: 0, soft: 500 })).toBe(
      "0 hårda brott · 0 på kölista · 500 mjukt",
    );
  });
});
