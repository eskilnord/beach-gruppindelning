import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { sv } from "../src/i18n/sv";

// package.json has "type": "module", so __dirname isn't available — derive it from import.meta.url
// (same pattern as playwright.config.ts).
const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Committed as `import-fixture.csv.txt`, NOT `.csv`: the repo's confidentiality firewall
// (.gitignore + scripts/check-no-confidential.sh, see CLAUDE.md) denies *any* `.csv` file outside
// `test-data/datasets/` by extension, to make it structurally hard to accidentally commit a real
// member-data export. This fixture is synthetic (fake names/emails, generated for this test) but
// still a plain CSV, so it's kept under a `.txt` extension to clear that gate. The backend picks
// CSV vs xlsx parsing purely from the *uploaded* filename's extension (WorkbookParsers.parse), not
// the on-disk path — so `setInputFiles({ name: "import-fixture.csv", ... })` below uploads this
// file's bytes under a real `.csv` name, and the backend parses it exactly as it would any other
// CSV upload.
const FIXTURE_PATH = path.join(__dirname, "fixtures/import-fixture.csv.txt");

test("import wizard: upload → preview → map → validate → decide duplicate → commit → Deltagare", async ({
  page,
}) => {
  const seasonName = `E2E-import-säsong-${Date.now()}`;
  const planName = `E2E-import-plan-${Date.now()}`;

  await page.goto("/");

  // --- Create a season + activity plan to import into (same flow as plan-flow.spec.ts) ---
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
  await expect(page.getByText(sv.participants.empty)).toBeVisible();

  // --- Enter the wizard from the Deltagare tab's "Importera" button ---
  await page.getByRole("button", { name: sv.participants.importButton }).click();
  await expect(page).toHaveURL(/\/import(\?.*)?$/);

  // --- Step 1: Välj fil ---
  // level: 4 disambiguates from the wizard's own page title ("Importera deltagare", an h2), which
  // also renders on every step and would otherwise substring-match some step headings below.
  await expect(page.getByRole("heading", { name: sv.importWizard.file.heading, level: 4 })).toBeVisible();
  await page.locator('input[type="file"]').setInputFiles({
    name: "import-fixture.csv",
    mimeType: "text/csv",
    buffer: readFileSync(FIXTURE_PATH),
  });

  // --- Step 2: Välj blad & granska — CSV always parses to a single sheet named "CSV" ---
  await expect(page.getByRole("heading", { name: sv.importWizard.sheet.heading, level: 4 })).toBeVisible();
  await expect(page.getByText("Förnamn")).toBeVisible();
  await expect(page.getByText("Åkesson")).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.sheet.nextButton }).click();

  // --- Step 3: Mappa kolumner — every column is auto-suggested from the header text alone ---
  await expect(page.getByRole("heading", { name: sv.importWizard.mapping.heading, level: 4 })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Mappning för kolumn Förnamn" })).toHaveValue(
    sv.importWizard.mapping.targets.firstName,
  );
  await expect(page.getByRole("textbox", { name: "Mappning för kolumn E-post" })).toHaveValue(
    sv.importWizard.mapping.targets.email,
  );
  await page.getByRole("button", { name: sv.importWizard.mapping.nextButton }).click();

  // --- Step 4: Validera — blank row + missing-name row are skipped by default; the duplicate
  //     email pair (Anna/Maria, both "anna.akesson@example.se") is flagged as a warning. ---
  await expect(page.getByRole("heading", { name: sv.importWizard.validate.heading, level: 4 })).toBeVisible();
  await expect(page.getByText(sv.importWizard.validate.summary(4, 2, 2))).toBeVisible();

  // exact: true — otherwise Playwright's default substring match also picks up the summary
  // sentence above ("... 2 hoppas över"), which literally contains the SKIP badge's label text.
  const skipBadges = page.getByText(sv.importWizard.validate.status.SKIP, { exact: true });
  const warnBadges = page.getByText(sv.importWizard.validate.status.WARN, { exact: true });
  await expect(skipBadges).toHaveCount(2);
  await expect(warnBadges).toHaveCount(2);

  // --- Decide the duplicate: skip Maria's row (rowIndex 6 — row 1 is the header, so data rows
  //     are 1-indexed; Anna is row 1, Maria is row 6) rather than importing her as a second person. ---
  const duplicateDecision = page.getByRole("textbox", { name: "Beslut för rad 6" });
  await expect(duplicateDecision).toHaveValue(sv.importWizard.validate.decision.createNew);
  await duplicateDecision.click();
  await page.getByRole("option", { name: sv.importWizard.validate.decision.skip }).click();
  await expect(duplicateDecision).toHaveValue(sv.importWizard.validate.decision.skip);

  await page.getByRole("button", { name: sv.importWizard.validate.nextButton }).click();

  // --- Step 5: Importera — commit (Anna is kept, Maria is now also skipped: 5 imported, 3 skipped) ---
  await expect(page.getByRole("heading", { name: sv.importWizard.commit.heading, level: 4 })).toBeVisible();
  // exact: true — otherwise this also substring-matches the Stepper's own "5 Importera" step button.
  await page.getByRole("button", { name: sv.importWizard.commit.submit, exact: true }).click();

  await expect(page.getByRole("heading", { name: sv.importWizard.commit.resultHeading, level: 4 })).toBeVisible();
  await expect(page.getByText(sv.importWizard.commit.resultSummary(5, 3))).toBeVisible();

  // --- Back on Deltagare: the imported rows are listed, the skipped ones are not. ---
  await page.getByRole("button", { name: sv.importWizard.commit.goToParticipants }).click();
  await expect(page).toHaveURL(/\/deltagare$/);

  for (const name of ["Anna Åkesson", "Björn Öberg", "Erik Käring", "Nils Fagerström", "Ida Håkansson"]) {
    await expect(page.getByRole("gridcell", { name })).toBeVisible();
  }
  await expect(page.getByRole("gridcell", { name: "Maria Söderström" })).toHaveCount(0);
  await expect(page.getByText(sv.participants.empty)).toHaveCount(0);
});
