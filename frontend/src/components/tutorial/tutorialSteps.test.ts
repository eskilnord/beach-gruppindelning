import { describe, expect, it } from "vitest";
import { sv } from "../../i18n/sv";
import { SIMPLE_STEPS } from "../../routes/plan/planSimpleSteps";
import {
  resolveTutorialTargetPath,
  TUTORIAL_STEP_CONFIG,
  TUTORIAL_STEP_CONFIG_SIMPLE,
  type TutorialTarget,
} from "./tutorialSteps";

describe("tutorialSteps", () => {
  it("has one config entry per sv.tutorial.steps entry", () => {
    expect(TUTORIAL_STEP_CONFIG).toHaveLength(sv.tutorial.steps.length);
    expect(TUTORIAL_STEP_CONFIG).toHaveLength(10);
  });

  it("resolves the home step regardless of an active plan", () => {
    expect(resolveTutorialTargetPath({ kind: "home" }, undefined)).toBe("/");
    expect(resolveTutorialTargetPath({ kind: "home" }, "plan-1")).toBe("/");
  });

  it("resolves a plan tab only when a plan is active", () => {
    expect(resolveTutorialTargetPath({ kind: "tab", tab: "resurser" }, undefined)).toBeNull();
    expect(resolveTutorialTargetPath({ kind: "tab", tab: "resurser" }, "plan-1")).toBe("/plans/plan-1/resurser");
  });

  it("resolves the import step to the standalone import route", () => {
    expect(resolveTutorialTargetPath({ kind: "import" }, "plan-1")).toBe("/plans/plan-1/import");
    expect(resolveTutorialTargetPath({ kind: "import" }, undefined)).toBeNull();
  });
});

// v0.6.0 F6 (M-S6): the SIMPLE-mode kom-igång-guiden's config table.
describe("TUTORIAL_STEP_CONFIG_SIMPLE", () => {
  it("has one config entry per sv.tutorial.simpleSteps entry", () => {
    expect(TUTORIAL_STEP_CONFIG_SIMPLE).toHaveLength(sv.tutorial.simpleSteps.length);
    expect(TUTORIAL_STEP_CONFIG_SIMPLE).toHaveLength(6);
  });

  it("every step is reachable in SIMPLE mode - home, or one of the six SIMPLE_STEPS' tab paths", () => {
    const simpleReachablePaths = new Set(SIMPLE_STEPS.map((step) => step.path));
    TUTORIAL_STEP_CONFIG_SIMPLE.forEach((config) => {
      if (config.target.kind === "home") {
        return;
      }
      expect(config.target.kind).toBe("tab");
      const target = config.target as Extract<TutorialTarget, { kind: "tab" }>;
      expect(simpleReachablePaths.has(target.tab)).toBe(true);
    });
  });

  // Explicit regression net (F6 loose-ends check): none of the four gated ADVANCED-only tabs is
  // ever a SIMPLE tutorial target - "Ta mig dit" for those isn't reachable from the 6-step config at
  // all.
  //
  // v0.6.0 F6 review fix (FIX 5, MINOR): "export" used to be lumped into this same gated/
  // ADVANCED-only set, which was simply false - planSimpleSteps.ts's SIMPLE_STEPS DOES include an
  // "export" path (the sixth step, "Spara & exportera"), it's fully reachable from the SIMPLE
  // stepper. TUTORIAL_STEP_CONFIG_SIMPLE's own last step targets "resultat" instead (its primary
  // surface - see that step's bullets, which explicitly point onward to the "Spara & exportera" step
  // for saving/exporting), which is a deliberate design choice, not evidence that "export" itself is
  // gated - so it has no business being asserted here alongside the four tabs that genuinely ARE
  // <AdvancedRouteGate>-only.
  it("never targets a gated ADVANCED-only tab (falt/tranare/kapacitet/planer)", () => {
    const advancedOnly = new Set(["falt", "tranare", "kapacitet", "planer"]);
    TUTORIAL_STEP_CONFIG_SIMPLE.forEach((config) => {
      if (config.target.kind === "tab") {
        expect(advancedOnly.has(config.target.tab)).toBe(false);
      }
    });
  });

  it("resolves the prioriteringar target to the plan's prioriteringar route", () => {
    expect(resolveTutorialTargetPath({ kind: "tab", tab: "prioriteringar" }, "plan-1")).toBe(
      "/plans/plan-1/prioriteringar",
    );
  });
});
