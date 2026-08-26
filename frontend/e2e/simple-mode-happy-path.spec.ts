import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { sv } from "../src/i18n/sv";
import { finishImportAfterUpload } from "./helpers/finishImport";
import { useSimpleMode } from "./helpers/uiMode";

// package.json has "type": "module", so __dirname isn't available — derive it from import.meta.url
// (same pattern as the other e2e specs).
const __dirname = path.dirname(fileURLToPath(import.meta.url));

// 10 unique participants, never reused by another spec's fixture (see resources-coaches-capacity
// .spec.ts's own fixture-uniqueness note - specs in this suite share one backend/DB for the whole
// run, playwright.config.ts fullyParallel:false).
const FIXTURE_PATH = path.join(__dirname, "fixtures/simple-happy-fixture.csv.txt");

const SLOT1_LABEL = "Torsdag 18.00–19.30";
const SLOT2_LABEL = "Torsdag 19.30–21.00";
const SLOT3_LABEL = "Torsdag 21.00–22.30";

test.beforeEach(async ({ page }) => {
  // True SIMPLE mode from a fresh boot (not just "switched into" from ADVANCED, unlike
  // simple-mode-explain.spec.ts) - the whole point of this crown spec is proving the product
  // default flow works end-to-end for a user who never touches Avancerat läge. useSimpleMode already
  // resets the shared backend's durable value too (its own seedUiMode helper calls
  // resetServerUiMode internally) - v0.6.0 F6 review fix (FIX 6, MINOR): a second explicit call here
  // was redundant.
  await useSimpleMode(page);
});

/**
 * The crown spec (v0.6.0 F6, M-S6): a genuine SIMPLE-mode admin, start to finish, against the real
 * backend - create season/plan → the 3 seeded Thursday slots already there, untouched → import via
 * the one-click review path → the 6-step stepper (no 9-tab bar) → Tider (seeded slots + capacity
 * summary) → Prioriteringar (reorder + autosave + reload-persistence) → Skapa grupper (ONE click,
 * no profile picker) → Resultat (groups render, ZERO coach strings anywhere - this plan never had a
 * coach) → Spara & exportera (save a version, export to Excel, real browser download).
 *
 * Longer than this suite's other specs by design - a full product journey, not a single feature
 * slice - hence the extended test timeout (the "Skapa grupper" step is a real CUSTOM Timefold solve,
 * `useSuggestDuration`'s own formula, not the instant deterministic GREEDY other specs pick via
 * Avancerat läge specifically to dodge this cost - SIMPLE mode never exposes that picker at all, see
 * OptimizePanelSimple.tsx's own doc comment, so this spec has to actually wait it out).
 */
// CI runners are far slower than a dev laptop AND the suggested solve duration scales with
// measured hardware speed (SolveBenchmarkService), so the solve itself can legitimately take
// several minutes there - the 420s budget and the 300s outcome wait below are sized for that,
// not for local runs (locally the whole spec finishes in ~2 min).
test.setTimeout(420_000);

