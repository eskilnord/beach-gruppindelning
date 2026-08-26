import { test, expect, type Page } from "@playwright/test";
import { sv } from "../src/i18n/sv";
import { useAdvancedMode, useSimpleMode } from "./helpers/uiMode";

// Matches playwright.config.ts's `use.baseURL` / helpers/uiMode.ts's own APP_ORIGIN - the Vite dev
// server origin the app itself runs under, which proxies `/api/*` to the fixed-port dev backend.
const APP_ORIGIN = "http://localhost:5173";
const DEV_TOKEN = "dev";

test.beforeEach(async ({ page }) => {
  // ADVANCED (not the SIMPLE product default): the customWeights-path test needs the 9-tab bar's
  // Fält tab reachable to sanity-check the "Öppna avancerat läge" hand-off, and Prioriteringar
  // itself is reachable in EITHER mode (router.tsx: no <AdvancedRouteGate>) so this doesn't affect
  // the reorder-and-persist test either.
  await useAdvancedMode(page);
});

/** Creates a season + activity plan via the UI (same flow as ui-mode-toggle.spec.ts/plan-flow.spec.ts)
 *  and returns the plan's base URL (`/plans/<id>`, no trailing segment). */
async function createPlan(page: Page, label: string): Promise<string> {
  const seasonName = `E2E-prio-säsong-${label}-${Date.now()}`;
  const planName = `E2E-prio-plan-${label}-${Date.now()}`;

  await page.goto("/");
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

  return page.url().replace(/\/deltagare$/, "");
}

/**
 * v0.6.0 F3 (M-S3) end-to-end, against the real backend (playwright.config.ts's webServer): the
 * autosaved reorder round-trips through `GET/PUT /api/plans/{planId}/priority-order` and survives a
 * reload, and the `customWeightsActive` path (seeded by directly PUTting a manual constraint-weight
 * override, mirroring what the ADVANCED Konfiguration tab would do) drives the overrides alert +
 * reset flow correctly against that same real backend.
 */
test("reorder via arrows autosaves and persists across reload", async ({ page }) => {
  const planUrl = await createPlan(page, "reorder");
  await page.goto(`${planUrl}/prioriteringar`);

  const rows = page.getByTestId("priority-row");
  await expect(rows).toHaveCount(4);

  const currentOrder = () => rows.evaluateAll((elements) => elements.map((el) => el.getAttribute("data-priority-key")));
  const originalOrder = await currentOrder();

  // Move the second row up (swaps ranks 1 and 2) via its up-arrow - the first button in its
  // ActionIcon.Group (PriorityRankList.tsx: up before down).
  await rows.nth(1).locator("button").nth(0).click();

  const expectedOrder = [originalOrder[1], originalOrder[0], originalOrder[2], originalOrder[3]];
  await expect.poll(currentOrder).toEqual(expectedOrder);

  // Autosave debounces 600ms, then a real PUT round-trips - "Sparat ✓" confirms it actually landed.
  await expect(page.getByTestId("priority-save-status")).toHaveText(sv.simple.priorities.saved, { timeout: 5_000 });

  await page.reload();
  await expect(page.getByTestId("priority-row")).toHaveCount(4);
  await expect.poll(currentOrder).toEqual(expectedOrder);
});

