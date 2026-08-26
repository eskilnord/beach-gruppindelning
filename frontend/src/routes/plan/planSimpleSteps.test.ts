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
    activeCourtsCount: undefined,
    optimizationRunsCount: undefined,
    latestRunFinished: undefined,
    priorityOrder: undefined,
    savedPlansCount: undefined,
  };

  it("leaves the live-signal steps un-checked with no description when nothing has loaded yet", () => {
    const result = completionFor(EMPTY);
    expect(result).toHaveLength(SIMPLE_STEPS.length);
    [0, 1, 3].forEach((index) => {
      expect(result[index].completed).toBeUndefined();
      expect(result[index].description).toBeUndefined();
    });
    // Resultat has no description signal of its own even once `latestRunFinished` resolves - see
    // its own describe block below - but with NOTHING loaded it's un-checked too.
    expect(result[4].completed).toBeUndefined();
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

  // v0.6.0 audit-fix A8: the checkmark now gates on ACTIVE COURT count, not the raw slot count - a
  // plan can have slots configured with every court switched off (zero real capacity). The
  // description still shows the slot count.
  describe("Tider (resurser step)", () => {
    it("singular/plural description picked by slot count", () => {
      expect(completionFor({ ...EMPTY, timeSlotsCount: 1, activeCourtsCount: 2 })[1].description).toBe("1 tid");
      expect(completionFor({ ...EMPTY, timeSlotsCount: 3, activeCourtsCount: 2 })[1].description).toBe("3 tider");
    });

    it("checked once at least one court is active", () => {
      expect(completionFor({ ...EMPTY, timeSlotsCount: 1, activeCourtsCount: 2 })[1].completed).toBe(true);
    });

    it("NOT checked when slots exist but every court is inactive (0 real capacity)", () => {
      expect(completionFor({ ...EMPTY, timeSlotsCount: 3, activeCourtsCount: 0 })[1]).toEqual({
        completed: false,
        description: "3 tider",
      });
    });

    it("un-checked, no description while the slot count itself hasn't loaded", () => {
      expect(completionFor({ ...EMPTY, activeCourtsCount: 2 })[1]).toEqual({
        completed: undefined,
        description: undefined,
      });
    });
  });

  // v0.6.0 F2 review fix (FIX 8): Resultat/Exportera never have a live count to derive completion
  // from, but they DO get a static fallback description (sv.simple.stepDescriptions) so every step
  // in the stepper renders a description line - not just the ones with a cheap signal.
  it("Prioriteringar: no signal (query not loaded) - un-checked, static fallback description", () => {
    const result = completionFor({ ...EMPTY, participantsCount: 260, timeSlotsCount: 3, activeCourtsCount: 2, optimizationRunsCount: 2, latestRunFinished: true });
    expect(result[2]).toEqual({ completed: undefined, description: sv.simple.stepDescriptions.prioriteringar });
  });

  // v0.6.0 F3 (M-S3), review fix FIX 4 (MAJOR): once the real priority-order query resolves, the
  // DESCRIPTION always reflects the actual current state, not a static placeholder - but `completed`
  // now gates on `updatedAt !== null` (the order has actually been explicitly saved at least once),
  // not merely on the query having resolved - see priorityCompletion's own doc comment for why
  // "resolved" alone isn't evidence of anything (every plan is seeded with a default order).
  describe("Prioriteringar: once the priority-order query has resolved", () => {
    it("shows the top priority AND checks the step once the order has actually been saved (updatedAt set)", () => {
      const result = completionFor({
        ...EMPTY,
        participantsCount: 260,
        timeSlotsCount: 3,
        activeCourtsCount: 2,
        optimizationRunsCount: 2,
        priorityOrder: { customWeightsActive: false, topPriorityLabelSv: "Träna tillsammans", updatedAt: "2026-01-01T00:00:00Z" },
      });
      expect(result[2]).toEqual({ completed: true, description: "Viktigast: Träna tillsammans" });
    });

    it("shows the top priority but does NOT check the step while the order has never been saved (updatedAt null)", () => {
      const result = completionFor({
        ...EMPTY,
        participantsCount: 260,
        timeSlotsCount: 3,
        activeCourtsCount: 2,
        optimizationRunsCount: 2,
        priorityOrder: { customWeightsActive: false, topPriorityLabelSv: "Träna tillsammans", updatedAt: null },
      });
      expect(result[2]).toEqual({ completed: false, description: "Viktigast: Träna tillsammans" });
    });

    it("shows 'Anpassade vikter' when advanced-mode weight edits have moved the plan off the order ladder", () => {
      const result = completionFor({
        ...EMPTY,
        participantsCount: 260,
        timeSlotsCount: 3,
        activeCourtsCount: 2,
        optimizationRunsCount: 2,
        priorityOrder: { customWeightsActive: true, topPriorityLabelSv: "Träna tillsammans", updatedAt: "2026-01-01T00:00:00Z" },
      });
      expect(result[2]).toEqual({ completed: true, description: sv.simple.stepDescriptions.prioritiesCustomWeights });
    });
  });

  // v0.6.0 audit-fix A8: the checkmark now gates on the LATEST run's status, not merely on
  // `runs.length > 0` - a run that's still solving (or was cancelled/failed) isn't "done".
  describe("Optimera", () => {
    it("description shows the total run count regardless of whether the latest one finished", () => {
      expect(completionFor({ ...EMPTY, optimizationRunsCount: 1, latestRunFinished: false })[3].description).toBe("1 körning");
      expect(completionFor({ ...EMPTY, optimizationRunsCount: 2, latestRunFinished: true })[3].description).toBe("2 körningar");
    });

    it("checked once the latest run has actually finished", () => {
      expect(completionFor({ ...EMPTY, optimizationRunsCount: 2, latestRunFinished: true })[3].completed).toBe(true);
    });

    it("NOT checked while the latest run is still solving (or failed/cancelled)", () => {
      expect(completionFor({ ...EMPTY, optimizationRunsCount: 1, latestRunFinished: false })[3].completed).toBe(false);
    });

    it("un-checked, no description while the run count itself hasn't loaded", () => {
      expect(completionFor({ ...EMPTY, latestRunFinished: true })[3]).toEqual({ completed: undefined, description: undefined });
    });
  });

  // v0.6.0 audit-fix A8: Resultat used to be permanently un-checked (a "cheap distinct signal"
  // objection that no longer holds - it reuses Optimera's own `latestRunFinished`, no extra call).
  describe("Resultat", () => {
    it("checked once the latest run has finished - same signal as Optimera, no extra call", () => {
      const result = completionFor({ ...EMPTY, participantsCount: 260, timeSlotsCount: 3, activeCourtsCount: 2, optimizationRunsCount: 2, latestRunFinished: true });
      expect(result[4]).toEqual({ completed: true, description: sv.simple.stepDescriptions.resultat });
    });

    it("NOT checked while the latest run hasn't finished", () => {
      const result = completionFor({ ...EMPTY, participantsCount: 260, timeSlotsCount: 3, activeCourtsCount: 2, optimizationRunsCount: 1, latestRunFinished: false });
      expect(result[4]).toEqual({ completed: false, description: sv.simple.stepDescriptions.resultat });
    });
  });

  // v0.6.0 F6 (M-S6): restored (F2 review fix FIX 3's own TODO) - SimpleSaveExportCard now lands on
  // the export route, so a saved-plans count is real, cheap evidence again. Same singular/plural +
  // "loaded but zero is not completed" shape as Deltagare/Optimera above.
  it("Exportera: completed + saved-plans count once loaded", () => {
    expect(completionFor({ ...EMPTY, savedPlansCount: 1 })[5]).toEqual({
      completed: true,
      description: "1 sparad plan",
    });
    expect(completionFor({ ...EMPTY, savedPlansCount: 3 })[5]).toEqual({
      completed: true,
      description: "3 sparade planer",
    });
  });

  it("Exportera: loaded but empty is NOT completed, still shows the zero count", () => {
    expect(completionFor({ ...EMPTY, savedPlansCount: 0 })[5]).toEqual({
      completed: false,
      description: "0 sparade planer",
    });
  });

  it("Exportera: no signal (query not loaded) - un-checked, no description", () => {
    expect(completionFor(EMPTY)[5]).toEqual({ completed: undefined, description: undefined });
  });
});