test("SIMPLE mode, start to finish: season/plan → import → 6-step flow → save & export", async ({ page }) => {
  const seasonName = `E2E-simple-happy-säsong-${Date.now()}`;
  const planName = `E2E-simple-happy-plan-${Date.now()}`;
  // Person matching is GLOBAL (not per-plan), so a Playwright retry re-importing the same fixture
  // names into the shared backend DB would turn every row into a "possible duplicate" WARN and
  // break the exact rowsSummary assertion. Suffixing every surname per attempt makes the spec
  // retry-idempotent (and parallel-safe against future specs reusing the fixture).
  const runSuffix = `${Date.now().toString(36)}`;
  const uniqueFixtureCsv = readFileSync(FIXTURE_PATH, "utf8")
    .split("\n")
    .map((line, i) => {
      if (i === 0 || line.trim() === "") return line;
      const cols = line.split(",");
      cols[1] = `${cols[1]}-${runSuffix}`;
      return cols.join(",");
    })
    .join("\n");
  const surname = (base: string) => `${base}-${runSuffix}`;

  await page.goto("/");

  // --- Create a season + activity plan via the UI ---
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

  const planUrl = page.url().replace(/\/deltagare$/, "");

  // --- B3 (v0.6.0) auto-seeds 3 default weekly Thursday slots on plan creation - assert they're
  //     already there BEFORE any user action (a plain navigation to Tider, not a deliberate setup
  //     step - the point is that the admin never had to create these). ---
  await page.goto(`${planUrl}/resurser`);
  const slotRows = page.locator('[data-testid="time-slot-row"]');
  await expect(slotRows).toHaveCount(3);
  await expect(page.getByText(SLOT1_LABEL)).toBeVisible();
  await expect(page.getByText(SLOT2_LABEL)).toBeVisible();
  await expect(page.getByText(SLOT3_LABEL)).toBeVisible();

  // --- Back to Deltagare: import the 10-participant fixture via the one-click review path ---
  await page.goto(`${planUrl}/deltagare`);
  await expect(page.getByTestId("plan-simple-stepper")).toBeVisible();
  // SIMPLE mode: the 6-step stepper, never the 9-tab bar.
  await expect(page.getByRole("tab")).toHaveCount(0);
  const SIMPLE_STEP_TESTIDS = [
    "plan-simple-step-deltagare",
    "plan-simple-step-tider",
    "plan-simple-step-prioriteringar",
    "plan-simple-step-optimera",
    "plan-simple-step-resultat",
    "plan-simple-step-exportera",
  ];
  for (const testId of SIMPLE_STEP_TESTIDS) {
    await expect(page.getByTestId(testId)).toBeVisible();
  }

  await page.getByRole("button", { name: sv.participants.importButton }).click();
  await expect(page).toHaveURL(/\/import(\?.*)?$/);
  await expect(page.getByRole("heading", { name: sv.importWizard.file.heading, level: 4 })).toBeVisible();
  await page.locator('input[type="file"]').setInputFiles({
    name: "simple-happy-fixture.csv",
    mimeType: "text/csv",
    buffer: Buffer.from(uniqueFixtureCsv, "utf8"),
  });
  await finishImportAfterUpload(page, { ok: 10, warn: 0, skip: 0 });
  await expect(page).toHaveURL(/\/deltagare$/);

  await expect(page.getByTestId("simple-participants-summary")).toContainText(
    sv.simple.participants.summary.total(10),
  );

  // --- Link a friend pair (Tuva Berglind <-> Noel Kvist) via the standard "Vill spela med" field,
  //     the same seeded structured field field-builder.spec.ts's own worked example uses. ---
  const gridRow = (name: string) => page.locator('[role="row"]').filter({ hasText: name });
  await gridRow(`Tuva ${surname("Berglind")}`).click();
  const tuvaDrawer = page.getByRole("dialog").filter({ hasText: `Tuva ${surname("Berglind")}` });
  await expect(tuvaDrawer).toBeVisible();
  await tuvaDrawer.getByRole("textbox", { name: "Vill spela med" }).click();
  await page.getByRole("option", { name: `Noel ${surname("Kvist")}` }).click();
  await tuvaDrawer.getByRole("button", { name: sv.participants.drawer.saveButton, exact: true }).click();
  await expect(page.getByText(sv.participants.drawer.saveSuccess).first()).toBeVisible();
  await tuvaDrawer.getByRole("button", { name: sv.participants.drawer.closeButton }).click();
  await expect(tuvaDrawer).toHaveCount(0);

  // --- Step 2, Tider: the same seeded slots, now with the capacity summary underneath - set 1
  //     court on the first slot so the plan is actually solvable. ---
  await page.getByTestId("simple-step-next").click();
  await expect(page).toHaveURL(/\/resurser$/);
  await expect(page.locator('[data-testid="time-slot-row"]')).toHaveCount(3);
  await expect(page.getByTestId("simple-capacity-summary")).toBeVisible();

  // v0.6.0 F4 (M-S4): the per-court "block-chip" toggles are ADVANCED-only (ResourcesPanel.tsx
  // wraps them in <AdvancedOnly> - a manual per-court exception is never touched from SIMPLE mode),
  // so this genuinely-SIMPLE spec confirms the court count committed via the NumberInput's own
  // value instead of a chip that doesn't render here at all.
  const slot1Row = page.locator('[data-testid="time-slot-row"]').filter({ hasText: SLOT1_LABEL });
  const courtsInput = slot1Row.getByLabel(sv.resources.courtsLabel);
  await courtsInput.fill("1");
  await courtsInput.blur();
  await expect(courtsInput).toHaveValue("1");
  // v0.6.0 F6 review fix (FIX 2, MAJOR): the coach-string sweep, extended to the Tider step too -
  // cheap (one assertion) now that the step's own content above is already confirmed rendered.
  await expect(page.getByText(/tränar/i)).toHaveCount(0);

  // --- Step 3, Prioriteringar: reorder via arrows, autosave, and confirm it survives a reload. ---
  await page.getByTestId("simple-step-next").click();
  await expect(page).toHaveURL(/\/prioriteringar$/);

  const priorityRows = page.getByTestId("priority-row");
  await expect(priorityRows).toHaveCount(4);
  const currentPriorityOrder = () =>
    priorityRows.evaluateAll((elements) => elements.map((el) => el.getAttribute("data-priority-key")));
  const originalOrder = await currentPriorityOrder();

  await priorityRows.nth(1).locator("button").nth(0).click();
  const expectedOrder = [originalOrder[1], originalOrder[0], originalOrder[2], originalOrder[3]];
  await expect.poll(currentPriorityOrder).toEqual(expectedOrder);
  await expect(page.getByTestId("priority-save-status")).toHaveText(sv.simple.priorities.saved, { timeout: 5_000 });

  await page.reload();
  await expect(page.getByTestId("priority-row")).toHaveCount(4);
  await expect.poll(currentPriorityOrder).toEqual(expectedOrder);

  // --- Step 4, Skapa grupper: ONE click - no profile picker, no manual duration entry. ---
  await page.getByTestId("simple-step-next").click();
  await expect(page).toHaveURL(/\/optimering$/);
  await expect(page.getByTestId("simple-optimize-readiness")).toBeVisible();

  await page.getByTestId("simple-optimize-button").click();
  // The CUSTOM solve's own duration (useSuggestDuration's formula, clamped [15, 600]s) - generous
  // window, this is the one genuinely slow step in the whole flow.
  await expect(page.getByTestId("simple-optimize-outcome")).toBeVisible({ timeout: 300_000 });
  await expect(page.getByTestId("simple-optimize-view-groups-button")).toBeVisible();
  // v0.6.0 F6 review fix (FIX 2, MAJOR): the coach-string sweep, extended to the Optimera step too.
  await expect(page.getByText(/tränar/i)).toHaveCount(0);
  await page.getByTestId("simple-optimize-view-groups-button").click();

  // --- Step 5, Resultat: groups render, and ZERO coach strings anywhere on the page - this plan
  //     never configured a coach, and SIMPLE mode must never mention coach assignment at all. ---
  await expect(page).toHaveURL(/\/resultat$/);
  await expect(page.getByRole("heading", { name: sv.results.heading })).toBeVisible();
  await expect(page.getByTestId("group-card").first()).toBeVisible();
  await expect(page.getByTestId("results-misplaced-hint")).toBeVisible();
  // Sanity check the sweep itself isn't vacuous - the misplaced-hint card and group cards above are
  // real, non-coach content already confirmed visible, proving getByText below is scanning a page
  // that's actually populated, not a blank/loading one.
  // v0.6.0 F6 review fix (FIX 2, MAJOR): widened from /tränare/i to /tränar/i - the reviewer verified
  // no non-coach /tränar/i string can render on these steps once FIX 1 lands ("{name} tränar i" is
  // spotlight-only; "Tränar själv" is the drawer).
  await expect(page.getByText(/tränar/i)).toHaveCount(0);

  // --- Step 6, Spara & exportera: save a version, then export to Excel (real browser download). ---
  await page.getByTestId("simple-step-next").click();
  await expect(page).toHaveURL(/\/export$/);
  await expect(page.getByTestId("simple-save-export-card")).toBeVisible();

  const nameInput = page.getByTestId("simple-save-name-input");
  await expect(nameInput).not.toHaveValue("");
  await page.getByTestId("simple-save-button").click();
  await expect(page.getByTestId("simple-save-success")).toContainText("Sparad ✓");

  await expect(page.getByTestId("export-empty-hint")).toHaveCount(0);
  const [exportDownload] = await Promise.all([
    page.waitForEvent("download"),
    page.getByTestId("simple-export-button").click(),
  ]);
  expect(exportDownload.suggestedFilename()).toMatch(/\.xlsx$/);
  // v0.6.0 F6 review fix (FIX 6, MINOR): also await the download's saved path (not just the
  // `download` event firing) - the event fires on download START, not completion, so this alone
  // proves the file actually finished writing to disk.
  expect(await exportDownload.path()).not.toBeNull();

  // No coach strings snuck in anywhere across the whole Spara & exportera step either.
  // v0.6.0 F6 review fix (FIX 2, MAJOR): widened from /tränare/i to /tränar/i - the reviewer verified
  // no non-coach /tränar/i string can render on these steps once FIX 1 lands ("{name} tränar i" is
  // spotlight-only; "Tränar själv" is the drawer).
  await expect(page.getByText(/tränar/i)).toHaveCount(0);
});
