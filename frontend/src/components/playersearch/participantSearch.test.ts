import { describe, expect, it } from "vitest";
import { matchesSearchQuery, normalizeForSearch } from "./participantSearch";

describe("normalizeForSearch", () => {
  it("strips Swedish diacritics and lowercases", () => {
    expect(normalizeForSearch("Åsa Örnberg")).toBe("asa ornberg");
  });

  it("trims surrounding whitespace", () => {
    expect(normalizeForSearch("  Björn  ")).toBe("bjorn");
  });
});

describe("matchesSearchQuery", () => {
  it("matches diacritics-insensitively in either direction", () => {
    expect(matchesSearchQuery("Åsa Örnberg", "orn")).toBe(true);
    expect(matchesSearchQuery("Åsa Örnberg", "Örn")).toBe(true);
    expect(matchesSearchQuery("Åsa Örnberg", "asa")).toBe(true);
  });

  it("is case-insensitive", () => {
    expect(matchesSearchQuery("Björn Ekvall", "BJORN")).toBe(true);
  });

  it("matches on any substring, not just a name prefix", () => {
    expect(matchesSearchQuery("Björn Ekvall", "kvall")).toBe(true);
  });

  it("returns false for a non-matching query", () => {
    expect(matchesSearchQuery("Björn Ekvall", "zzz")).toBe(false);
  });

  it("matches everything for an empty or blank query", () => {
    expect(matchesSearchQuery("Anyone", "")).toBe(true);
    expect(matchesSearchQuery("Anyone", "   ")).toBe(true);
  });
});
