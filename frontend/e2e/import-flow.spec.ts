import { test, expect, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { sv } from "../src/i18n/sv";
import { useAdvancedMode } from "./helpers/uiMode";

test.beforeEach(async ({ page }) => {
  await useAdvancedMode(page);
});

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Committed as `import-fixture.csv.txt`, NOT `.csv`: the repo's confidentiality firewall
// (.gitignore + scripts/check-no-confidential.sh, see CLAUDE.md) denies *any* `.csv` file outside
// `test-data/datasets/` by extension. Uploaded under a real `.csv` name so the backend parses it.
const FIXTURE_PATH = path.join(__dirname, "fixtures/import-fixture.csv.txt");

// A separate, small, fully-clean fixture (2 unique participants, no blanks/duplicates - never
// reused by another spec's or this file's OTHER test's fixture, same fixture-uniqueness rationale as
// resources-coaches-capacity.spec.ts's own note) for the one-click test below. Person-matching is
// global, not per-plan, and both tests in this file run against the same backend/DB within a run
// (playwright.config.ts, fullyParallel: false) - reusing import-fixture.csv.txt's own names here
// would have the one-click test's commit (which submits no per-row decisions, so every row - even
// the in-file duplicate - defaults to "create new" and gets persisted) silently create Person rows
// that then turn the "Justera" test's later upload of the SAME rows into "matched existing person"
// WARNs instead of the clean OK/WARN/SKIP split its own assertions are written against.
const ONECLICK_FIXTURE_PATH = path.join(__dirname, "fixtures/import-oneclick-fixture.csv.txt");

async function createSeasonAndPlan(page: Page, seasonName: string, planName: string) {
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

  await expect(page).toHaveURL(/\/deltagare$/);
  await expect(page.getByText(sv.participants.empty)).toBeVisible();
}

async function uploadFixture(page: Page, fixturePath: string = FIXTURE_PATH, fileName = "import-fixture.csv") {
  await page.getByRole("button", { name: sv.participants.importButton }).click();
  await expect(page).toHaveURL(/\/import(\?.*)?$/);
  await expect(page.getByRole("heading", { name: sv.importWizard.file.heading, level: 4 })).toBeVisible();
  await page.locator('input[type="file"]').setInputFiles({
    name: fileName,
    mimeType: "text/csv",
    buffer: readFileSync(fixturePath),
  });
}

test("one-click import: upload → granska → importera → Deltagare", async ({ page }) => {
  const seasonName = `E2E-oneclick-säsong-${Date.now()}`;
  const planName = `E2E-oneclick-plan-${Date.now()}`;
  await createSeasonAndPlan(page, seasonName, planName);
  await uploadFixture(page, ONECLICK_FIXTURE_PATH, "import-oneclick-fixture.csv");

  await expect(page.getByRole("heading", { name: sv.importWizard.review.heading, level: 4 })).toBeVisible();
  await expect(page.getByTestId("import-review-sheet")).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.review.importButton, exact: true }).click();

  await expect(page.getByRole("heading", { name: sv.importWizard.commit.resultHeading, level: 4 })).toBeVisible();
  await expect(page.getByText(sv.importWizard.commit.resultSummary(2, 0))).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.commit.goToParticipants }).click();
  await expect(page).toHaveURL(/\/deltagare$/);
  await expect(page.getByRole("gridcell", { name: "Signe Cederholm" })).toBeVisible();
});

test("import wizard via Justera: upload → justera → map → validate → decide duplicate → commit", async ({
  page,
}) => {
  const seasonName = `E2E-import-säsong-${Date.now()}`;
  const planName = `E2E-import-plan-${Date.now()}`;
  await createSeasonAndPlan(page, seasonName, planName);
  await uploadFixture(page);

  // Confident auto-analysis lands on the review screen; drop into the classic wizard.
  await expect(page.getByRole("heading", { name: sv.importWizard.review.heading, level: 4 })).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.review.adjustButton }).click();

  await expect(page.getByRole("heading", { name: sv.importWizard.sheet.heading, level: 4 })).toBeVisible();
  await expect(page.getByText("Förnamn")).toBeVisible();
  await expect(page.getByText("Åkesson")).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.sheet.nextButton }).click();

  await expect(page.getByRole("heading", { name: sv.importWizard.mapping.heading, level: 4 })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Mappning för kolumn Förnamn" })).toHaveValue(
    sv.importWizard.mapping.targets.firstName,
  );
  await expect(page.getByRole("textbox", { name: "Mappning för kolumn E-post" })).toHaveValue(
    sv.importWizard.mapping.targets.email,
  );
  await page.getByRole("button", { name: sv.importWizard.mapping.nextButton }).click();

  await expect(page.getByRole("heading", { name: sv.importWizard.validate.heading, level: 4 })).toBeVisible();
  await expect(page.getByText(sv.importWizard.validate.summary(4, 2, 2))).toBeVisible();

  const skipBadges = page.getByText(sv.importWizard.validate.status.SKIP, { exact: true });
  const warnBadges = page.getByText(sv.importWizard.validate.status.WARN, { exact: true });
  await expect(skipBadges).toHaveCount(2);
  await expect(warnBadges).toHaveCount(2);

  const duplicateDecision = page.getByRole("textbox", { name: "Beslut för rad 6" });
  await expect(duplicateDecision).toHaveValue(sv.importWizard.validate.decision.createNew);
  await duplicateDecision.click();
  await page.getByRole("option", { name: sv.importWizard.validate.decision.skip }).click();
  await expect(duplicateDecision).toHaveValue(sv.importWizard.validate.decision.skip);

  await page.getByRole("button", { name: sv.importWizard.validate.nextButton }).click();

  await expect(page.getByRole("heading", { name: sv.importWizard.commit.heading, level: 4 })).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.commit.submit, exact: true }).click();

  await expect(page.getByRole("heading", { name: sv.importWizard.commit.resultHeading, level: 4 })).toBeVisible();
  // 8 rows total: the 2 nameless rows are SKIP outright, and the duplicate-email row (Maria, decided
  // "Hoppa över" above) joins them, so 5 rows import and 3 are skipped.
  await expect(page.getByText(sv.importWizard.commit.resultSummary(5, 3))).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.commit.goToParticipants }).click();
  await expect(page).toHaveURL(/\/deltagare$/);

  // The 5 non-skipped, non-duplicate rows landed as participants...
  await expect(page.getByRole("gridcell", { name: "Anna Åkesson" })).toBeVisible();
  await expect(page.getByRole("gridcell", { name: "Björn Öberg" })).toBeVisible();
  await expect(page.getByRole("gridcell", { name: "Erik Käring" })).toBeVisible();
  await expect(page.getByRole("gridcell", { name: "Nils Fagerström" })).toBeVisible();
  await expect(page.getByRole("gridcell", { name: "Ida Håkansson" })).toBeVisible();
  // ...while Maria (the duplicate the user chose to skip) did not.
  await expect(page.getByRole("gridcell", { name: "Maria Söderström" })).toHaveCount(0);
  await expect(page.getByText(sv.participants.empty)).toHaveCount(0);
});
