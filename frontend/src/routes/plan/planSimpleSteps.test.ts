import { describe, expect, it } from "vitest";
import { sv } from "../../i18n/sv";
import { completionFor, resolveSimpleStepIndex, SIMPLE_STEPS } from "./planSimpleSteps";

describe("resolveSimpleStepIndex", () => {
  it("resolves every step path to its index in SIMPLE_STEPS", () => {
    SIMPLE_STEPS.forEach((step, index) => {
      expect(resolveSimpleStepIndex(`/plans/plan-1/${step.path}`)).toBe(index);
    });
  });

  it("returns -1 for a gated tab reached via deep link (falt/tranare/kapacitet/planer)", () => {
    expect(resolveSimpleStepIndex("/plans/plan-1/falt")).toBe(-1);
    expect(resolveSimpleStepIndex("/plans/plan-1/tranare")).toBe(-1);
    expect(resolveSimpleStepIndex("/plans/plan-1/kapacitet")).toBe(-1);
    expect(resolveSimpleStepIndex("/plans/plan-1/planer")).toBe(-1);
  });

  // v0.6.0 F2 (M-S2): falt is reachable only via a deep link while in SIMPLE mode - it is
  // deliberately not one of the six SIMPLE_STEPS at all (unlike the -1 cases above, which ARE step
  // paths, just gated ones this milestone). Moved here from e2e/ui-mode-toggle.spec.ts (F2 review fix
  // FIX 6) - a pure data-table assertion belongs in this unit spec, not a browser test.
  it("falt is not among SIMPLE_STEPS' paths", () => {
    expect(SIMPLE_STEPS.some((step) => step.path === "falt")).toBe(false);
  });

  it("returns -1 for an unrelated/unknown route", () => {
    expect(resolveSimpleStepIndex("/plans/plan-1/import")).toBe(-1);
    expect(resolveSimpleStepIndex("/seasons/season-1")).toBe(-1);
    expect(resolveSimpleStepIndex("/")).toBe(-1);
  });

  // v0.6.0 F2 review fix (FIX 5): both reviewers flagged these two edges.
  it("strips a trailing slash before matching", () => {
    expect(resolveSimpleStepIndex("/plans/plan-1/deltagare/")).toBe(0);
    expect(resolveSimpleStepIndex("/plans/plan-1/export/")).toBe(5);
    // A bare trailing slash on an unrelated route still doesn't match anything.
    expect(resolveSimpleStepIndex("/plans/plan-1/falt/")).toBe(-1);
  });

  it("resolves the bare plan root (before router.tsx's index-route redirect fires) to index 0, matching PlanLayout's ADVANCED fallback-to-first-tab", () => {
    expect(resolveSimpleStepIndex("/plans/plan-1")).toBe(0);
    expect(resolveSimpleStepIndex("/plans/plan-1/")).toBe(0);
  });
});

describe("completionFor", () => {
  const EMPTY = {
    participantsCount: undefined,
    timeSlotsCount: undefined,
    optimizationRunsCount: undefined,
  };

  it("leaves the three live-count steps un-checked with no description when nothing has loaded yet", () => {
    const result = completionFor(EMPTY);
    expect(result).toHaveLength(SIMPLE_STEPS.length);
    [0, 1, 3].forEach((index) => {
      expect(result[index].completed).toBeUndefined();
      expect(result[index].description).toBeUndefined();
    });
  });

  it("Deltagare: completed + live count once participants are loaded", () => {
    expect(completionFor({ ...EMPTY, participantsCount: 260 })[0]).toEqual({
      completed: true,
      description: "260 deltagare",
    });
  });

  it("Deltagare: loaded but empty is NOT completed, still shows the zero count", () => {
    expect(completionFor({ ...EMPTY, participantsCount: 0 })[0]).toEqual({
      completed: false,
      description: "0 deltagare",
    });
  });

  it("Tider (resurser step): singular/plural picked by count", () => {
    expect(completionFor({ ...EMPTY, timeSlotsCount: 1 })[1]).toEqual({
      completed: true,
      description: "1 tid",
    });
    expect(completionFor({ ...EMPTY, timeSlotsCount: 3 })[1]).toEqual({
      completed: true,
      description: "3 tider",
    });
  });

  // v0.6.0 F2 review fix (FIX 8): Prioriteringar/Resultat/Exportera never have a live count to derive
  // completion from, but they now DO get a static fallback description (sv.simple.stepDescriptions)
  // so every step in the stepper renders a description line - not just the three with a cheap signal.
  it("Prioriteringar: always un-checked, static fallback description (F3 placeholder route)", () => {
    const result = completionFor({ participantsCount: 260, timeSlotsCount: 3, optimizationRunsCount: 2 });
    expect(result[2]).toEqual({ completed: undefined, description: sv.simple.stepDescriptions.prioriteringar });
  });

  it("Optimera: completed + run count once runs are loaded", () => {
    expect(completionFor({ ...EMPTY, optimizationRunsCount: 1 })[3]).toEqual({
      completed: true,
      description: "1 körning",
    });
    expect(completionFor({ ...EMPTY, optimizationRunsCount: 2 })[3]).toEqual({
      completed: true,
      description: "2 körningar",
    });
    expect(completionFor({ ...EMPTY, optimizationRunsCount: 0 })[3]).toEqual({
      completed: false,
      description: "0 körningar",
    });
  });

  it("Resultat: always un-checked, static fallback description (no cheap distinct signal)", () => {
    const result = completionFor({ participantsCount: 260, timeSlotsCount: 3, optimizationRunsCount: 2 });
    expect(result[4]).toEqual({ completed: undefined, description: sv.simple.stepDescriptions.resultat });
  });

  // v0.6.0 F2 review fix (FIX 3): used to derive completion/description from a saved-plan count -
  // dropped, since saving isn't actually reachable from this step yet (no SimpleSaveExportCard on
  // the export route this milestone). Now behaves like Prioriteringar/Resultat: always un-checked,
  // static fallback description, regardless of input.
  it("Exportera: always un-checked, static fallback description (saving not reachable from this step yet)", () => {
    const result = completionFor({ participantsCount: 260, timeSlotsCount: 3, optimizationRunsCount: 2 });
    expect(result[5]).toEqual({ completed: undefined, description: sv.simple.stepDescriptions.exportera });
  });
});
