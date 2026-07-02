import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { sv } from "../src/i18n/sv";

// package.json has "type": "module", so __dirname isn't available — derive it from import.meta.url
// (same pattern as the other e2e specs).
const __dirname = path.dirname(fileURLToPath(import.meta.url));

// 2 unique participants (never reused by another spec's fixture - see resources-coaches-capacity
// .spec.ts's own fixture-uniqueness note: specs in this suite share one backend/DB for the whole
// run, playwright.config.ts fullyParallel:false). "Åsa Örnberg" deliberately carries Swedish
// diacritics (å/ö) - the player-search sub-scenario below searches for her using the plain-ASCII
// "ornberg", proving the Ctrl/Cmd+F search is diacritics-insensitive.
const FIXTURE_PATH = path.join(__dirname, "fixtures/tutorial-search-schedule-fixture.csv.txt");

const SLOT_LABEL = "Torsdag 18.00–19.30";
const COURT_COUNT = 8;

/**
 * v0.2.0 frontend batch acceptance flow: the "Kom igång-guiden" tutorial (open from the AppShell
 * header, step through it, "Ta mig dit" navigates to the right tab), the Ctrl/Cmd+F player-search
 * Spotlight (diacritics-insensitive, jumps to Resultatvy and flash-highlights the hit's row), and
 * the Planeringskarta's horizontal scroll for many courts (sticky time-slot column stays visible).
 */
