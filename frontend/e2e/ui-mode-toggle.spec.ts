import { test, expect } from "@playwright/test";
import { sv } from "../src/i18n/sv";
import { UI_MODE_STORAGE_KEY } from "../src/lib/uiMode/uiMode";
import { SIMPLE_STEPS } from "../src/routes/plan/planSimpleSteps";
import { resetServerUiMode } from "./helpers/uiMode";

// Deliberately does NOT seed a mode via helpers/uiMode.ts's useAdvancedMode/useSimpleMode (unlike
// every other e2e spec): this spec's own "simple by default" assertion below needs a genuine
// fresh-user boot with no `gp.uiMode` localStorage entry at all - useSimpleMode's addInitScript
// re-runs on every navigation/reload too, which would force-reset SIMPLE on the reload-persistence
// leg further down and make that assertion pass no matter what the app actually persists. Instead,
// this just clears whatever a PREVIOUS spec run in this same browser context may have left behind.
//
// The removal itself must run at most ONCE, not on every navigation: `page.addInitScript` re-fires
// on every navigation the page makes - including this spec's own `page.reload()` further down - so
// an unconditional removeItem would just as eagerly wipe out the ADVANCED value the real app wrote
// to localStorage moments earlier during the toggle, defeating the reload-persistence assertion (the
// same class of bug B10 flagged in the old useSimpleMode-seeded version, just in the opposite
// direction). A sessionStorage marker - which itself survives a reload, unlike localStorage cleared
// here - makes the removal a true one-shot: gone before the very first paint, untouched afterwards.
//
// Every OTHER spec in this suite runs against the same backend for the whole run
// (playwright.config.ts, fullyParallel: false) and seeds ADVANCED via useAdvancedMode, which now
// that /api/app-settings genuinely persists (B3) leaves the backend's durable uiMode as ADVANCED by
// the time this spec runs. UiModeSync's background GET would then reconcile the freshly-cleared
// localStorage mirror right back to ADVANCED on first paint, breaking the "simple by default"
// assertion below even with no local value present - so also reset the durable value directly
// (resetServerUiMode, no addInitScript attached, fires exactly once) before the app boots.
test.beforeEach(async ({ page }) => {
  await resetServerUiMode(page, "SIMPLE");
  await page.addInitScript(
    (key) => {
      const marker = "e2e-ui-mode-cleared-once";
      if (window.sessionStorage.getItem(marker)) {
        return;
      }
      window.sessionStorage.setItem(marker, "1");
      window.localStorage.removeItem(key);
    },
    UI_MODE_STORAGE_KEY,
  );
});

/**
 * v0.6.0 F1 (M-S1) + F2 (M-S2) end-to-end: the UI mode toggle, confirm modal, header badge, the
 * four <AdvancedRouteGate>-wrapped tabs (falt/tranare/kapacitet/planer), and (F2) the SIMPLE-mode
 * 6-step Stepper that replaces the 9-tab bar (PlanLayout.tsx/PlanSimpleStepper.tsx). Since F2, falt
 * is NOT one of the six simple steps - reached here via a direct URL navigation ("deep link"),
 * mirroring how an admin might land on it (e.g. a bookmarked link) while in SIMPLE mode.
 *
 * NOTE: exercises the not-yet-merged backend milestone's GET/PUT /api/app-settings. If that
 * endpoint isn't present in this checkout, UiModeSync's background GET simply 404s (silently kept
 * local per its doc comment) but the final "persists across reload" assertion needs the PUT to have
 * actually saved server-side to be meaningful - run this spec only once that milestone is merged.
 */
