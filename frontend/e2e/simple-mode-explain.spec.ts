import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { sv } from "../src/i18n/sv";
import { finishImportAfterUpload } from "./helpers/finishImport";
import { useAdvancedMode } from "./helpers/uiMode";

test.beforeEach(async ({ page }) => {
  await useAdvancedMode(page);
});

// package.json has "type": "module", so __dirname isn't available — derive it from import.meta.url
// (same pattern as the other e2e specs).
const __dirname = path.dirname(fileURLToPath(import.meta.url));

// 12 unique participants (never reused by another spec's fixture - see resources-coaches-capacity
// .spec.ts's own fixture-uniqueness note: specs in this suite share one backend/DB for the whole
// run, playwright.config.ts fullyParallel:false).
const FIXTURE_PATH = path.join(__dirname, "fixtures/simple-mode-explain-fixture.csv.txt");

const SLOT_LABEL = "Torsdag 18.00–19.30";
// v0.6.0 F5 review fix (minor, e2e fixture): a second active slot, alongside 12 participants
// (> GroupGenerator.FALLBACK_TARGET_SIZE=10), forces groupCount=2 - see whatif-explain.spec.ts's own
// identical fixture-sizing comment. A real second group is what turns Astrid's "Vill spela med Simon"
// wish (linked below, before solving) into a genuinely UNMET one: GREEDY's pure level-sort split
// (GreedyBaselineService#assignGroups, deterministic, wish-blind) always puts Astrid (Ranking 500,
// the median-ish top of the upper half) and Simon (Ranking 380, the bottom of the lower half) in
// opposite groups.
const SLOT2_LABEL = "Torsdag 19.30–21.00";

/**
 * v0.6.0 F5 (M-S5) frontend acceptance flow: seed and solve a plan the same way whatif-explain
 * .spec.ts does (import → link a friend wish that GREEDY's level-sort will break → resurser → generate
 * groups → GREEDY solve, deterministic/synchronous - the SIMPLE-mode "Optimera"-step form deliberately
 * never exposes a profile picker, see OptimizeRoute's own SIMPLE branch, so GREEDY has to be selected
 * in ADVANCED first), THEN switch to SIMPLE mode and exercise the SIMPLE-only Resultat surface: the
 * Ctrl/Cmd+F player search → explain drawer flow (PlayerSearchSpotlight's `?highlight=&forklara=`
 * navigation, ResultsPanel reading `forklara` on mount), and SimpleExplainBody's plain-language content
 * in place of the ADVANCED explain body - including (v0.6.0 F5 review fix) the populated "Önskemål som
 * inte kunde uppfyllas" row/accordion this spec's fixture previously never actually produced.
 *
 * Deliberately asserts STRUCTURE (headline testid, unmet-wish row/accordion presence, ADVANCED-only
 * "Tillämpade vikter" absence) rather than the exact prioritySensitivity classification/CTA - which
 * outcome (verdict/summarySv/cautionSv/bestCandidateGroupId) the REAL backend computes for this pair
 * isn't this spec's concern (SimpleExplainBody.test.tsx's own unit tests already cover every CTA
 * presence rule against controlled fixtures); this spec only needs to prove the wish reaches the UI
 * and the accordion never renders blank.
 */
