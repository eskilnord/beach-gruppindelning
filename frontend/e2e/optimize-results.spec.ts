import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { sv } from "../src/i18n/sv";

// package.json has "type": "module", so __dirname isn't available — derive it from import.meta.url
// (same pattern as the other e2e specs).
const __dirname = path.dirname(fileURLToPath(import.meta.url));

// 6 unique participants (never reused by another spec's fixture - see resources-coaches-capacity
// .spec.ts's own fixture-uniqueness note: specs in this suite share one backend/DB for the whole
// run, playwright.config.ts fullyParallel:false).
const FIXTURE_PATH = path.join(__dirname, "fixtures/optimize-results-fixture.csv.txt");

const SLOT_LABEL = "Torsdag 18.00–19.30";

/**
 * M6b frontend acceptance flow (milestone brief): seed a plan (import → resurser → tränare) →
 * generate groups → GREEDY solve (fast, deterministic - no waitlist logic, see GreedyBaselineService
 * javadoc, so this fixture's 6 players comfortably fit the single generated group's fallback
 * target/max of 10/12 and the OPLACERAD/KÖLISTA card is asserted in its empty state) → Resultatvy
 * shows the group with its members → toggle a player lock → Planeringskarta (Schema sub-view) shows
 * the group on the grid → FAST solve → progress panel appears while solving → completion banner.
 */
test("Optimering (generera+GREEDY+FAST) → Resultatvy (grupper+kölista+lås) → Planeringskarta", async ({ page }) => {
  const seasonName = `E2E-optimize-sasong-${Date.now()}`;
  const planName = `E2E-optimize-plan-${Date.now()}`;

  await page.goto("/");

  // --- Create a season + activity plan ---
  await page.getByRole("button", { name: sv.start.createSeasonButton }).click();
  const createSeasonDialog = page.getByRole("dialog", { name: sv.createSeasonModal.title });
  await createSeasonDialog.getByLabel(sv.createSeasonModal.nameLabel).fill(seasonName);
  await createSeasonDialog.getByRole("button", { name: sv.createSeasonModal.submit }).click();

  await expect(page).toHaveURL(/\/seasons\//);
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
    name: "optimize-results-fixture.csv",
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
  await personInput.fill("Tova");
  await page.getByRole("option", { name: "Tova Lindberg" }).click();
  await coachDialog.getByRole("button", { name: sv.coaches.newCoachModal.submit }).click();
  await expect(coachDialog).toHaveCount(0);
  await expect(page.getByRole("row").filter({ hasText: "Tova Lindberg" })).toHaveCount(1);

  // --- Optimering: generate groups, then run GREEDY (synchronous, deterministic) ---
  await page.getByRole("tab", { name: sv.plan.tabs.optimize }).click();
  await expect(page.getByRole("heading", { name: sv.optimize.heading })).toBeVisible();
  const groupsSummary = page.getByTestId("groups-summary");
  await expect(groupsSummary.getByText(sv.optimize.groups.count(0))).toBeVisible();

  await page.getByRole("button", { name: sv.optimize.groups.generateButton }).click();
  // Scoped to groups-summary - the "1 grupp genererad." success notification toast also matches
  // this text and would otherwise make the plain page-wide locator ambiguous (strict mode).
  await expect(groupsSummary.getByText(sv.optimize.groups.count(1))).toBeVisible();

  await page.getByRole("radio", { name: sv.optimize.profiles.GREEDY.label }).click();
  await page.getByRole("button", { name: sv.optimize.startButton }).click();

  const lastRunCard = page.getByTestId("last-run-summary");
  await expect(lastRunCard.getByTestId("last-run-score-line")).toBeVisible({ timeout: 10_000 });

  // --- Resultatvy: the group card shows all 6 members, kölistan is empty ---
  await page.getByRole("tab", { name: sv.plan.tabs.results }).click();
  await expect(page.getByRole("heading", { name: sv.results.heading })).toBeVisible();

  const groupCard = page.getByTestId("group-card").filter({ hasText: `${planName} 1` });
  await expect(groupCard).toHaveCount(1);
  await expect(groupCard.getByText("6/10/12")).toBeVisible();
  await expect(groupCard.getByRole("row").filter({ hasText: "Tova Lindberg" })).toHaveCount(1);
  await expect(groupCard.getByRole("row").filter({ hasText: "Rasmus Falk" })).toHaveCount(1);

  const waitlistCard = page.getByTestId("waitlist-card");
  await expect(waitlistCard.getByText(sv.results.waitlist.empty)).toBeVisible();

  // --- Toggle a player lock on one member ---
  const tovaRow = groupCard.getByRole("row").filter({ hasText: "Tova Lindberg" });
  await tovaRow.getByRole("button", { name: sv.results.groupCard.lockButton, exact: true }).click();
  await expect(tovaRow.getByRole("button", { name: sv.results.groupCard.unlockButton, exact: true })).toBeVisible();
  await expect(tovaRow).toContainText(sv.results.groupCard.sourceBadge.locked);

  // M7: the spec's own [Förklara]/[Testa flytt] buttons are enabled once a run exists (see
  // explain-whatif.spec.ts for the full explain/what-if acceptance flow).
  await expect(tovaRow.getByRole("button", { name: sv.results.groupCard.explainButton })).toBeEnabled();
  await expect(tovaRow.getByRole("button", { name: sv.results.groupCard.testMoveButton })).toBeEnabled();

  // --- Planeringskarta (Schema sub-view): the group occupies its block, no conflicts ---
  await page.getByText(sv.results.viewToggle.schedule, { exact: true }).click();
  const scheduleGrid = page.getByTestId("schedule-grid");
  await expect(scheduleGrid).toBeVisible();
  await expect(scheduleGrid.getByText(`${planName} 1`)).toBeVisible();
  await expect(page.getByText(sv.results.schedule.noConflicts)).toBeVisible();

  // --- Optimering again: a FAST solve shows the live progress panel while it runs ---
  await page.getByRole("tab", { name: sv.plan.tabs.optimize }).click();
  await page.getByRole("radio", { name: sv.optimize.profiles.FAST.label }).click();
  await page.getByRole("button", { name: sv.optimize.startButton }).click();

  await expect(page.getByTestId("solve-progress")).toBeVisible({ timeout: 10_000 });
  await expect(page.getByTestId("solve-progress")).toHaveCount(0, { timeout: 20_000 });
  await expect(page.getByTestId("last-run-summary").getByTestId("last-run-score-line")).toBeVisible({ timeout: 10_000 });
});
