import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { sv } from "../src/i18n/sv";

// package.json has "type": "module", so __dirname isn't available — derive it from import.meta.url
// (same pattern as import-flow.spec.ts/playwright.config.ts).
const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Committed as `.csv.txt`, not `.csv` — see import-flow.spec.ts for why (confidentiality firewall
// denies any `.csv` file outside test-data/datasets/ by extension). Karin has a "Kommentar" so the
// Deltagarvy comment indicator / anonymize flow has something to exercise.
const FIXTURE_PATH = path.join(__dirname, "fixtures/field-builder-fixture.csv.txt");

/**
 * M4 acceptance flow (docs/plan.md M4 exit criterion + milestone brief): create the spec §9.1
 * worked-example custom field ("Vill spela med" / personRelation / SameGroupSoft / 80) in
 * Fältbyggaren, import participants, edit a participant's manual level score and confirm the
 * recompute-levels action updates the Deltagarvy grid, link two participants via the new custom
 * field from the detail drawer, then anonymize comments and confirm the comment indicator clears.
 */
test("Fältbyggare + Deltagarvy: create field → import → edit level → recompute → link via custom field → anonymize", async ({
  page,
}) => {
  const seasonName = `E2E-falt-sasong-${Date.now()}`;
  const planName = `E2E-falt-plan-${Date.now()}`;

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

  // --- Fältbyggaren: create "Vill spela med" / personRelation / SameGroupSoft / 80 (spec §9.1) ---
  // exact: true — Fältbyggaren has its own inner "Alla fält" sub-tab, whose label otherwise
  // substring-matches "Fält" (Playwright role-name matching is case-insensitive substring by default).
  await page.getByRole("tab", { name: sv.plan.tabs.fields, exact: true }).click();
  await expect(page.getByRole("heading", { name: sv.fieldBuilder.heading })).toBeVisible();

  await page.getByRole("button", { name: sv.fieldBuilder.newFieldButton }).click();
  const newFieldDialog = page.getByRole("dialog", { name: sv.fieldBuilder.newFieldModal.title });
  await newFieldDialog.getByLabel(sv.fieldBuilder.newFieldModal.labelLabel).fill("Vill spela med");

  // getByLabel would match 2 elements here (the input AND the listbox, which also carries
  // aria-labelledby pointing at the same label) - getByRole("textbox", ...) is unambiguous.
  await newFieldDialog.getByRole("textbox", { name: sv.fieldBuilder.newFieldModal.typeLabel }).click();
  await page.getByRole("option", { name: sv.fieldTypes.personRelation }).click();

  await newFieldDialog
    .getByRole("switch", { name: sv.fieldBuilder.newFieldModal.affectsOptimizationLabel })
    .click();

  // Defaults after enabling: first compatible constraint family (SAME_GROUP = "Samma grupp") and
  // hardOrSoft SOFT ("Mjuk") — exactly SameGroupSoft, matching the spec's worked example.
  await expect(newFieldDialog.getByRole("textbox", { name: sv.fieldBuilder.newFieldModal.constraintLabel })).toHaveValue(
    sv.constraintFamilies.SAME_GROUP,
  );
  await expect(newFieldDialog.getByRole("radio", { name: sv.hardOrSoft.SOFT })).toBeChecked();

  await newFieldDialog.getByLabel(sv.fieldBuilder.newFieldModal.weightLabel).fill("80");
  await newFieldDialog.getByRole("button", { name: sv.fieldBuilder.newFieldModal.submit }).click();
  await expect(newFieldDialog).toHaveCount(0);

  // The spec's own seed data already has a standard field labeled "Vill spela med" (key `playWith`,
  // same SameGroupSoft/80 configuration) — the new CUSTOM row is disambiguated by its "Anpassat"
  // badge (vs. the standard row's "Standard" badge).
  // Not `hasText: "Vill spela med"` — the custom row's label renders as an editable <input value="…">
  // (unlike the standard row's plain text), and an input's `value` is not part of its DOM
  // textContent, so a hasText filter on that phrase would only ever match the standard row. The
  // "Anpassat" badge (an actual text node) uniquely identifies the one custom field created here.
  const newFieldRow = page.locator("tr").filter({ hasText: sv.fieldBuilder.customBadge });
  await expect(newFieldRow).toHaveCount(1);
  await expect(newFieldRow.getByRole("radio", { name: sv.hardOrSoft.SOFT })).toBeChecked();

  // --- Import the fixture (reusing the M3 wizard flow) ---
  await page.getByRole("tab", { name: sv.plan.tabs.participants }).click();
  await page.getByRole("button", { name: sv.participants.importButton }).click();
  await expect(page).toHaveURL(/\/import(\?.*)?$/);

  await expect(page.getByRole("heading", { name: sv.importWizard.file.heading, level: 4 })).toBeVisible();
  await page.locator('input[type="file"]').setInputFiles({
    name: "field-builder-fixture.csv",
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

  const gridRow = (name: string) => page.locator('[role="row"]').filter({ hasText: name });

  for (const name of ["Karin Lindqvist", "Oskar Bergman", "Sara Ekström"]) {
    await expect(gridRow(name)).toHaveCount(1);
  }

  // Karin's imported comment shows as an indicator (not the text itself) in the grid.
  await expect(gridRow("Karin Lindqvist")).toContainText(sv.participants.columns.comment);

  // --- Deltagarvy drawer: edit Karin's manual level score ---
  await gridRow("Karin Lindqvist").click();
  const drawer = page.getByRole("dialog").filter({ hasText: "Karin Lindqvist" });
  await expect(drawer).toBeVisible();

  await drawer.getByLabel(sv.participants.drawer.manualLevelScoreLabel).fill("777");
  await drawer.getByRole("button", { name: sv.participants.drawer.saveButton }).click();
  await expect(page.getByText(sv.participants.drawer.saveSuccess).first()).toBeVisible();
  await drawer.getByRole("button", { name: sv.participants.drawer.closeButton }).click();
  await expect(drawer).toHaveCount(0);

  // --- Räkna om nivåer: the grid's Nivå column reflects Karin's new manualLevelScore ---
  await page.getByRole("button", { name: sv.participants.recomputeLevelsButton }).click();
  await expect(page.getByText(sv.participants.recomputeLevelsSuccess(3))).toBeVisible();
  await expect(gridRow("Karin Lindqvist")).toContainText("777");
  await expect(gridRow("Karin Lindqvist")).toContainText(sv.participants.levelConfidence.high);

  // --- Link Oskar <-> Karin via the new "Vill spela med" custom field ---
  await gridRow("Oskar Bergman").click();
  const oskarDrawer = page.getByRole("dialog").filter({ hasText: "Oskar Bergman" });
  await expect(oskarDrawer).toBeVisible();

  // Both the pre-existing standard field and the new custom field share the label "Vill spela med"
  // (spec's own worked example duplicates the seeded standard field) — the custom field's control
  // renders last (higher sort_order, appended after every standard field).
  const playWithControls = oskarDrawer.getByRole("textbox", { name: "Vill spela med" });
  await expect(playWithControls).toHaveCount(2);
  await playWithControls.last().click();
  await page.getByRole("option", { name: "Karin Lindqvist" }).click();
  await oskarDrawer.getByRole("button", { name: sv.participants.drawer.saveButton }).click();
  await expect(page.getByText(sv.participants.drawer.saveSuccess).first()).toBeVisible();
  await oskarDrawer.getByRole("button", { name: sv.participants.drawer.closeButton }).click();
  await expect(oskarDrawer).toHaveCount(0);

  // --- Anonymize comments: the indicator disappears from the grid ---
  await page.getByRole("button", { name: sv.participants.anonymizeButton }).click();
  const anonymizeDialog = page.getByRole("dialog", { name: sv.participants.anonymizeModal.title });
  await anonymizeDialog.getByRole("button", { name: sv.participants.anonymizeModal.confirm }).click();
  await expect(page.getByText(sv.participants.anonymizeModal.success(1))).toBeVisible();

  await expect(gridRow("Karin Lindqvist")).not.toContainText(sv.participants.columns.comment);
});
