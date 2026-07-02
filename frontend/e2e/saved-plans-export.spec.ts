import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { sv } from "../src/i18n/sv";

// package.json has "type": "module", so __dirname isn't available — derive it from import.meta.url
// (same pattern as the other e2e specs).
const __dirname = path.dirname(fileURLToPath(import.meta.url));

// 6 unique participants (never reused by another spec's fixture, see optimize-results.spec.ts's own
// note - specs in this suite share one backend/DB for the whole run, playwright.config.ts
// fullyParallel:false).
const FIXTURE_PATH = path.join(__dirname, "fixtures/saved-plans-export-fixture.csv.txt");

const SLOT_LABEL = "Torsdag 18.00–19.30";

/**
 * M8 frontend acceptance flow (milestone brief): seed a plan (import → resurser → tränare) →
 * generate groups → GREEDY solve → Planer tab: spara plan → status Sparad→Låst (only legal next
 * steps rendered, §14.2/§14.3) → Export tab: flat csv download (asserts a real browser download
 * event + the backend's `{plan}_export.csv` filename convention) → "Inkludera kommentarer" warning
 * appears once ticked (§20.3) → anonymiserat testdata download (§21.3) → Säsongsvy: plan table shows
 * real Deltagare/Grupper/Tränare counts (§19.2) and an empty Konflikter panel (only one plan in the
 * season, so nothing can double-book yet).
 */
