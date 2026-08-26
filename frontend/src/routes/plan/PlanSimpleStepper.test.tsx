import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, useLocation } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "../../test/server";
import { sv } from "../../i18n/sv";
import type { PriorityOrderView } from "../../api/priorityOrder";
import type { SlotBlocksView } from "../../api/types";
import { SIMPLE_STEPS } from "./planSimpleSteps";
import { PlanSimpleStepper } from "./PlanSimpleStepper";

const PLAN_ID = "plan-1";
const CHECK_ICON_SELECTOR = ".tabler-icon-check";

function LocationDisplay() {
  const location = useLocation();
  return <div data-testid="current-path">{location.pathname}</div>;
}

/** PlanSimpleStepper reads location via useLocation/navigates via useNavigate but does NOT read
 *  useParams (planId is a prop), so unlike CoachesPanel.test.tsx's pattern this doesn't need a
 *  matched <Route> - a plain MemoryRouter with the desired initialEntries is enough. */
function renderStepper(initialPath: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return render(
    <MantineProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[initialPath]}>
          <PlanSimpleStepper planId={PLAN_ID} />
          <LocationDisplay />
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

// v0.6.0 F3 (M-S3): a fully-resolved, "normal" priority-order response - top priority TRAIN_TOGETHER
// ("Träna tillsammans"), weights matching the order (customWeightsActive: false). `updatedAt` is a
// real timestamp (v0.6.0 F3 review fix, FIX 4: this fixture represents a plan whose order HAS been
// explicitly saved at least once, which is what actually drives the step's checkmark - see
// planSimpleSteps.ts's priorityCompletion doc comment) - `mockPlanData({ priorityOrder: { ...
// DEFAULT_PRIORITY_ORDER, updatedAt: null } })` is used instead wherever a test specifically needs
// the "never saved" (no checkmark) state. Individual tests override just this handler
// (mockPriorityOrderError below) when they need a different state.
const DEFAULT_PRIORITY_ORDER: PriorityOrderView = {
  order: ["TRAIN_TOGETHER", "PREVIOUS_GROUP", "PREFERRED_TIME", "LEVEL"],
  defaultOrder: ["TRAIN_TOGETHER", "PREVIOUS_GROUP", "PREFERRED_TIME", "LEVEL"],
  matchesOrder: true,
  customWeightsActive: false,
  otherOverridesActive: false,
  staleSinceLastRun: false,
  updatedAt: "2026-01-01T00:00:00Z",
  priorities: [
    { key: "TRAIN_TOGETHER", rank: 1, labelSv: "Träna tillsammans", summarySv: "", constraintKeys: [], weights: {}, enabled: true },
    { key: "PREVIOUS_GROUP", rank: 2, labelSv: "Fortsätta i samma grupp", summarySv: "", constraintKeys: [], weights: {}, enabled: true },
    { key: "PREFERRED_TIME", rank: 3, labelSv: "Önskad tid", summarySv: "", constraintKeys: [], weights: {}, enabled: true },
    { key: "LEVEL", rank: 4, labelSv: "Jämn nivå", summarySv: "", constraintKeys: [], weights: {}, enabled: true },
  ],
};

/** v0.6.0 audit-fix A8: builds `timeSlots` grouped training-block entries (one court block per
 *  slot), with the first `activeCourts` of them marked `active: true` and the rest `active: false` -
 *  the same grouped shape `useTrainingBlocksForPlan` (ResourcesPanel.tsx's own query) returns, now
 *  also driving the Tider step's checkmark (real capacity, not just slot count). */
function trainingBlocksFixture(timeSlots: number, activeCourts: number): SlotBlocksView[] {
  return Array.from({ length: timeSlots }, (_, i) => ({
    timeSlot: {
      id: `t${i}`,
      activityPlanId: PLAN_ID,
      dayOfWeek: "THURSDAY",
      startTime: "18:00",
      endTime: "19:00",
      durationMinutes: 60,
      label: `Tid ${i}`,
    },
    blocks: [
      {
        id: `block${i}`,
        timeSlotId: `t${i}`,
        courtId: `court${i}`,
        courtName: `Bana ${i}`,
        activityPlanId: PLAN_ID,
        active: i < activeCourts,
        locked: false,
      },
    ],
  }));
}

/** `participants`/`timeSlots`(+`activeCourts`)/`runs`(+`latestRunFinished`+`latestRunUnassignedCount`)/
 *  `savedPlans` default to a "fully loaded, non-empty-except-savedPlans, latest run finished with
 *  everyone placed" plan (260/3 slots with 2 active courts/1 finished run/0 unassigned/0 saved plans -
 *  matches the pre-existing live-number description assertions below, while also giving
 *  Tider/Optimera/Resultat a real "done" signal by default - v0.6.0 audit-fix A8, threaded through
 *  `resultSummaryJson` since v0.6.0 final pre-release fix round FIX 1's `hasUsableResult`/
 *  `resultatAllPlaced` read that, not just `status`). `priorityOrder` (v0.6.0 F3) defaults to
 *  {@link DEFAULT_PRIORITY_ORDER} - pass `null` to instead make that endpoint error, for tests that
 *  need Prioriteringar's completion signal to stay unresolved (`undefined`, same as a still-loading
 *  query). */
function mockPlanData({
  participants = 260,
  timeSlots = 3,
  activeCourts = 2,
  runs = 1,
  latestRunFinished = true,
  latestRunUnassignedCount = 0,
  savedPlans = 0,
  priorityOrder = DEFAULT_PRIORITY_ORDER as PriorityOrderView | null,
} = {}) {
  server.use(
    http.get(`/api/plans/${PLAN_ID}/participants`, () =>
      HttpResponse.json(Array.from({ length: participants }, (_, i) => ({ id: `p${i}` }))),
    ),
    http.get(`/api/plans/${PLAN_ID}/training-blocks`, () =>
      HttpResponse.json(trainingBlocksFixture(timeSlots, activeCourts)),
    ),
    http.get(`/api/plans/${PLAN_ID}/runs`, () =>
      HttpResponse.json(
        // Most-recent-first (useOptimizationRuns's own doc comment) - `data[0]` is the latest run.
        Array.from({ length: runs }, (_, i) => {
          const status = i === 0 ? (latestRunFinished ? "FINISHED" : "SOLVING_ACTIVE") : "FINISHED";
          return {
            id: `r${i}`,
            status,
            startedAt: "2026-01-01T00:00:00Z",
            resultSummaryJson:
              status === "FINISHED"
                ? JSON.stringify({
                    hard: 0,
                    medium: 0,
                    soft: 0,
                    feasible: true,
                    unassignedCount: i === 0 ? latestRunUnassignedCount : 0,
                  })
                : null,
          };
        }),
      ),
    ),
    http.get(`/api/plans/${PLAN_ID}/priority-order`, () =>
      priorityOrder ? HttpResponse.json(priorityOrder) : HttpResponse.json({ error: "not found" }, { status: 404 }),
    ),
    http.get(`/api/plans/${PLAN_ID}/saved-plans`, () =>
      HttpResponse.json(Array.from({ length: savedPlans }, (_, i) => ({ id: `sp${i}` }))),
    ),
  );
}

describe("PlanSimpleStepper", () => {
  it("renders all 6 steps with their Swedish labels and testids", () => {
    mockPlanData();
    renderStepper(`/plans/${PLAN_ID}/deltagare`);

    expect(screen.getByTestId("plan-simple-stepper")).toBeInTheDocument();
    SIMPLE_STEPS.forEach((step) => {
      const stepEl = screen.getByTestId(step.testId);
      expect(within(stepEl).getByText(sv.simple.steps[step.labelKey])).toBeInTheDocument();
    });
  });

  it("wraps the stepper in a <nav> landmark with a Swedish label (FIX 4)", () => {
    mockPlanData();
    renderStepper(`/plans/${PLAN_ID}/deltagare`);

    const nav = screen.getByRole("navigation", { name: sv.simple.stepperNavLabel });
    expect(within(nav).getByTestId("plan-simple-stepper")).toBeInTheDocument();
  });

  it("marks only the active step aria-current='step' (FIX 4)", () => {
    mockPlanData();
    renderStepper(`/plans/${PLAN_ID}/prioriteringar`);

    expect(screen.getByTestId("plan-simple-step-prioriteringar")).toHaveAttribute("aria-current", "step");
    expect(screen.getByTestId("plan-simple-step-deltagare")).not.toHaveAttribute("aria-current");
    expect(screen.getByTestId("plan-simple-step-tider")).not.toHaveAttribute("aria-current");
    expect(screen.getByTestId("plan-simple-step-optimera")).not.toHaveAttribute("aria-current");
  });

  it("shows live-number descriptions once participants/time-slots/saved-plans load", async () => {
    mockPlanData({ savedPlans: 2 });
    renderStepper(`/plans/${PLAN_ID}/deltagare`);

    expect(await within(screen.getByTestId("plan-simple-step-deltagare")).findByText("260 deltagare")).toBeInTheDocument();
    expect(await within(screen.getByTestId("plan-simple-step-tider")).findByText("3 tider")).toBeInTheDocument();
    // v0.6.0 F6 (M-S6): restored - SimpleSaveExportCard now lands on the export route, so Exportera
    // behaves like Deltagare/Tider/Optimera (a real live count), not a static fallback.
    expect(
      await within(screen.getByTestId("plan-simple-step-exportera")).findByText("2 sparade planer"),
    ).toBeInTheDocument();
  });

  it("Prioriteringar (F3) shows its live top priority; Resultat (A8) shows the static description with a real checkmark signal - every step renders a description (FIX 8)", async () => {
    mockPlanData();
    renderStepper(`/plans/${PLAN_ID}/deltagare`);

    // v0.6.0 F3: once the real priority-order query resolves, Prioriteringar's description is the
    // current top priority's backend labelSv, not the static F2 placeholder fallback any more - see
    // planSimpleSteps.ts's priorityCompletion doc comment.
    expect(
      await within(screen.getByTestId("plan-simple-step-prioriteringar")).findByText("Viktigast: Träna tillsammans"),
    ).toBeInTheDocument();
    expect(
      await within(screen.getByTestId("plan-simple-step-resultat")).findByText(sv.simple.stepDescriptions.resultat),
    ).toBeInTheDocument();
    // v0.6.0 audit-fix A8: Resultat CAN check now (mockPlanData's default has a finished run) - a
    // permanently grey "5" used to read as an unexplained accusation.
    expect(screen.getByTestId("plan-simple-step-resultat").querySelector(CHECK_ICON_SELECTOR)).not.toBeNull();
  });

  it("Prioriteringar falls back to the static placeholder description while its query hasn't resolved yet (F3)", async () => {
    mockPlanData({ priorityOrder: null });
    renderStepper(`/plans/${PLAN_ID}/deltagare`);

    expect(
      await within(screen.getByTestId("plan-simple-step-prioriteringar")).findByText(sv.simple.stepDescriptions.prioriteringar),
    ).toBeInTheDocument();
  });

  it("clicking a step navigates to its route (allowNextStepsSelect: jumping ahead is allowed)", async () => {
    mockPlanData();
    const user = userEvent.setup();
    renderStepper(`/plans/${PLAN_ID}/deltagare`);

    await user.click(screen.getByTestId("plan-simple-step-exportera"));

    expect(screen.getByTestId("current-path")).toHaveTextContent(`/plans/${PLAN_ID}/export`);
  });

  // v0.6.0 audit-fix A8: Tider's checkmark now gates on ACTIVE COURT count, not just how many time
  // slots are configured - a plan can have slots with every court switched off (zero real capacity).
  it("Tider is NOT checked when time slots exist but every court is inactive", async () => {
    mockPlanData({ timeSlots: 2, activeCourts: 0 });
    renderStepper(`/plans/${PLAN_ID}/export`);

    await within(screen.getByTestId("plan-simple-step-tider")).findByText("2 tider");
    expect(screen.getByTestId("plan-simple-step-tider").querySelector(CHECK_ICON_SELECTOR)).toBeNull();
  });

  // v0.6.0 audit-fix A8: Optimera/Resultat's checkmarks now gate on the LATEST run's status, not
  // merely on `runs.length > 0` - a run 1 second into solving must not check either step.
  it("Optimera and Resultat are NOT checked while the latest run is still solving, even though a run exists", async () => {
    mockPlanData({ runs: 1, latestRunFinished: false });
    renderStepper(`/plans/${PLAN_ID}/export`);

    await within(screen.getByTestId("plan-simple-step-optimera")).findByText("1 körning");
    expect(screen.getByTestId("plan-simple-step-optimera").querySelector(CHECK_ICON_SELECTOR)).toBeNull();
    expect(screen.getByTestId("plan-simple-step-resultat").querySelector(CHECK_ICON_SELECTOR)).toBeNull();
  });

  // v0.6.0 final pre-release fix round (FIX 1, MAJOR, Opus m5): Resultat's checkmark must never
  // contradict a plan whose latest solve still shows a non-empty waitlist - Optimera CAN check (a
  // usable result exists), but Resultat must not, once `unassignedCount > 0`.
  it("Optimera checks but Resultat does NOT when the latest finished run still has waitlisted participants", async () => {
    mockPlanData({ runs: 1, latestRunFinished: true, latestRunUnassignedCount: 3 });
    renderStepper(`/plans/${PLAN_ID}/export`);

    await within(screen.getByTestId("plan-simple-step-optimera")).findByText("1 körning");
    expect(screen.getByTestId("plan-simple-step-optimera").querySelector(CHECK_ICON_SELECTOR)).not.toBeNull();
    expect(screen.getByTestId("plan-simple-step-resultat").querySelector(CHECK_ICON_SELECTOR)).toBeNull();
  });

  // v0.6.0 F2 review fix (FIX 1): completionFor's `completed` used to be computed and never read -
  // Mantine's own position-only Stepper state (`active > index`) checkmarked every step once the
  // admin navigated past it, regardless of whether it actually had any data. These two tests pin the
  // fix: the rendered checkmark now tracks `completed`, not just position.
  describe("checkmarks track completed data, not just step position (FIX 1)", () => {
    it("an empty plan on the last step (Exportera) shows no checkmarks at all", async () => {
      // v0.6.0 F3 review fix (FIX 4, MAJOR): a REAL, resolved priority-order fixture (not a 404) with
      // `updatedAt: null` - the order has never actually been saved, so Prioriteringar must show no
      // checkmark either, same as every other un-passed/unconfirmed step. (Previously this test used
      // `priorityOrder: null` - a 404 - to neuter Prioriteringar's signal instead of actually
      // asserting the real "never saved" state; that's no longer necessary now that `completed` is
      // gated on `updatedAt`, not just "the query resolved".)
      mockPlanData({
        participants: 0,
        timeSlots: 0,
        activeCourts: 0,
        runs: 0,
        latestRunFinished: false,
        priorityOrder: { ...DEFAULT_PRIORITY_ORDER, updatedAt: null },
      });
      renderStepper(`/plans/${PLAN_ID}/export`);

      // Wait for the live-number queries to settle (0 deltagare) so we're not just observing the
      // still-loading state, which also has no checkmarks for an unrelated reason.
      await within(screen.getByTestId("plan-simple-step-deltagare")).findByText("0 deltagare");
      await within(screen.getByTestId("plan-simple-step-prioriteringar")).findByText("Viktigast: Träna tillsammans");

      const stepper = screen.getByTestId("plan-simple-stepper");
      expect(stepper.querySelectorAll(CHECK_ICON_SELECTOR)).toHaveLength(0);
      // Every already-"passed" step (Deltagare..Resultat, all before the active Exportera step)
      // shows its plain number instead - the neutral fallback, not a checkmark.
      expect(within(screen.getByTestId("plan-simple-step-deltagare")).getByText("1")).toBeInTheDocument();
      expect(within(screen.getByTestId("plan-simple-step-prioriteringar")).getByText("3")).toBeInTheDocument();
      expect(within(screen.getByTestId("plan-simple-step-resultat")).getByText("5")).toBeInTheDocument();
    });

    it("a plan with participants+active courts loaded checks Deltagare/Tider once past them, on step 3 (Prioriteringar)", async () => {
      mockPlanData({ participants: 260, timeSlots: 3, activeCourts: 2, runs: 0, latestRunFinished: false });
      renderStepper(`/plans/${PLAN_ID}/prioriteringar`);

      await within(screen.getByTestId("plan-simple-step-deltagare")).findByText("260 deltagare");

      expect(screen.getByTestId("plan-simple-step-deltagare").querySelector(CHECK_ICON_SELECTOR)).not.toBeNull();
      expect(screen.getByTestId("plan-simple-step-tider").querySelector(CHECK_ICON_SELECTOR)).not.toBeNull();
      // v0.6.0 F3, review fix FIX 4 (MAJOR): Prioriteringar (the ACTIVE step here) also shows a
      // checkmark - `completed` drives the icon in EVERY state (stepVisual's own doc comment), even
      // the currently-active one, unlike the position-only Mantine default - but only because
      // DEFAULT_PRIORITY_ORDER's `updatedAt` is a real timestamp (the order HAS been saved). See the
      // "no checkmarks at all" test above for the `updatedAt: null` (never saved) counterpart.
      await within(screen.getByTestId("plan-simple-step-prioriteringar")).findByText("Viktigast: Träna tillsammans");
      expect(screen.getByTestId("plan-simple-step-prioriteringar").querySelector(CHECK_ICON_SELECTOR)).not.toBeNull();
      // Exportera is neither "passed" by position nor has a completed signal of its own.
      expect(screen.getByTestId("plan-simple-step-exportera").querySelector(CHECK_ICON_SELECTOR)).toBeNull();
    });
  });
});
