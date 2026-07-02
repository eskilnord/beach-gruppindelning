import { describe, expect, it } from "vitest";
import { sv } from "../../i18n/sv";
import { resolveTutorialTargetPath, TUTORIAL_STEP_CONFIG } from "./tutorialSteps";

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