test("simple by default → toggle to advanced via navbar switch → falt renders → persists across reload → toggle back via badge", async ({
  page,
}) => {
  const seasonName = `E2E-uimode-säsong-${Date.now()}`;
  const planName = `E2E-uimode-plan-${Date.now()}`;

  await page.goto("/");
  await expect(page.getByRole("heading", { name: sv.start.heading, level: 2 })).toBeVisible();

  // --- Create season + activity plan (same flow as plan-flow.spec.ts) ---
  await page.getByRole("button", { name: sv.start.createSeasonButton }).click();
  const createSeasonDialog = page.getByRole("dialog", { name: sv.createSeasonModal.title });
  await createSeasonDialog.getByLabel(sv.createSeasonModal.nameLabel).fill(seasonName);
  await createSeasonDialog.getByRole("button", { name: sv.createSeasonModal.submit }).click();
  await expect(page).toHaveURL(/\/seasons\//);

  await page.getByRole("button", { name: sv.season.createPlanButton }).click();
  const createPlanDialog = page.getByRole("dialog", { name: sv.createPlanModal.title });
  await createPlanDialog.getByLabel(sv.createPlanModal.nameLabel).fill(planName);
  await createPlanDialog.getByRole("button", { name: sv.createPlanModal.submit }).click();
  await expect(page).toHaveURL(/\/plans\//);
  await expect(page).toHaveURL(/\/deltagare$/);

  // --- Simple by default (fresh-user boot, no gp.uiMode key at all): switch off, no badge, the
  // 6-step simple stepper is shown instead of the 9-tab bar, and falt (an <AdvancedRouteGate>-only
  // tab) is not among its steps (F2 - M-S2). ---
  const uiModeSwitch = page.getByRole("switch", { name: sv.uiMode.switchAriaLabel });
  await expect(uiModeSwitch).not.toBeChecked();
  await expect(page.getByTestId("ui-mode-advanced-badge")).toHaveCount(0);
  await expect(page.getByTestId("plan-simple-stepper")).toBeVisible();
  await expect(page.getByRole("tab")).toHaveCount(0);
  for (const step of SIMPLE_STEPS) {
    await expect(page.getByTestId(step.testId)).toBeVisible();
  }
  // (falt not being one of SIMPLE_STEPS' paths is a pure data-table fact, not something the browser
  // needs to prove - pinned in planSimpleSteps.test.ts instead, F2 review fix FIX 6.)

  // --- Sticky footer nav (PlanSimpleStepFooter.tsx): "Nästa: Tider →" moves from Deltagare to the
  // Tider step's route (F2 review fix FIX 6 - exercise the new chrome, not just assert it exists). ---
  await page.getByTestId("simple-step-next").click();
  await expect(page).toHaveURL(/\/resurser$/);

  // --- falt isn't reachable via the stepper in SIMPLE mode - a direct navigation (deep link)
  // still shows the route-gate card, not FieldsPanel. ---
  const planUrl = page.url().replace(/\/resurser$/, "");
  await page.goto(`${planUrl}/falt`);
  await expect(page.getByTestId("ui-mode-route-gate")).toBeVisible();

  // --- SIMPLE header collapses edit/delete into one Menu behind plan-header-menu-button
  // (PlanLayout.tsx, F2 review fix FIX 6) - open it and check both items render. ---
  await page.getByTestId("plan-header-menu-button").click();
  await expect(page.getByRole("menuitem", { name: sv.plan.menu.edit })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: sv.plan.menu.delete })).toBeVisible();
  await page.keyboard.press("Escape");

  // --- Turning the navbar switch on requires confirming the modal ---
  // force:true - Mantine's Switch renders a decorative track/thumb on top of the (visually hidden)
  // real input, which otherwise intercepts Playwright's pointer-events-visibility check.
  await uiModeSwitch.click({ force: true });
  const confirmModal = page.getByRole("dialog", { name: sv.uiMode.enableConfirm.title });
  await expect(confirmModal).toBeVisible();
  await confirmModal.getByRole("button", { name: sv.uiMode.enableConfirm.confirm }).click();

  await expect(uiModeSwitch).toBeChecked();
  await expect(page.getByTestId("ui-mode-advanced-badge")).toBeVisible();

  // Fält now renders FieldsPanel for real (its "Nytt fält" button), not the gate card.
  await expect(page.getByTestId("ui-mode-route-gate")).toHaveCount(0);
  await expect(page.getByRole("button", { name: sv.fieldBuilder.newFieldButton })).toBeVisible();

  // --- ADVANCED: the 9-tab bar returns, the simple stepper is gone (F2 - M-S2). Restored (F2
  // review fix FIX 6) to assert every tab label, not just Fält - the whole point of this leg is that
  // ADVANCED shows the FULL 9-tab bar, not a reduced one. ---
  await expect(page.getByTestId("plan-simple-stepper")).toHaveCount(0);
  for (const tabLabel of Object.values(sv.plan.tabs)) {
    await expect(page.getByRole("tab", { name: tabLabel, exact: true })).toBeVisible();
  }

  // --- Persists across reload (durable value, not just this tab's in-memory store): nothing
  // re-seeds `gp.uiMode` on this reload (see this spec's own beforeEach note above), so ADVANCED
  // surviving here proves the localStorage mirror - and not just in-memory zustand state - actually
  // persisted the change. ---
  await page.reload();
  await expect(uiModeSwitch).toBeChecked();
  await expect(page.getByTestId("ui-mode-advanced-badge")).toBeVisible();
  await expect(page.getByTestId("ui-mode-route-gate")).toHaveCount(0);

  // --- Toggle back via the header badge - friction-free, no confirm modal ---
  await page.getByTestId("ui-mode-advanced-badge").click();
  await expect(page.getByTestId("ui-mode-advanced-badge")).toHaveCount(0);
  await expect(page.getByTestId("ui-mode-route-gate")).toBeVisible();

  // --- Back in SIMPLE: the stepper chrome returns too (still on the falt deep link, which stays
  // gated - the stepper itself always renders in SIMPLE mode, just with no step active here since
  // falt isn't one of the six). ---
  await expect(page.getByTestId("plan-simple-stepper")).toBeVisible();
  await expect(page.getByRole("tab")).toHaveCount(0);
});
