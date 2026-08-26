import { describe, expect, it } from "vitest";
import { describeExportResult, isGroupedLayoutDisabled, normalizeLayoutForFormat, showCommentsWarning } from "./exportForm";

const TEXTS = {
  downloaded: "downloaded-text",
  savedGeneric: "saved-generic-text",
  savedWithFilename: (filename: string) => `saved-with:${filename}`,
  cancelled: "cancelled-text",
};

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

// v0.6.0 audit batch D (D7): saveFile's richer SaveFileResult (platform.ts) -> the notification to
// show, shared by ExportPanel.tsx's two export buttons and SimpleSaveExportCard.tsx's one.
describe("describeExportResult", () => {
  it("shows the 'downloaded' text for the browser branch", () => {
    expect(describeExportResult({ saved: true, isTauriSave: false, filename: "x.xlsx" }, TEXTS)).toEqual({
      color: "green",
      message: "downloaded-text",
    });
  });

  it("shows the filename-specific text for a successful Tauri save with a known filename", () => {
    expect(describeExportResult({ saved: true, isTauriSave: true, filename: "grupper.xlsx" }, TEXTS)).toEqual({
      color: "green",
      message: "saved-with:grupper.xlsx",
    });
  });

  it("falls back to the generic saved text for a successful Tauri save with no filename", () => {
    expect(describeExportResult({ saved: true, isTauriSave: true }, TEXTS)).toEqual({
      color: "green",
      message: "saved-generic-text",
    });
  });

  it("shows the cancelled text when the Tauri save dialog was cancelled", () => {
    expect(describeExportResult({ saved: false, isTauriSave: true }, TEXTS)).toEqual({
      color: "gray",
      message: "cancelled-text",
    });
  });
});
