import { sv } from "../../i18n/sv";

export type TutorialTabPath =
  | "deltagare"
  | "falt"
  | "resurser"
  | "prioriteringar"
  | "tranare"
  | "kapacitet"
  | "optimering"
  | "resultat"
  | "planer"
  | "export";

export type TutorialTarget = { kind: "home" } | { kind: "import" } | { kind: "tab"; tab: TutorialTabPath };

export interface TutorialStepConfig {
  /** Single-emoji step icon - no icon library dependency, per the tutorial's "Mantine components
   *  only, no new heavyweight dependency" brief. */
  icon: string;
  target: TutorialTarget;
}

/**
 * One entry per `sv.tutorial.steps[i]`, in the same order - kept as a parallel array rather than
 * merged into sv.ts, since sv.ts is Swedish COPY only (CLAUDE.md "Language"); the icon and
 * navigation target are UI behavior, not translatable text. Targets mirror the real product flow
 * (router.tsx's plan tabs / the standalone import route), grounded against each panel this step
 * describes:
 *  - home: StartPage.tsx (create season/plan)
 *  - import: ImportWizardPage.tsx
 *  - deltagare: ParticipantsPanel.tsx / ParticipantDrawer.tsx (structured fields)
 *  - resurser: ResourcesPanel.tsx (time slots / courts)
 *  - tranare: CoachesPanel.tsx / AvailabilityMatrix.tsx
 *  - kapacitet: CapacityPanel.tsx
 *  - optimering: OptimizePanel.tsx
 *  - resultat: ResultsPanel.tsx / ExplainDrawer.tsx / WhatIfDialog.tsx / GroupCard's lock buttons
 *  - export: SavedPlansPanel.tsx (spara) + ExportPanel.tsx (exportera)
 */
export const TUTORIAL_STEP_CONFIG: TutorialStepConfig[] = [
  { icon: "📅", target: { kind: "home" } },
  { icon: "📥", target: { kind: "import" } },
  { icon: "🗂️", target: { kind: "tab", tab: "deltagare" } },
  { icon: "⏰", target: { kind: "tab", tab: "resurser" } },
  { icon: "🧑‍🏫", target: { kind: "tab", tab: "tranare" } },
  { icon: "📊", target: { kind: "tab", tab: "kapacitet" } },
  { icon: "⚙️", target: { kind: "tab", tab: "optimering" } },
  { icon: "🔍", target: { kind: "tab", tab: "resultat" } },
  { icon: "🔒", target: { kind: "tab", tab: "resultat" } },
  { icon: "💾", target: { kind: "tab", tab: "export" } },
];

if (TUTORIAL_STEP_CONFIG.length !== sv.tutorial.steps.length) {
  // Defensive - a mismatch here would silently desync icons/targets from copy (module-load-time
  // check, so it fails fast in dev/tests rather than rendering a mismatched step).
  throw new Error("tutorialSteps: TUTORIAL_STEP_CONFIG and sv.tutorial.steps must have the same length");
}

/**
 * v0.6.0 audit batch D (D10): the SIMPLE-mode kom-igång-guiden's icon/target table - one entry per
 * `sv.tutorial.simpleSteps[i]`, in the same order (same parallel-array split as
 * {@link TUTORIAL_STEP_CONFIG} above). Targets mirror PlanSimpleStepper.tsx's six-step IA
 * (planSimpleSteps.ts's SIMPLE_STEPS) exactly, 1:1 - every target is one of the six SIMPLE steps
 * (deltagare/resurser/prioriteringar/optimering/resultat/export), never one of the four gated
 * ADVANCED-only tabs (falt/tranare/kapacitet/planer), none of which are reachable from the SIMPLE
 * stepper at all (see tutorialSteps.test.ts's own assertion of this). Unlike the F6-era version,
 * there is no `{kind: "home"}` step any more - season/plan creation is now covered in step 1's own
 * body text (sv.tutorial.simpleSteps[0]) instead of a dedicated opening step, so every one of these
 * six entries needs an active plan to actually navigate anywhere.
 */
export const TUTORIAL_STEP_CONFIG_SIMPLE: TutorialStepConfig[] = [
  { icon: "📥", target: { kind: "tab", tab: "deltagare" } },
  { icon: "⏰", target: { kind: "tab", tab: "resurser" } },
  { icon: "🎯", target: { kind: "tab", tab: "prioriteringar" } },
  { icon: "⚙️", target: { kind: "tab", tab: "optimering" } },
  { icon: "🔍", target: { kind: "tab", tab: "resultat" } },
  { icon: "💾", target: { kind: "tab", tab: "export" } },
];

if (TUTORIAL_STEP_CONFIG_SIMPLE.length !== sv.tutorial.simpleSteps.length) {
  throw new Error(
    "tutorialSteps: TUTORIAL_STEP_CONFIG_SIMPLE and sv.tutorial.simpleSteps must have the same length",
  );
}

/**
 * Resolves a step's "Ta mig dit" destination, or `null` when it isn't reachable right now. Every
 * target except "home" needs an active plan (`planId`) - TutorialModal disables the button with a
 * tooltip in that case, the same disabled+Tooltip pattern GroupCard/WaitlistCard use for their
 * run-dependent actions.
 */
export function resolveTutorialTargetPath(target: TutorialTarget, planId: string | undefined): string | null {
  if (target.kind === "home") {
    return "/";
  }
  if (!planId) {
    return null;
  }
  if (target.kind === "import") {
    return `/plans/${planId}/import`;
  }
  return `/plans/${planId}/${target.tab}`;
}
