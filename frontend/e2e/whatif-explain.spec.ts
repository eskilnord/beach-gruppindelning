import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { sv } from "../src/i18n/sv";

// package.json has "type": "module", so __dirname isn't available — derive it from import.meta.url
// (same pattern as the other e2e specs).
const __dirname = path.dirname(fileURLToPath(import.meta.url));

// 12 unique participants (never reused by another spec's fixture - see resources-coaches-capacity
// .spec.ts's own fixture-uniqueness note: specs in this suite share one backend/DB for the whole
// run, playwright.config.ts fullyParallel:false). Headcount matters here: GroupGenerator.
// FALLBACK_TARGET_SIZE is 10, so >10 active participants combined with 2 active TrainingBlocks below
// forces groupCount=2 (design §7's clamp(ceil(P/T),1,B)) - this spec needs a genuine second group to
// probe "varför inte grupp X" against and to have a real what-if move target.
const FIXTURE_PATH = path.join(__dirname, "fixtures/explain-whatif-fixture.csv.txt");

const SLOT1_LABEL = "Torsdag 18.00–19.30";
const SLOT2_LABEL = "Torsdag 19.30–21.00";

/**
 * M7 frontend acceptance flow (milestone brief): seed a plan with 2 real groups (import 12 → resurser
 * → generate groups → GREEDY solve, deterministic/synchronous - splits players evenly across groups,
 * see GreedyBaselineService#assignGroups) then exercise the explain/what-if surface end to end:
 * plan-level "Analys", group-level "Förklara grupp", person-level explain drawer (selected group +
 * factors + alternatives, plus an ad-hoc "Varför inte...?" probe against the other group), the
 * what-if consequence dialog, a safe manual move (moving 1 of 6 players out of an evenly-split group
 * of 6 can never breach a max size of 12 - see GroupGenerator's target+2 fallback), the resulting
 * "manuell" source badge, the staleness banner flipping on for the just-mutated run, and a re-solve
 * clearing it again.
 *
 * Deliberately asserts STRUCTURE (headings/sections/badges/at-least-one-item render) rather than
 * specific group compositions or exact factor text - GREEDY's precise level-banded split isn't
 * asserted, only that two real groups exist and the explain/what-if surface responds to them.
 */