test("a manually-overridden constraint weight shows the overrides alert, and reset restores the order-driven ladder", async ({
  page,
}) => {
  const planUrl = await createPlan(page, "overrides");
  const planId = planUrl.split("/").pop()!;

  // Seed customWeightsActive directly against the real backend (mirrors what editing "Vikt" on the
  // Konfiguration sub-tab would do) - levelBalance=999 matches none of PriorityOrder.LEVEL_LADDER's
  // four rank values ({340,215,135,85}), so no permutation's weightsFor() output matches any more.
  const putResponse = await page.request.put(new URL(`/api/plans/${planId}/constraint-weights`, APP_ORIGIN).toString(), {
    headers: { "X-GP-Token": DEV_TOKEN },
    data: [{ key: "levelBalance", weight: 999 }],
  });
  expect(putResponse.ok()).toBe(true);

  await page.goto(`${planUrl}/prioriteringar`);

  const alert = page.getByTestId("priority-overrides-alert");
  await expect(alert).toBeVisible();
  await expect(alert).toContainText(sv.simple.priorities.overridesAlert.title);
  await expect(alert).toContainText(sv.simple.priorities.overridesAlert.body);
  // v0.6.0 F3 review fix (FIX 6, MAJOR, inference honesty): the body now also spells out that this
  // order is the backend's INFERENCE from the custom weights, not an admin-confirmed ranking.
  await expect(alert).toContainText("Ordningen nedan är vår tolkning av de anpassade vikterna.");

  // v0.6.0 F3 review fix (FIX 6, MAJOR): pins the FULL inferred order deliberately, not just
  // levelBalance's resulting weight further down - levelBalance=999 is far above any bucket's real
  // ladder weight, so the backend's best-effort inference promotes LEVEL to rank 1; the other three
  // buckets, whose weights were never touched, keep their relative order from this fresh plan's
  // still-default TRAIN_TOGETHER/PREVIOUS_GROUP/PREFERRED_TIME/LEVEL ranking (with LEVEL itself
  // removed from its old rank 4 and promoted to rank 1).
  const inferredOrder = await page
    .getByTestId("priority-row")
    .evaluateAll((elements) => elements.map((el) => el.getAttribute("data-priority-key")));
  expect(inferredOrder).toEqual(["LEVEL", "TRAIN_TOGETHER", "PREVIOUS_GROUP", "PREFERRED_TIME"]);

  // The rank list is dimmed + non-interactive while customWeightsActive.
  await expect(page.getByTestId("priority-rank-list")).toHaveAttribute("aria-disabled", "true");
  for (const button of await page.getByTestId("priority-row").locator("button").all()) {
    await expect(button).toBeDisabled();
  }

  // --- "Öppna avancerat läge" hands off to the real Konfiguration tab (already ADVANCED here via
  // this spec's beforeEach, so no confirm modal - just the navigation itself). ---
  await alert.getByRole("button", { name: sv.simple.priorities.overridesAlert.openAdvancedButton }).click();
  await expect(page).toHaveURL(new RegExp(`/plans/${planId}/falt$`));
  await expect(page.getByRole("button", { name: sv.fieldBuilder.newFieldButton })).toBeVisible();

  // --- Back to Prioriteringar: "Återställ till prioriteringsordning" clears the override for real. ---
  await page.goto(`${planUrl}/prioriteringar`);
  await expect(page.getByTestId("priority-overrides-alert")).toBeVisible();
  await page.getByRole("button", { name: sv.simple.priorities.overridesAlert.resetButton }).click();
  const confirmDialog = page.getByRole("dialog", { name: sv.simple.priorities.resetConfirm.title });
  await expect(confirmDialog).toBeVisible();
  await confirmDialog.getByRole("button", { name: sv.simple.priorities.resetConfirm.confirmLabel }).click();

  await expect(page.getByTestId("priority-overrides-alert")).toHaveCount(0);
  await expect(page.getByTestId("priority-rank-list")).not.toHaveAttribute("aria-disabled", "true");

  // Confirmed directly against the backend too: levelBalance is back on a ladder value (340), not
  // the manually-overridden 999. 340 is LEVEL_LADDER[0] (rank 1) - NOT because LEVEL is somehow the
  // "default order"'s rank-1 bucket (the default order's rank 1 is TRAIN_TOGETHER, not LEVEL - see
  // createPlan's fresh-plan default above) - but because "Återställ" PUTs the INFERRED order asserted
  // above (`inferredOrder`, LEVEL first), and the reset flow's PUT re-derives every bucket's weight
  // from whatever order it's given (FIX 3: it PUTs the shown/displayed order, not a hardcoded
  // default) - LEVEL lands at rank 1 in THAT order, hence LEVEL_LADDER[0].
  const weightsResponse = await page.request.get(new URL(`/api/plans/${planId}/constraint-weights`, APP_ORIGIN).toString(), {
    headers: { "X-GP-Token": DEV_TOKEN },
  });
  const weights = (await weightsResponse.json()) as { key: string; weight: number }[];
  expect(weights.find((w) => w.key === "levelBalance")?.weight).toBe(340);
});

// v0.6.0 F3 review fix (FIX 10, MINOR): the two tests above run in ADVANCED mode (this spec's own
// beforeEach); this pins that the exact same reorder-via-arrows + autosave + reload-persistence path
// also works end-to-end in the SIMPLE mode (Prioriteringar is reachable from EITHER mode - router.tsx
// has no <AdvancedRouteGate> on it, see this spec's top-of-file doc comment).
test("SIMPLE mode: reorder via arrows autosaves and persists across reload", async ({ page }) => {
  // Registered AFTER the beforeEach's useAdvancedMode(page) call - Playwright runs addInitScript
  // callbacks in registration order on every navigation, so this SIMPLE seed wins for this test's
  // page (see helpers/uiMode.ts's own doc comments on the two seed functions).
  await useSimpleMode(page);

  const planUrl = await createPlan(page, "simple-mode");
  await page.goto(`${planUrl}/prioriteringar`);

  // Sanity check that this test is genuinely exercising SIMPLE mode, not silently still ADVANCED:
  // the six-step stepper (PlanSimpleStepper.tsx) only renders in SIMPLE mode (PlanLayout.tsx).
  await expect(page.getByTestId("plan-simple-stepper")).toBeVisible();

  const rows = page.getByTestId("priority-row");
  await expect(rows).toHaveCount(4);

  const currentOrder = () => rows.evaluateAll((elements) => elements.map((el) => el.getAttribute("data-priority-key")));
  const originalOrder = await currentOrder();

  await rows.nth(1).locator("button").nth(0).click();
  const expectedOrder = [originalOrder[1], originalOrder[0], originalOrder[2], originalOrder[3]];
  await expect.poll(currentOrder).toEqual(expectedOrder);
  await expect(page.getByTestId("priority-save-status")).toHaveText(sv.simple.priorities.saved, { timeout: 5_000 });

  await page.reload();
  await expect(page.getByTestId("priority-row")).toHaveCount(4);
  await expect.poll(currentOrder).toEqual(expectedOrder);
});