test("Planer (spara+lås) → Export (flat csv+anonymiserat) → Säsongsvy (antal+konflikter)", async ({ page }) => {
  const seasonName = `E2E-savedplans-sasong-${Date.now()}`;
  const planName = `E2E-savedplans-plan-${Date.now()}`;

  await page.goto("/");

  // --- Create a season + activity plan ---
  await page.getByRole("button", { name: sv.start.createSeasonButton }).click();
  const createSeasonDialog = page.getByRole("dialog", { name: sv.createSeasonModal.title });
  await createSeasonDialog.getByLabel(sv.createSeasonModal.nameLabel).fill(seasonName);
  await createSeasonDialog.getByRole("button", { name: sv.createSeasonModal.submit }).click();

  await expect(page).toHaveURL(/\/seasons\//);
  const seasonUrl = page.url();
  await page.getByRole("button", { name: sv.season.createPlanButton }).click();
  const createPlanDialog = page.getByRole("dialog", { name: sv.createPlanModal.title });
  await createPlanDialog.getByLabel(sv.createPlanModal.nameLabel).fill(planName);
  await createPlanDialog.getByRole("button", { name: sv.createPlanModal.submit }).click();
  await expect(page).toHaveURL(/\/deltagare$/);

  // --- Import 6 participants via the M3 wizard ---
  await page.getByRole("button", { name: sv.participants.importButton }).click();
  await expect(page).toHaveURL(/\/import(\?.*)?$/);
  await expect(page.getByRole("heading", { name: sv.importWizard.file.heading, level: 4 })).toBeVisible();
  await page.locator('input[type="file"]').setInputFiles({
    name: "saved-plans-export-fixture.csv",
    mimeType: "text/csv",
    buffer: readFileSync(FIXTURE_PATH),
  });
  await expect(page.getByRole("heading", { name: sv.importWizard.sheet.heading, level: 4 })).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.sheet.nextButton }).click();
  await expect(page.getByRole("heading", { name: sv.importWizard.mapping.heading, level: 4 })).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.mapping.nextButton }).click();
  await expect(page.getByRole("heading", { name: sv.importWizard.validate.heading, level: 4 })).toBeVisible();
  await expect(page.getByText(sv.importWizard.validate.summary(6, 0, 0))).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.validate.nextButton }).click();
  await expect(page.getByRole("heading", { name: sv.importWizard.commit.heading, level: 4 })).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.commit.submit, exact: true }).click();
  await expect(page.getByRole("heading", { name: sv.importWizard.commit.resultHeading, level: 4 })).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.commit.goToParticipants }).click();
  await expect(page).toHaveURL(/\/deltagare$/);

  // --- Resurser: one time slot, one court -> one TrainingBlock ---
  await page.getByRole("tab", { name: sv.plan.tabs.resources }).click();
  await page.getByRole("button", { name: sv.resources.newSlotButton }).click();
  const slotDialog = page.getByRole("dialog", { name: sv.resources.slotModal.createTitle });
  await slotDialog.getByRole("textbox", { name: sv.resources.slotModal.dayLabel }).click();
  await page.getByRole("option", { name: sv.days.THURSDAY }).click();
  await slotDialog.getByLabel(sv.resources.slotModal.startTimeLabel).fill("18:00");
  await slotDialog.getByLabel(sv.resources.slotModal.endTimeLabel).fill("19:30");
  await slotDialog.getByRole("button", { name: sv.resources.slotModal.submit }).click();
  await expect(slotDialog).toHaveCount(0);

  const slotRow = page.locator('[data-testid="time-slot-row"]').filter({ hasText: SLOT_LABEL });
  await expect(slotRow).toHaveCount(1);
  await slotRow.getByLabel(sv.resources.courtsLabel).fill("1");
  await slotRow.getByLabel(sv.resources.courtsLabel).blur();
  await expect(slotRow.locator('[data-testid="block-chip"]')).toHaveCount(1);

  // --- Tränare: one coach, linked to an already-imported participant ---
  await page.getByRole("tab", { name: sv.plan.tabs.coaches }).click();
  await page.getByRole("button", { name: sv.coaches.newCoachButton }).click();
  const coachDialog = page.getByRole("dialog", { name: sv.coaches.newCoachModal.title });
  const personInput = coachDialog.getByRole("textbox", { name: sv.coaches.newCoachModal.personLabel });
  await personInput.click();
  await personInput.fill("Linnea");
  await page.getByRole("option", { name: "Linnea Berg" }).click();
  await coachDialog.getByRole("button", { name: sv.coaches.newCoachModal.submit }).click();
  await expect(coachDialog).toHaveCount(0);
  await expect(page.getByRole("row").filter({ hasText: "Linnea Berg" })).toHaveCount(1);

  // --- Optimering: generate groups, run GREEDY (synchronous, deterministic) ---
  await page.getByRole("tab", { name: sv.plan.tabs.optimize }).click();
  await expect(page.getByRole("heading", { name: sv.optimize.heading, exact: true })).toBeVisible();
  const groupsSummary = page.getByTestId("groups-summary");
  await page.getByRole("button", { name: sv.optimize.groups.generateButton }).click();
  await expect(groupsSummary.getByText(sv.optimize.groups.count(1))).toBeVisible();

  // §14.4 blocking checkboxes default per the MVP note: blockPlayers+blockCoaches on.
  const blockingGroup = page.getByTestId("blocking-checkboxes");
  await expect(blockingGroup.getByRole("checkbox", { name: sv.optimize.blocking.blockPlayers })).toBeChecked();
  await expect(blockingGroup.getByRole("checkbox", { name: sv.optimize.blocking.blockCoaches })).toBeChecked();
  await expect(blockingGroup.getByRole("checkbox", { name: sv.optimize.blocking.blockCourts })).not.toBeChecked();

  // v0.2.0: the presets moved under the "Avancerat" collapse (suggestion-first Optimeringsvy).
  await page.getByTestId("advanced-toggle").click();
  await page.getByRole("radio", { name: sv.optimize.profiles.GREEDY.label }).click();
  await page.getByRole("button", { name: sv.optimize.startButton }).click();
  const lastRunCard = page.getByTestId("last-run-summary");
  await expect(lastRunCard.getByTestId("last-run-score-line")).toBeVisible({ timeout: 10_000 });

  // --- Planer: spara plan, then status Sparad -> Låst (only legal next steps render) ---
  await page.getByRole("tab", { name: sv.plan.tabs.savedPlans }).click();
  await expect(page.getByRole("heading", { name: sv.savedPlans.heading })).toBeVisible();
  await page.getByRole("textbox", { name: sv.savedPlans.saveForm.nameLabel }).fill("Version 1");
  await page.getByRole("button", { name: sv.savedPlans.saveForm.submit }).click();

  const savedPlanRow = page.getByTestId("saved-plan-row").filter({ hasText: "Version 1" });
  await expect(savedPlanRow).toHaveCount(1);
  await expect(savedPlanRow.getByText(sv.savedPlans.status.saved)).toBeVisible();
  // Only the legal next steps for "saved" render - Lås (->locked) and Arkivera (->archived), never a
  // straight-to-published or back-to-draft button.
  await expect(savedPlanRow.getByRole("button", { name: sv.savedPlans.actions.locked })).toBeVisible();
  await expect(savedPlanRow.getByRole("button", { name: sv.savedPlans.actions.archived })).toBeVisible();
  await expect(savedPlanRow.getByRole("button", { name: sv.savedPlans.actions.published })).toHaveCount(0);
  await expect(savedPlanRow.getByRole("button", { name: sv.savedPlans.deleteButton })).toBeVisible();

  await savedPlanRow.getByRole("button", { name: sv.savedPlans.actions.locked }).click();
  await expect(savedPlanRow.getByText(sv.savedPlans.status.locked)).toBeVisible();
  // Once locked: Publicera/Arkivera are legal, Lås is gone, and delete is no longer offered (only
  // draft/saved plans are deletable, spec §14).
  await expect(savedPlanRow.getByRole("button", { name: sv.savedPlans.actions.published })).toBeVisible();
  await expect(savedPlanRow.getByRole("button", { name: sv.savedPlans.actions.locked })).toHaveCount(0);
  await expect(savedPlanRow.getByRole("button", { name: sv.savedPlans.deleteButton })).toHaveCount(0);

  // --- Export: flat csv download, comments warning, then anonymiserat testdata ---
  await page.getByRole("tab", { name: sv.plan.tabs.export }).click();
  await expect(page.getByRole("heading", { name: sv.export.heading })).toBeVisible();

  const exportCard = page.getByTestId("export-card");
  const anonymizedCard = page.getByTestId("anonymized-export-card");

  await exportCard.getByRole("radio", { name: sv.export.format.csv }).click();
  // Grupperad layout is disabled for csv (backend 400s the combination) - flat is auto-selected.
  await expect(exportCard.getByRole("radio", { name: sv.export.layout.grouped })).toBeDisabled();
  await expect(exportCard.getByRole("radio", { name: sv.export.layout.flat })).toBeChecked();

  await expect(page.getByTestId("comments-warning")).toHaveCount(0);
  await exportCard.getByRole("checkbox", { name: sv.export.includeCommentsLabel }).click();
  await expect(page.getByTestId("comments-warning")).toBeVisible();
  await expect(page.getByText(sv.export.includeCommentsWarning)).toBeVisible();
  await exportCard.getByRole("checkbox", { name: sv.export.includeCommentsLabel }).click();
  await expect(page.getByTestId("comments-warning")).toHaveCount(0);

  const [exportDownload] = await Promise.all([
    page.waitForEvent("download"),
    exportCard.getByRole("button", { name: sv.export.exportButton }).click(),
  ]);
  expect(exportDownload.suggestedFilename()).toBe(`${planName}_export.csv`);

  const [anonymizedDownload] = await Promise.all([
    page.waitForEvent("download"),
    anonymizedCard.getByRole("button", { name: sv.export.anonymized.exportButton }).click(),
  ]);
  expect(anonymizedDownload.suggestedFilename()).toBe("anonymiserat.xlsx");

  // --- Säsongsvy: Deltagare/Grupper/Tränare counts + an empty Konflikter panel ---
  await page.goto(seasonUrl);
  const planRow = page.getByRole("row").filter({ hasText: planName });
  await expect(planRow).toHaveCount(1);
  await expect(planRow.getByRole("cell").nth(3)).toHaveText("6"); // Deltagare
  await expect(planRow.getByRole("cell").nth(4)).toHaveText("1"); // Grupper
  await expect(planRow.getByRole("cell").nth(5)).toHaveText("1"); // Tränare

  await expect(page.getByRole("heading", { name: sv.season.conflicts.heading })).toBeVisible();
  await expect(page.getByTestId("conflicts-count-badge")).toHaveText("0");
  await expect(page.getByText(sv.season.conflicts.empty)).toBeVisible();
});
