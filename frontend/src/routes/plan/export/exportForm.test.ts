import { describe, expect, it } from "vitest";
import { isGroupedLayoutDisabled, normalizeLayoutForFormat, showCommentsWarning } from "./exportForm";

describe("isGroupedLayoutDisabled", () => {
  it("disables grouped layout for csv (backend 400s layout=grouped&format=csv)", () => {
    expect(isGroupedLayoutDisabled("csv")).toBe(true);
  });

  it("allows grouped layout for xlsx", () => {
    expect(isGroupedLayoutDisabled("xlsx")).toBe(false);
  });
});

describe("normalizeLayoutForFormat", () => {
  it("falls back grouped -> flat when switching to csv", () => {
    expect(normalizeLayoutForFormat("csv", "grouped")).toBe("flat");
  });

  it("leaves flat layout untouched when switching to csv", () => {
    expect(normalizeLayoutForFormat("csv", "flat")).toBe("flat");
  });

  it("leaves grouped layout untouched for xlsx", () => {
    expect(normalizeLayoutForFormat("xlsx", "grouped")).toBe("grouped");
  });

  it("leaves flat layout untouched for xlsx", () => {
    expect(normalizeLayoutForFormat("xlsx", "flat")).toBe("flat");
  });
});

describe("showCommentsWarning", () => {
  it("is hidden by default (includeComments=false)", () => {
    expect(showCommentsWarning(false)).toBe(false);
  });

  it("shows once the user opts in", () => {
    expect(showCommentsWarning(true)).toBe(true);
  });
});