test("Optimering (GREEDY, 2 grupper) → Analys/Förklara grupp/Förklara/Testa flytt → manuell flytt → staleness → re-solve rensar", async ({
  page,
}) => {
  const seasonName = `E2E-explain-sasong-${Date.now()}`;
  const planName = `E2E-explain-plan-${Date.now()}`;

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
    name: "explain-whatif-fixture.csv",
    mimeType: "text/csv",
    buffer: readFileSync(FIXTURE_PATH),
  });
  await expect(page.getByRole("heading", { name: sv.importWizard.sheet.heading, level: 4 })).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.sheet.nextButton }).click();
  await expect(page.getByRole("heading", { name: sv.importWizard.mapping.heading, level: 4 })).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.mapping.nextButton }).click();
  await expect(page.getByRole("heading", { name: sv.importWizard.validate.heading, level: 4 })).toBeVisible();
  await expect(page.getByText(sv.importWizard.validate.summary(12, 0, 0))).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.validate.nextButton }).click();
  await expect(page.getByRole("heading", { name: sv.importWizard.commit.heading, level: 4 })).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.commit.submit, exact: true }).click();
  await expect(page.getByRole("heading", { name: sv.importWizard.commit.resultHeading, level: 4 })).toBeVisible();
  await page.getByRole("button", { name: sv.importWizard.commit.goToParticipants }).click();
  await expect(page).toHaveURL(/\/deltagare$/);

  // --- Resurser: two time slots, 1 court each -> 2 active TrainingBlocks -> 2 generated groups ---
  await page.getByRole("tab", { name: sv.plan.tabs.resources }).click();

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
  await slot1Row.getByLabel(sv.resources.courtsLabel).fill("1");
  await slot1Row.getByLabel(sv.resources.courtsLabel).blur();
  await slot2Row.getByLabel(sv.resources.courtsLabel).fill("1");
  await slot2Row.getByLabel(sv.resources.courtsLabel).blur();
  await expect(slot1Row.locator('[data-testid="block-chip"]')).toHaveCount(1);
  await expect(slot2Row.locator('[data-testid="block-chip"]')).toHaveCount(1);

  // --- Optimering: generate 2 groups, then GREEDY solve (synchronous, deterministic) ---
  await page.getByRole("tab", { name: sv.plan.tabs.optimize }).click();
  const groupsSummary = page.getByTestId("groups-summary");
  await page.getByRole("button", { name: sv.optimize.groups.generateButton }).click();
  await expect(groupsSummary.getByText(sv.optimize.groups.count(2))).toBeVisible();

  await page.getByRole("radio", { name: sv.optimize.profiles.GREEDY.label }).click();
  await page.getByRole("button", { name: sv.optimize.startButton }).click();
  const lastRunCard = page.getByTestId("last-run-summary");
  await expect(lastRunCard.getByTestId("last-run-score-line")).toBeVisible({ timeout: 10_000 });

  // --- Plan-level "Analys" (kravspec §17.1 Planeringsnivå) - expand, check structural presence ---
  await lastRunCard.getByRole("button", { name: sv.optimize.analysis.heading }).click();
  const analysisSection = lastRunCard.getByTestId("plan-analysis-section");
  await expect(analysisSection).toBeVisible();
  await expect(analysisSection.getByText(sv.optimize.analysis.constraintSummariesHeading)).toBeVisible();
  await expect(analysisSection.getByTestId("plan-analysis-hard-violations")).toBeVisible();
  await expect(analysisSection.getByTestId("plan-analysis-waitlist")).toBeVisible();
  await expect(analysisSection.getByTestId("plan-analysis-problematic-groups")).toBeVisible();

  // --- Resultatvy: 2 real groups (6 players each, GREEDY's even split) ---
  await page.getByRole("tab", { name: sv.plan.tabs.results }).click();
  await expect(page.getByRole("heading", { name: sv.results.heading })).toBeVisible();
  await expect(page.getByTestId("explain-based-on")).toBeVisible();
  const groupCards = page.getByTestId("group-card");
  await expect(groupCards).toHaveCount(2);

  const group1Name = `${planName} 1`;
  const group2Name = `${planName} 2`;
  const group1Card = groupCards.filter({ hasText: group1Name });
  const group2Card = groupCards.filter({ hasText: group2Name });
  await expect(group1Card).toHaveCount(1);
  await expect(group2Card).toHaveCount(1);

  // --- Group-level "Förklara grupp" (kravspec §17.1 Gruppnivå) ---
  await group1Card.getByRole("button", { name: sv.results.groupCard.explainGroupButton, exact: true }).click();
  const groupExplainDrawer = page.getByRole("dialog", { name: sv.results.groupExplain.title(group1Name) });
  await expect(groupExplainDrawer).toBeVisible();
  await expect(groupExplainDrawer.getByTestId("group-explain-warnings")).toBeVisible();
  await expect(groupExplainDrawer.getByTestId("group-explain-matches")).toBeVisible();
  await expect(groupExplainDrawer.getByTestId("group-explain-broken-wish-members")).toBeVisible();
  await groupExplainDrawer.getByRole("button", { name: sv.common.close }).click();
  await expect(groupExplainDrawer).toHaveCount(0);

  // --- Person-level explain drawer for the (alphabetically) first member of group 1 ---
  const memberRow = group1Card.getByRole("row").first();
  const memberName = (await memberRow.locator("td").first().innerText()).trim();

  await memberRow.getByRole("button", { name: sv.results.groupCard.explainButton, exact: true }).click();
  const explainDrawer = page.getByRole("dialog", { name: sv.results.explain.title(memberName) });
  await expect(explainDrawer).toBeVisible();
  await expect(explainDrawer.getByTestId("explain-selected-group")).toBeVisible();
  await expect(explainDrawer.getByTestId("explain-positive-factor").first()).toBeVisible();
  await expect(explainDrawer.getByTestId("explain-alternatives").getByTestId("alternative-card").first()).toBeVisible();
  // Nothing has mutated the plan since this run was solved - not stale yet.
  await expect(explainDrawer.getByTestId("explain-stale-banner")).toHaveCount(0);

  // --- "Varför inte...?" why-not picker on the other (arbitrary) group ---
  await explainDrawer.getByRole("textbox", { name: sv.results.explain.whyNotHeading }).click();
  await page.getByRole("option", { name: group2Name }).click();
  await explainDrawer.getByRole("button", { name: sv.results.explain.whyNotButton }).click();
  await expect(explainDrawer.getByTestId("explain-why-not").getByTestId("alternative-card")).toBeVisible();

  await explainDrawer.getByRole("button", { name: sv.common.close }).click();
  await expect(explainDrawer).toHaveCount(0);

  // --- What-if dialog: probe moving the same member to group 2 ---
  await memberRow.getByRole("button", { name: sv.results.groupCard.testMoveButton, exact: true }).click();
  const whatIfDialog = page.getByRole("dialog", { name: sv.results.whatIf.title(memberName) });
  await expect(whatIfDialog).toBeVisible();
  await whatIfDialog.getByRole("textbox", { name: sv.results.whatIf.targetGroupLabel }).click();
  await page.getByRole("option", { name: group2Name }).click();

  await expect(whatIfDialog.getByTestId("whatif-consequence")).toBeVisible();
  await expect(whatIfDialog.getByText(sv.results.whatIf.scoreDeltaLabel)).toBeVisible();
  await expect(whatIfDialog.getByText(sv.results.whatIf.groupSizeChangesHeading)).toBeVisible();
  // Moving 1 of 6 players out of an evenly-split group of 6 (max size 12) is always safe.
  await expect(whatIfDialog.getByTestId("whatif-would-break-hard")).toHaveCount(0);

  await whatIfDialog.getByRole("button", { name: sv.results.whatIf.actions.moveAnyway }).click();
  await expect(page.getByText(sv.results.whatIf.moveSuccess(memberName, group2Name))).toBeVisible();
  await expect(whatIfDialog).toHaveCount(0);

  // --- Results refetch: the member now shows in group 2 with the "manuell" source badge ---
  // toContainText (not getByText(...).toBeVisible()) on the row - matches the codebase's own
  // established convention (see optimize-results.spec.ts's lock-badge check) for a Mantine Badge
  // label span nested in a table cell.
  const movedRow = group2Card.getByRole("row").filter({ hasText: memberName });
  await expect(movedRow).toHaveCount(1);
  await expect(movedRow).toContainText(sv.results.groupCard.sourceBadge.manual);

  // --- Re-opening the explanation for the SAME run now shows the staleness banner ---
  await movedRow.getByRole("button", { name: sv.results.groupCard.explainButton, exact: true }).click();
  const explainDrawerAfterMove = page.getByRole("dialog", { name: sv.results.explain.title(memberName) });
  await expect(explainDrawerAfterMove).toBeVisible();
  await expect(explainDrawerAfterMove.getByTestId("explain-stale-banner")).toBeVisible();
  await explainDrawerAfterMove.getByRole("button", { name: sv.common.close }).click();
  await expect(explainDrawerAfterMove).toHaveCount(0);

  // --- Re-solving clears the staleness (a fresh run -> currentRevision === the new run's own) ---
  await page.getByRole("tab", { name: sv.plan.tabs.optimize }).click();
  await page.getByRole("radio", { name: sv.optimize.profiles.GREEDY.label }).click();
  await page.getByRole("button", { name: sv.optimize.startButton }).click();
  await expect(page.getByTestId("last-run-summary").getByTestId("last-run-score-line")).toBeVisible({ timeout: 10_000 });

  await page.getByRole("tab", { name: sv.plan.tabs.results }).click();
  // The same participant, wherever GREEDY's fresh re-sort landed them this time.
  const memberRowAfterResolve = page.getByTestId("group-card").getByRole("row").filter({ hasText: memberName });
  await expect(memberRowAfterResolve).toHaveCount(1);
  await memberRowAfterResolve.getByRole("button", { name: sv.results.groupCard.explainButton, exact: true }).click();
  const explainDrawerAfterResolve = page.getByRole("dialog", { name: sv.results.explain.title(memberName) });
  await expect(explainDrawerAfterResolve).toBeVisible();
  await expect(explainDrawerAfterResolve.getByTestId("explain-stale-banner")).toHaveCount(0);
});
