import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { sv } from "../src/i18n/sv";

// package.json has "type": "module", so __dirname isn't available — derive it from import.meta.url
// (same pattern as the other e2e specs).
const __dirname = path.dirname(fileURLToPath(import.meta.url));

// A dedicated fixture (3 synthetic participants: Lena Holmberg, Peter Vikström, Sofia Dahlgren) -
// purely to get a non-zero participant count for the Kapacitetsvy headline, via the same
// upload-through-the-wizard flow as field-builder.spec.ts/import-flow.spec.ts (the M3 wizard).
// Deliberately NOT the field-builder fixture: person-matching (spec §8.6) is global, not per-plan,
// and specs in this suite share one backend/DB for the whole run (playwright.config.ts,
// fullyParallel: false) - reusing the same names/emails as another spec's fixture would make this
// test's row turn into a WARN "matched existing person" instead of a clean OK once that other spec
// has already run and created those Person rows.
const FIXTURE_PATH = path.join(__dirname, "fixtures/resources-fixture.csv.txt");

const SLOT1_LABEL = "Torsdag 18.00–19.30";
const SLOT2_LABEL = "Torsdag 19.30–21.00";

/**
 * M5 frontend acceptance flow (milestone brief): Resursvy (§19.6) - create two time slots matching
 * spec §12.1's own worked example, declare 4 + 3 "Antal banor" so 7 TrainingBlocks appear, then
 * deactivate one block as a §12.3 manual exception. Tränarvy (§19.7) - link a coach to an already-
 * imported participant (§13.2 "tränare som spelare") and mark one time slot "Föredrar" in the
 * tri-state availability matrix. Kapacitetsanalysvy (§19.8) - the headline numbers and per-slot
 * breakdown table reflect all of the above.
 */