test("GREEDY solve (ADVANCED) → switch to SIMPLE → Ctrl/Cmd+F → Enter → explain drawer opens, no ADVANCED-only content", async ({
  page,
}) => {
  const seasonName = `E2E-simple-explain-sasong-${Date.now()}`;
  const planName = `E2E-simple-explain-plan-${Date.now()}`;

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

  // --- Import 12 participants via the M3 wizard ---
  await page.getByRole("button", { name: sv.participants.importButton }).click();
  await expect(page).toHaveURL(/\/import(\?.*)?$/);
  await expect(page.getByRole("heading", { name: sv.importWizard.file.heading, level: 4 })).toBeVisible();
  await page.locator('input[type="file"]').setInputFiles({
    name: "simple-mode-explain-fixture.csv",
    mimeType: "text/csv",
    buffer: readFileSync(FIXTURE_PATH),
  });
  await finishImportAfterUpload(page, { ok: 12, warn: 0, skip: 0 });
  await expect(page).toHaveURL(/\/deltagare$/);

  // --- Link Astrid <-> Simon via the plan's seeded standard "Vill spela med" field (personRelation/
  // SameGroupSoft - see field-builder.spec.ts's own worked-example doc comment for the same field),
  // BEFORE generating/solving, so GREEDY's level-sort split (see SLOT2_LABEL's own doc comment above)
  // breaks it - this spec's one deliberately unmet wish. Directed Astrid -> Simon (not mutual) so the
  // wish is attributed to Astrid (CausalNarrator's `wishOwnedByTarget`), the participant this spec
  // already searches for and opens the explain drawer on below. ---
  const gridRow = (name: string) => page.locator('[role="row"]').filter({ hasText: name });
  await gridRow("Astrid Lund").click();
  const astridDrawer = page.getByRole("dialog").filter({ hasText: "Astrid Lund" });
  await expect(astridDrawer).toBeVisible();
  await astridDrawer.getByRole("textbox", { name: "Vill spela med" }).click();
  await page.getByRole("option", { name: "Simon Häll" }).click();
  await astridDrawer.getByRole("button", { name: sv.participants.drawer.saveButton, exact: true }).click();
  await expect(page.getByText(sv.participants.drawer.saveSuccess).first()).toBeVisible();
  await astridDrawer.getByRole("button", { name: sv.participants.drawer.closeButton }).click();
  await expect(astridDrawer).toHaveCount(0);

  // --- Resurser: B3 (v0.6.0) auto-seeds 3 default weekly Thursday slots on plan creation, including
  // both slots this spec needs (SLOT_LABEL/SLOT2_LABEL) - reuse them instead of creating duplicates
  // (409, TimeSlotController.requireNoDuplicate). 1 court each -> 2 active TrainingBlocks -> 2
  // generated groups (GroupGenerator clamps groupCount to the active-block count). ---
  await page.getByRole("tab", { name: sv.plan.tabs.resources }).click();
  const slotRow = page.locator('[data-testid="time-slot-row"]').filter({ hasText: SLOT_LABEL });
  const slot2Row = page.locator('[data-testid="time-slot-row"]').filter({ hasText: SLOT2_LABEL });
  await expect(slotRow).toHaveCount(1);
  await slotRow.getByLabel(sv.resources.courtsLabel).fill("1");
  await slotRow.getByLabel(sv.resources.courtsLabel).blur();
  await slot2Row.getByLabel(sv.resources.courtsLabel).fill("1");
  await slot2Row.getByLabel(sv.resources.courtsLabel).blur();
  await expect(slotRow.locator('[data-testid="block-chip"]')).toHaveCount(1);
  await expect(slot2Row.locator('[data-testid="block-chip"]')).toHaveCount(1);

  // --- Optimering (still ADVANCED): generate 2 groups, then GREEDY solve (synchronous, deterministic)
  await page.getByRole("tab", { name: sv.plan.tabs.optimize }).click();
  const groupsSummary = page.getByTestId("groups-summary");
  await page.getByRole("button", { name: sv.optimize.groups.generateButton }).click();
  await expect(groupsSummary.getByText(sv.optimize.groups.count(2))).toBeVisible();

  await page.getByTestId("advanced-toggle").click();
  await page.getByRole("radio", { name: sv.optimize.profiles.GREEDY.label }).click();
  await page.getByRole("button", { name: sv.optimize.startButton }).click();
  await expect(page.getByTestId("last-run-summary").getByTestId("last-run-score-line")).toBeVisible({ timeout: 10_000 });

  // --- Switch to SIMPLE via the navbar toggle - ADVANCED -> SIMPLE is friction-free (no confirm
  // modal, see UiModeSwitch's own doc comment; force:true for the same decorative-track-intercepts-
  // pointer-events reason ui-mode-toggle.spec.ts already documents). ---
  const uiModeSwitch = page.getByRole("switch", { name: sv.uiMode.switchAriaLabel });
  await expect(uiModeSwitch).toBeChecked();
  await uiModeSwitch.click({ force: true });
  await expect(uiModeSwitch).not.toBeChecked();

  // --- Resultat (SIMPLE): Ctrl/Cmd+F -> search -> Enter selects the (only) hit, which in SIMPLE
  // mode navigates with BOTH `?highlight=` and `?forklara=` (PlayerSearchSpotlight.tsx) - the latter
  // opens the explain drawer directly on arrival (ResultsPanel.tsx reads it on mount). ---
  const planUrl = page.url().replace(/\/optimering$/, "");
  await page.goto(`${planUrl}/resultat`);
  await expect(page.getByRole("heading", { name: sv.results.heading })).toBeVisible();
  await expect(page.getByTestId("results-misplaced-hint")).toBeVisible();

  await page.keyboard.press(process.platform === "darwin" ? "Meta+f" : "Control+f");
  const searchInput = page.getByPlaceholder(sv.playerSearch.placeholder);
  await expect(searchInput).toBeVisible();
  await searchInput.fill("lund");
  const hit = page.getByRole("button").filter({ hasText: "Lund" });
  await expect(hit).toHaveCount(1);
  await page.keyboard.press("Enter");

  await expect(page).toHaveURL(/\/resultat\?.*highlight=.*forklara=|\/resultat\?.*forklara=.*highlight=/);

  // Same pattern as whatif-explain.spec.ts: match the drawer by its dialog ROLE (the fixed-position
  // content panel), not the `data-testid="explain-drawer"` Drawer.Root wrapper - that wrapper's only
  // children are `position:fixed` (overlay + content), so it collapses to a zero-size box and fails
  // Playwright's bounding-box-based `toBeVisible()` even while genuinely open.
  const explainDrawer = page.getByRole("dialog", { name: sv.results.explain.title("Astrid Lund") });
  await expect(explainDrawer).toBeVisible();
  await expect(explainDrawer.getByTestId("explain-why-headline")).toBeVisible();
  await expect(explainDrawer.getByTestId("explain-why-headline")).toContainText("Astrid Lund");
  await expect(explainDrawer.getByTestId("explain-unmet-wishes")).toBeVisible();

  // --- v0.6.0 F5 review fix: the friend wish linked above is genuinely UNMET (GREEDY split Astrid
  // and Simon into different groups) - exactly ONE row, naming Simon, with a "Vad skulle krävas?"
  // accordion that never renders blank once expanded (FIX 3's own regression net) and offers no CTA
  // it can't back up with a live known-group/caution pairing (FIX 2/FIX 5's own gates). ---
  const wishRow = explainDrawer.getByTestId("explain-unmet-wish");
  await expect(wishRow).toHaveCount(1);
  await expect(wishRow).toContainText("Simon Häll");

  const accordionControl = wishRow.getByRole("button", { name: sv.results.explain.simple.whatWouldItTakeHeading });
  await expect(accordionControl).toBeVisible();
  const accordionPanel = wishRow.getByRole("region");
  // v0.6.0 audit-fix batch C (C14, P2): with exactly ONE unmet wish (this spec's fixture, asserted
  // above), the accordion now opens BY DEFAULT - no click needed. Asserting `aria-expanded` up front
  // proves that behavior directly, rather than a click silently masking whichever state it started in.
  await expect(accordionControl).toHaveAttribute("aria-expanded", "true");
  await expect(accordionPanel).toBeVisible();
  // Never blank (FIX 3) - some sentence (summary+caution, unavailableReasonSv, or the
  // sensitivityUnknown fallback) is always present once expanded.
  await expect(accordionPanel).not.toHaveText("");

  // FIX 5: if "Testa att flytta" is offered, it must point at a group that genuinely exists in this
  // run - clicking it opens WhatIfDialog prefilled to one of the two real group names.
  const testMoveButton = accordionPanel.getByRole("button", { name: sv.results.explain.simple.testMoveButton });
  if (await testMoveButton.count()) {
    await testMoveButton.click();
    const whatIfDialog = page.getByRole("dialog", { name: sv.results.whatIf.title("Astrid Lund") });
    await expect(whatIfDialog).toBeVisible();
    const prefilledTarget = whatIfDialog.getByTestId("whatif-target-select");
    await expect(prefilledTarget).not.toHaveValue("");
    await expect(prefilledTarget).toHaveValue(new RegExp(`^${planName} [12]$`));
    await whatIfDialog.getByRole("button", { name: sv.results.whatIf.actions.keep }).click();
    await expect(whatIfDialog).toHaveCount(0);
  }

  // The URL is stripped of `forklara` (but not `highlight`) once the drawer has opened, so a
  // subsequent back-navigation wouldn't reopen it.
  await expect(page).not.toHaveURL(/forklara=/);
  await expect(page).toHaveURL(/highlight=/);

  // ADVANCED-only content never appears in the SIMPLE explain body.
  await expect(page.getByText(sv.results.explain.appliedWeightsHeading)).toHaveCount(0);
  await expect(page.getByText(sv.results.explain.alternativesHeading)).toHaveCount(0);
  await expect(page.getByText(sv.results.explain.whyNotHeading)).toHaveCount(0);
});
