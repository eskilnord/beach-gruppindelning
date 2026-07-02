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

  // Fält (M4: Fältbyggaren, not a placeholder anymore — see field-builder.spec.ts for the full flow).
  // exact: true — Fältbyggaren has its own inner "Alla fält" sub-tab, whose label otherwise
  // substring-matches "Fält" (Playwright role-name matching is case-insensitive substring by default).
  await page.getByRole("tab", { name: sv.plan.tabs.fields, exact: true }).click();
  await expect(page.getByRole("tab", { name: sv.plan.tabs.fields, exact: true })).toHaveAttribute(
    "aria-selected",
    "true",
  );
  await expect(page.getByRole("heading", { name: sv.fieldBuilder.heading })).toBeVisible();
  await expect(page.getByRole("button", { name: sv.fieldBuilder.newFieldButton })).toBeVisible();

  // Resurser (M5: Resursvy, not a placeholder anymore — see resources-coaches-capacity.spec.ts for
  // the full flow).
  await page.getByRole("tab", { name: sv.plan.tabs.resources }).click();
  await expect(page.getByRole("tab", { name: sv.plan.tabs.resources })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("heading", { name: sv.resources.heading })).toBeVisible();
  await expect(page.getByText(sv.resources.empty)).toBeVisible();
  await expect(page.getByRole("button", { name: sv.resources.newSlotButton })).toBeVisible();

  // Tränare (M5: Tränarvy, not a placeholder anymore).
  await page.getByRole("tab", { name: sv.plan.tabs.coaches }).click();
  await expect(page.getByRole("tab", { name: sv.plan.tabs.coaches })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("heading", { name: sv.coaches.heading })).toBeVisible();
  await expect(page.getByText(sv.coaches.empty)).toBeVisible();
  await expect(page.getByRole("button", { name: sv.coaches.newCoachButton })).toBeVisible();

  // Kapacitet (M5: Kapacitetsanalysvy, not a placeholder anymore) - empty state before any time
  // slots exist.
  await page.getByRole("tab", { name: sv.plan.tabs.capacity }).click();
  await expect(page.getByRole("tab", { name: sv.plan.tabs.capacity })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByText(sv.capacity.empty)).toBeVisible();

  // Optimering (M6b: Optimeringsvy, not a placeholder anymore — see optimize-results.spec.ts for the
  // full solve/results/schedule flow). No groups generated yet for this fresh plan.
  await page.getByRole("tab", { name: sv.plan.tabs.optimize }).click();
  await expect(page.getByRole("tab", { name: sv.plan.tabs.optimize })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("heading", { name: sv.optimize.heading, exact: true })).toBeVisible();
  await expect(page.getByText(sv.optimize.groups.count(0))).toBeVisible();

  // Resultat (M6b: Resultatvy, not a placeholder anymore) - empty state before any groups exist.
  await page.getByRole("tab", { name: sv.plan.tabs.results }).click();
  await expect(page.getByRole("tab", { name: sv.plan.tabs.results })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByText(sv.results.empty)).toBeVisible();

  // Planer (M8: SavedPlansPanel, not a placeholder) - empty state before any plan has been saved.
  await page.getByRole("tab", { name: sv.plan.tabs.savedPlans }).click();
  await expect(page.getByRole("tab", { name: sv.plan.tabs.savedPlans })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("heading", { name: sv.savedPlans.heading })).toBeVisible();
  await expect(page.getByText(sv.savedPlans.empty)).toBeVisible();

  // Export (M8: ExportPanel, not a placeholder) - disabled with a hint before any solve has run (see
  // saved-plans-export.spec.ts for the full save/lock/export flow).
  await page.getByRole("tab", { name: sv.plan.tabs.export }).click();
  await expect(page.getByRole("tab", { name: sv.plan.tabs.export })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("heading", { name: sv.export.heading })).toBeVisible();
  await expect(page.getByText(sv.export.emptyNoRun)).toBeVisible();
  // exact: true — "Exportera" otherwise substring-matches the "Exportera anonymiserat" button too
  // (Playwright role-name matching is case-insensitive substring by default).
  await expect(page.getByRole("button", { name: sv.export.exportButton, exact: true })).toBeDisabled();

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