test("Kom igång-guiden → Ctrl/Cmd+F spelarsök → Schema horisontell scroll", async ({ page }) => {
  const seasonName = `E2E-tutorial-sasong-${Date.now()}`;
  const planName = `E2E-tutorial-plan-${Date.now()}`;

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

  // ============================================================================================
  // FEATURE 1 — Kom igång-guiden: open from a tab OTHER than Deltagare, so "Ta mig dit" actually
  // proves it navigates somewhere (rather than trivially staying on the page we started from).
  // ============================================================================================
  await page.getByRole("tab", { name: sv.plan.tabs.capacity }).click();
  await expect(page).toHaveURL(/\/kapacitet$/);

  await page.getByRole("button", { name: sv.tutorial.headerButtonTooltip }).click();
  const tutorialDialog = page.getByRole("dialog", { name: sv.tutorial.modalTitle });
  await expect(tutorialDialog).toBeVisible();
  await expect(tutorialDialog.getByTestId("tutorial-active-step-title")).toHaveText(sv.tutorial.steps[0].title);

  // Step through 2 steps (Nästa x2) to "Strukturera fält", which targets the Deltagare tab.
  await tutorialDialog.getByRole("button", { name: sv.tutorial.nextButton }).click();
  await expect(tutorialDialog.getByTestId("tutorial-active-step-title")).toHaveText(sv.tutorial.steps[1].title);
  await tutorialDialog.getByRole("button", { name: sv.tutorial.nextButton }).click();
  await expect(tutorialDialog.getByTestId("tutorial-active-step-title")).toHaveText(sv.tutorial.steps[2].title);

  await tutorialDialog.getByTestId("tutorial-go-there").click();
  await expect(tutorialDialog).toHaveCount(0);
  await expect(page).toHaveURL(/\/deltagare$/);

  // --- Import 2 participants via the M3 wizard ---
  await page.getByRole("button", { name: sv.participants.importButton }).click();
  await expect(page).toHaveURL(/\/import(\?.*)?$/);
  await expect(page.getByRole("heading", { name: sv.importWizard.file.heading, level: 4 })).toBeVisible();
  await page.locator('input[type="file"]').setInputFiles({
    name: "tutorial-search-schedule-fixture.csv",
    mimeType: "text/csv",
    buffer: readFileSync(FIXTURE_PATH),
  });
  await expect(page.getByRole("heading", { name: sv.importWizard.sheet.heading, level: 4 })).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.sheet.nextButton }).click();
  await expect(page.getByRole("heading", { name: sv.importWizard.mapping.heading, level: 4 })).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.mapping.nextButton }).click();
  await expect(page.getByRole("heading", { name: sv.importWizard.validate.heading, level: 4 })).toBeVisible();
  await expect(page.getByText(sv.importWizard.validate.summary(2, 0, 0))).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.validate.nextButton }).click();
  await expect(page.getByRole("heading", { name: sv.importWizard.commit.heading, level: 4 })).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.commit.submit, exact: true }).click();
  await expect(page.getByRole("heading", { name: sv.importWizard.commit.resultHeading, level: 4 })).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.commit.goToParticipants }).click();
  await expect(page).toHaveURL(/\/deltagare$/);

  // --- Resurser: one time slot with COURT_COUNT (8) courts, well past "6+" (feature brief) ---
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
  await slotRow.getByLabel(sv.resources.courtsLabel).fill(String(COURT_COUNT));
  await slotRow.getByLabel(sv.resources.courtsLabel).blur();
  await expect(slotRow.locator('[data-testid="block-chip"]')).toHaveCount(COURT_COUNT);

  // --- Optimering: generate the (single) group, then GREEDY solve ---
  await page.getByRole("tab", { name: sv.plan.tabs.optimize }).click();
  const groupsSummary = page.getByTestId("groups-summary");
  await page.getByRole("button", { name: sv.optimize.groups.generateButton }).click();
  await expect(groupsSummary.getByText(sv.optimize.groups.count(1))).toBeVisible();

  // v0.2.0: the presets moved under the "Avancerat" collapse (suggestion-first Optimeringsvy).
  await page.getByTestId("advanced-toggle").click();
  await page.getByRole("radio", { name: sv.optimize.profiles.GREEDY.label }).click();
  await page.getByRole("button", { name: sv.optimize.startButton }).click();
  await expect(page.getByTestId("last-run-summary").getByTestId("last-run-score-line")).toBeVisible({ timeout: 10_000 });

  // ============================================================================================
  // FEATURE 3 — Ctrl/Cmd+F player search: search "ornberg" (plain ASCII) and expect it to find
  // "Åsa Örnberg" (diacritics-insensitive matching), then jump to Resultatvy + flash-highlight.
  // ============================================================================================
  await page.getByRole("tab", { name: sv.plan.tabs.results }).click();
  await expect(page.getByRole("heading", { name: sv.results.heading })).toBeVisible();

  await page.keyboard.press(process.platform === "darwin" ? "Meta+f" : "Control+f");
  const searchInput = page.getByPlaceholder(sv.playerSearch.placeholder);
  await expect(searchInput).toBeVisible();
  await searchInput.fill("ornberg");

  const hit = page.getByRole("button").filter({ hasText: "Örnberg" });
  await expect(hit).toHaveCount(1);
  await hit.click();

  await expect(page).toHaveURL(/\/resultat\?highlight=/);
  const highlightedRow = page.getByRole("row").filter({ hasText: "Åsa Örnberg" });
  await expect(highlightedRow).toBeVisible();
  await expect(highlightedRow).toHaveClass(/gp-highlight-flash/);

  // ============================================================================================
  // FEATURE 2 — Schedule (Planeringskarta) horizontal scroll: COURT_COUNT (8) courts overflow the
  // panel width, the scroll container reports scrollWidth > clientWidth, and the sticky time-slot
  // label column stays visually in place while the container scrolls.
  // ============================================================================================
  await page.getByText(sv.results.viewToggle.schedule, { exact: true }).click();
  const scrollContainer = page.getByTestId("schedule-grid-scroll");
  await expect(scrollContainer).toBeVisible();

  const { scrollWidth, clientWidth } = await scrollContainer.evaluate((el) => ({
    scrollWidth: el.scrollWidth,
    clientWidth: el.clientWidth,
  }));
  expect(scrollWidth).toBeGreaterThan(clientWidth);

  const timeHeader = page.getByTestId("schedule-time-header");
  const beforeBox = await timeHeader.boundingBox();
  expect(beforeBox).not.toBeNull();

  await scrollContainer.evaluate((el) => {
    el.scrollLeft = el.scrollWidth;
  });
  await page.waitForFunction(() => {
    const el = document.querySelector('[data-testid="schedule-grid-scroll"]');
    return el instanceof HTMLElement && el.scrollLeft > 0;
  });

  const afterBox = await timeHeader.boundingBox();
  expect(afterBox).not.toBeNull();
  // Sticky (position: sticky; left: 0) - the header cell's on-screen x position barely moves even
  // though the container scrolled its full width.
  expect(Math.abs(afterBox!.x - beforeBox!.x)).toBeLessThan(2);
});
