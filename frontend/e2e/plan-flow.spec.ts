import { test, expect } from "@playwright/test";
import { sv } from "../src/i18n/sv";

/**
 * End-to-end happy path against the real dev backend (docs/plan.md M2 exit criterion): create a
 * season, open it, create an activity plan, navigate every tab, then delete plan + season.
 */
test("create season → open it → create activity plan → navigate tabs → delete plan + season", async ({
  page,
}) => {
  const seasonName = `E2E-säsong-${Date.now()}`;
  const planName = `E2E-plan-${Date.now()}`;

  await page.goto("/");
  // level: 2 disambiguates from the app-shell header, which also renders "Gruppindelning" (h4).
  await expect(page.getByRole("heading", { name: sv.start.heading, level: 2 })).toBeVisible();

  // --- Create season ---
  await page.getByRole("button", { name: sv.start.createSeasonButton }).click();
  const createSeasonDialog = page.getByRole("dialog", { name: sv.createSeasonModal.title });
  await createSeasonDialog.getByLabel(sv.createSeasonModal.nameLabel).fill(seasonName);
  await createSeasonDialog.getByRole("button", { name: sv.createSeasonModal.submit }).click();

  // --- Season page ---
  await expect(page).toHaveURL(/\/seasons\//);
  await expect(page.getByRole("heading", { name: seasonName })).toBeVisible();

  // --- Create activity plan ---
  await page.getByRole("button", { name: sv.season.createPlanButton }).click();
  const createPlanDialog = page.getByRole("dialog", { name: sv.createPlanModal.title });
  await createPlanDialog.getByLabel(sv.createPlanModal.nameLabel).fill(planName);
  await createPlanDialog.getByRole("button", { name: sv.createPlanModal.submit }).click();

  // --- Plan layout ---
  await expect(page).toHaveURL(/\/plans\//);
  await expect(page.getByRole("heading", { name: planName })).toBeVisible();
  // Default tab redirect lands on "Deltagare".
  await expect(page).toHaveURL(/\/deltagare$/);

  // Deltagare (M3: a basic table + "Importera" button, not a placeholder anymore — see
  // import-flow.spec.ts for the wizard itself).
  await page.getByRole("tab", { name: sv.plan.tabs.participants }).click();
  await expect(page.getByRole("tab", { name: sv.plan.tabs.participants })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByText(sv.participants.empty)).toBeVisible();
  await expect(page.getByRole("button", { name: sv.participants.importButton })).toBeVisible();

  const placeholderTabs = [
    sv.plan.tabs.fields,
    sv.plan.tabs.resources,
    sv.plan.tabs.coaches,
    sv.plan.tabs.capacity,
    sv.plan.tabs.optimize,
    sv.plan.tabs.results,
    sv.plan.tabs.export,
  ];

  for (const tabLabel of placeholderTabs) {
    await page.getByRole("tab", { name: tabLabel }).click();
    await expect(page.getByRole("tab", { name: tabLabel })).toHaveAttribute("aria-selected", "true");
    await expect(page.getByText(sv.plan.comingSoon)).toBeVisible();
  }

  // --- Delete the plan, back to season ---
  await page.getByRole("button", { name: sv.plan.deleteButton }).click();
  const deletePlanDialog = page.getByRole("dialog", { name: sv.deletePlanModal.title });
  await deletePlanDialog.getByRole("button", { name: sv.deletePlanModal.confirm }).click();

  await expect(page).toHaveURL(/\/seasons\//);
  await expect(page.getByRole("heading", { name: seasonName })).toBeVisible();
  await expect(page.getByText(planName)).toHaveCount(0);

  // --- Delete the season, back to start ---
  await page.getByRole("button", { name: sv.season.deleteSeasonButton }).click();
  const deleteSeasonDialog = page.getByRole("dialog", { name: sv.deleteSeasonModal.title });
  await deleteSeasonDialog.getByRole("button", { name: sv.deleteSeasonModal.confirm }).click();

  await expect(page).toHaveURL("http://localhost:5173/");
  await expect(page.getByText(seasonName)).toHaveCount(0);
});