test("Resurser (tider+banor+block) → Tränare (tillgänglighet) → Kapacitet (dashboard reflects state)", async ({
  page,
}) => {
  const seasonName = `E2E-resurser-sasong-${Date.now()}`;
  const planName = `E2E-resurser-plan-${Date.now()}`;

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

  // --- Import 3 participants (reusing the M3 wizard flow, see FIXTURE_PATH note above) ---
  await page.getByRole("button", { name: sv.participants.importButton }).click();
  await expect(page).toHaveURL(/\/import(\?.*)?$/);
  await expect(page.getByRole("heading", { name: sv.importWizard.file.heading, level: 4 })).toBeVisible();
  await page.locator('input[type="file"]').setInputFiles({
    name: "resources-fixture.csv",
    mimeType: "text/csv",
    buffer: readFileSync(FIXTURE_PATH),
  });
  await expect(page.getByRole("heading", { name: sv.importWizard.sheet.heading, level: 4 })).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.sheet.nextButton }).click();
  await expect(page.getByRole("heading", { name: sv.importWizard.mapping.heading, level: 4 })).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.mapping.nextButton }).click();
  await expect(page.getByRole("heading", { name: sv.importWizard.validate.heading, level: 4 })).toBeVisible();
  await expect(page.getByText(sv.importWizard.validate.summary(3, 0, 0))).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.validate.nextButton }).click();
  await expect(page.getByRole("heading", { name: sv.importWizard.commit.heading, level: 4 })).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.commit.submit, exact: true }).click();
  await expect(page.getByRole("heading", { name: sv.importWizard.commit.resultHeading, level: 4 })).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.commit.goToParticipants }).click();
  await expect(page).toHaveURL(/\/deltagare$/);

  // --- Resurser: two time slots, spec §12.1's own worked example ---
  await page.getByRole("tab", { name: sv.plan.tabs.resources }).click();
  await expect(page.getByRole("heading", { name: sv.resources.heading })).toBeVisible();
  await expect(page.getByText(sv.resources.empty)).toBeVisible();

  const createSlot = async (start: string, end: string) => {
    await page.getByRole("button", { name: sv.resources.newSlotButton }).click();
    const dialog = page.getByRole("dialog", { name: sv.resources.slotModal.createTitle });
    await dialog.getByRole("textbox", { name: sv.resources.slotModal.dayLabel }).click();
    await page.getByRole("option", { name: sv.days.THURSDAY }).click();
    await dialog.getByLabel(sv.resources.slotModal.startTimeLabel).fill(start);
    await dialog.getByLabel(sv.resources.slotModal.endTimeLabel).fill(end);
    await dialog.getByRole("button", { name: sv.resources.slotModal.submit }).click();
    await expect(dialog).toHaveCount(0);
  };

  await createSlot("18:00", "19:30");
  await createSlot("19:30", "21:00");

  const slot1Row = page.locator('[data-testid="time-slot-row"]').filter({ hasText: SLOT1_LABEL });
  const slot2Row = page.locator('[data-testid="time-slot-row"]').filter({ hasText: SLOT2_LABEL });
  await expect(slot1Row).toHaveCount(1);
  await expect(slot2Row).toHaveCount(1);

  // --- 4 + 3 courts -> 7 TrainingBlocks appear (spec §12.2) ---
  await slot1Row.getByLabel(sv.resources.courtsLabel).fill("4");
  await slot1Row.getByLabel(sv.resources.courtsLabel).blur();
  await expect(slot1Row.locator('[data-testid="block-chip"]')).toHaveCount(4);

  await slot2Row.getByLabel(sv.resources.courtsLabel).fill("3");
  await slot2Row.getByLabel(sv.resources.courtsLabel).blur();
  await expect(slot2Row.locator('[data-testid="block-chip"]')).toHaveCount(3);

  await expect(page.locator('[data-testid="block-chip"]')).toHaveCount(7);

  // --- Deactivate one block: manual exception (spec §12.3) ---
  // force: true - Mantine's Switch track/trackLabel overlay sits visually on top of the native
  // input at this compact chip size, which fails Playwright's "receives pointer events" actionability
  // check even though a real click there still toggles the switch (native <label> semantics: any
  // click within the label - including on the overlaying track span - delegates to the input).
  await slot1Row.getByRole("switch", { name: "Bana 2 aktiv" }).click({ force: true });
  await expect(slot1Row.getByText(sv.resources.inactiveBadge)).toBeVisible();
  // Still 4 chips (never deleted), just one muted/inactive now.
  await expect(slot1Row.locator('[data-testid="block-chip"]')).toHaveCount(4);
  await expect(slot1Row).toContainText(sv.resources.blocksCount(3));

  // --- Tränare: link a coach to Lena (already imported - spec §13.2 "tränare som spelare") ---
  await page.getByRole("tab", { name: sv.plan.tabs.coaches }).click();
  await expect(page.getByRole("heading", { name: sv.coaches.heading })).toBeVisible();
  await expect(page.getByText(sv.coaches.empty)).toBeVisible();

  await page.getByRole("button", { name: sv.coaches.newCoachButton }).click();
  const coachDialog = page.getByRole("dialog", { name: sv.coaches.newCoachModal.title });
  const personInput = coachDialog.getByRole("textbox", { name: sv.coaches.newCoachModal.personLabel });
  await personInput.click();
  await personInput.fill("Lena");
  await page.getByRole("option", { name: "Lena Holmberg" }).click();
  await coachDialog.getByRole("button", { name: sv.coaches.newCoachModal.submit }).click();
  await expect(coachDialog).toHaveCount(0);

  const coachTableRow = page.getByRole("row").filter({ hasText: "Lena Holmberg" });
  await expect(coachTableRow).toHaveCount(1);
  await coachTableRow.click();

  const coachDrawer = page.getByRole("dialog").filter({ hasText: "Lena Holmberg" });
  await expect(coachDrawer).toBeVisible();

  // --- Mark Torsdag 18.00–19.30 "Föredrar" in the tri-state availability matrix (spec §13.1) ---
  // Mantine's SegmentedControl visually hides the native radio input (0 size/opacity) behind its
  // <label> - clicking the label's visible text, same as a real user would, is what actually toggles
  // it; the radio's own role/name is only used above to assert state (see riskBanner-style precedent
  // in field-builder.spec.ts, which only ever asserts .toBeChecked() on a segmented-control radio,
  // never clicks it directly).
  const slot1AvailabilityRow = coachDrawer.getByRole("group", { name: SLOT1_LABEL });
  await slot1AvailabilityRow.getByText(sv.coaches.availabilityKind.PREFERRED, { exact: true }).click();
  await expect(slot1AvailabilityRow.getByRole("radio", { name: sv.coaches.availabilityKind.PREFERRED })).toBeChecked();
  await coachDrawer.getByRole("button", { name: sv.coaches.drawer.saveButton }).click();
  await expect(page.getByText(sv.coaches.drawer.saveSuccess)).toBeVisible();
  await coachDrawer.getByRole("button", { name: sv.coaches.drawer.closeButton }).click();
  await expect(coachDrawer).toHaveCount(0);

  // --- Kapacitet: headline numbers + per-slot breakdown reflect everything above ---
  await page.getByRole("tab", { name: sv.plan.tabs.capacity }).click();
  // exact: true - otherwise this also substring-matches the panel's own "Tränarkapacitet" subheading
  // (Playwright role-name matching is case-insensitive substring by default).
  await expect(page.getByRole("heading", { name: sv.capacity.heading, exact: true })).toBeVisible();

  const headlineValue = (label: string) => page.locator('[data-testid="headline-card"]').filter({ hasText: label });

  // 3 imported participants, none waitlisted.
  await expect(headlineValue(sv.capacity.headline.participants)).toContainText("3");
  // 4 + 3 blocks, minus the 1 manually deactivated -> 6 active.
  await expect(headlineValue(sv.capacity.headline.activeBlocks)).toContainText("6");
  // No defaultGroupTargetSize/MaxSize configured on this plan (M2 scope) -> not computable.
  await expect(headlineValue(sv.capacity.headline.targetCapacity)).toContainText("—");
  await expect(headlineValue(sv.capacity.headline.maxCapacity)).toContainText("—");

  // exact: true - the banner *message* ends with "...kapacitet kan inte beräknas", which otherwise
  // case-insensitive-substring-matches the risk *title* text ("Kan inte beräknas") too.
  await expect(page.getByText(sv.capacity.risk.unknown, { exact: true })).toBeVisible();
  await expect(
    page.getByText("Standardstorlekar (target/max) saknas för planen - kapacitet kan inte beräknas"),
  ).toBeVisible();

  // 1 coach, groupsRequiringCoachEstimate == activeTrainingBlockCount (MVP estimate: 1 coach/block).
  const coachStat = (label: string) => page.locator('[data-testid="coach-stat"]').filter({ hasText: label });
  await expect(coachStat(sv.capacity.coachSection.coachCount)).toContainText("1");
  await expect(coachStat(sv.capacity.coachSection.groupsRequiringCoach)).toContainText("6");

  // Coach shortage: only 1 coach total, but each slot needs 3 (its active block count) -> both slots
  // are deficient (1 available < 3) -> risk banner, naming both slots. Matched on a message-only
  // substring ("för få tillgängliga tränare vid:") since the banner *title* is also literally "Risk
  // för tränarbrist" and would otherwise make the text search ambiguous.
  // exact: true - same title-vs-message ambiguity as the waitlist risk banner above ("Risk för
  // tränarbrist" is both the title and the message's own leading substring).
  await expect(page.getByText(sv.capacity.coachShortage.risk, { exact: true })).toBeVisible();
  const shortageMessage = page.getByText("för få tillgängliga tränare vid:", { exact: false });
  await expect(shortageMessage).toContainText(SLOT1_LABEL);
  await expect(shortageMessage).toContainText(SLOT2_LABEL);

  // Per-slot breakdown table: "tillgängliga tränare" counts everyone NOT marked UNAVAILABLE at that
  // slot (neutral/unlisted counts as available - backend CapacityService javadoc), so both slots show
  // 1 (the coach has no UNAVAILABLE entries anywhere); "varav föredrar" is the PREFERRED subset, which
  // does distinguish them: 1 at slot 1 (marked Föredrar above), 0 at slot 2 (never touched).
  const perSlotRow1 = page.getByRole("row").filter({ hasText: SLOT1_LABEL });
  await expect(perSlotRow1.locator("td").nth(1)).toHaveText("3");
  await expect(perSlotRow1.locator("td").nth(2)).toHaveText("1");
  await expect(perSlotRow1.locator("td").nth(3)).toHaveText("1");
  await expect(perSlotRow1.getByText(sv.capacity.perSlotTable.deficient)).toBeVisible();

  const perSlotRow2 = page.getByRole("row").filter({ hasText: SLOT2_LABEL });
  await expect(perSlotRow2.locator("td").nth(1)).toHaveText("3");
  await expect(perSlotRow2.locator("td").nth(2)).toHaveText("1");
  await expect(perSlotRow2.locator("td").nth(3)).toHaveText("0");
  await expect(perSlotRow2.getByText(sv.capacity.perSlotTable.deficient)).toBeVisible();
});
