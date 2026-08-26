import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, useLocation } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "../../test/server";
import { sv } from "../../i18n/sv";
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

/** `participants`/`timeSlots`/`runs` default to a "fully loaded, non-empty" plan (260/3/0 - matches
 *  the pre-existing live-number description assertions below). saved-plans is deliberately NOT
 *  mocked here any more (v0.6.0 F2 review fix, FIX 3): PlanSimpleStepper no longer queries it. */
function mockPlanData({ participants = 260, timeSlots = 3, runs = 0 } = {}) {
  server.use(
    http.get(`/api/plans/${PLAN_ID}/participants`, () =>
      HttpResponse.json(Array.from({ length: participants }, (_, i) => ({ id: `p${i}` }))),
    ),
    http.get(`/api/plans/${PLAN_ID}/time-slots`, () =>
      HttpResponse.json(Array.from({ length: timeSlots }, (_, i) => ({ id: `t${i}` }))),
    ),
    http.get(`/api/plans/${PLAN_ID}/runs`, () => HttpResponse.json(Array.from({ length: runs }, (_, i) => ({ id: `r${i}` })))),
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

  it("shows live-number descriptions once participants/time-slots load", async () => {
    mockPlanData();
    renderStepper(`/plans/${PLAN_ID}/deltagare`);

    expect(await within(screen.getByTestId("plan-simple-step-deltagare")).findByText("260 deltagare")).toBeInTheDocument();
    expect(await within(screen.getByTestId("plan-simple-step-tider")).findByText("3 tider")).toBeInTheDocument();
  });

  it("Prioriteringar/Resultat/Exportera fall back to a static description - all six steps render one (FIX 8)", async () => {
    mockPlanData();
    renderStepper(`/plans/${PLAN_ID}/deltagare`);

    expect(
      await within(screen.getByTestId("plan-simple-step-prioriteringar")).findByText(sv.simple.stepDescriptions.prioriteringar),
    ).toBeInTheDocument();
    expect(
      await within(screen.getByTestId("plan-simple-step-resultat")).findByText(sv.simple.stepDescriptions.resultat),
    ).toBeInTheDocument();
    expect(
      await within(screen.getByTestId("plan-simple-step-exportera")).findByText(sv.simple.stepDescriptions.exportera),
    ).toBeInTheDocument();
  });

  it("clicking a step navigates to its route (allowNextStepsSelect: jumping ahead is allowed)", async () => {
    mockPlanData();
    const user = userEvent.setup();
    renderStepper(`/plans/${PLAN_ID}/deltagare`);

    await user.click(screen.getByTestId("plan-simple-step-exportera"));

    expect(screen.getByTestId("current-path")).toHaveTextContent(`/plans/${PLAN_ID}/export`);
  });

  // v0.6.0 F2 review fix (FIX 1): completionFor's `completed` used to be computed and never read -
  // Mantine's own position-only Stepper state (`active > index`) checkmarked every step once the
  // admin navigated past it, regardless of whether it actually had any data. These two tests pin the
  // fix: the rendered checkmark now tracks `completed`, not just position.
  describe("checkmarks track completed data, not just step position (FIX 1)", () => {
    it("an empty plan on the last step (Exportera) shows no checkmarks at all", async () => {
      mockPlanData({ participants: 0, timeSlots: 0, runs: 0 });
      renderStepper(`/plans/${PLAN_ID}/export`);

      // Wait for the live-number queries to settle (0 deltagare) so we're not just observing the
      // still-loading state, which also has no checkmarks for an unrelated reason.
      await within(screen.getByTestId("plan-simple-step-deltagare")).findByText("0 deltagare");

      const stepper = screen.getByTestId("plan-simple-stepper");
      expect(stepper.querySelectorAll(CHECK_ICON_SELECTOR)).toHaveLength(0);
      // Every already-"passed" step (Deltagare..Resultat, all before the active Exportera step)
      // shows its plain number instead - the neutral fallback, not a checkmark.
      expect(within(screen.getByTestId("plan-simple-step-deltagare")).getByText("1")).toBeInTheDocument();
      expect(within(screen.getByTestId("plan-simple-step-resultat")).getByText("5")).toBeInTheDocument();
    });

    it("a plan with participants+slots loaded checks Deltagare/Tider once past them, on step 3 (Prioriteringar)", async () => {
      mockPlanData({ participants: 260, timeSlots: 3, runs: 0 });
      renderStepper(`/plans/${PLAN_ID}/prioriteringar`);

      await within(screen.getByTestId("plan-simple-step-deltagare")).findByText("260 deltagare");

      expect(screen.getByTestId("plan-simple-step-deltagare").querySelector(CHECK_ICON_SELECTOR)).not.toBeNull();
      expect(screen.getByTestId("plan-simple-step-tider").querySelector(CHECK_ICON_SELECTOR)).not.toBeNull();
      // The active step itself (Prioriteringar) and everything after it are never "passed" yet, so
      // neither has a checkmark regardless of `completed`.
      expect(screen.getByTestId("plan-simple-step-prioriteringar").querySelector(CHECK_ICON_SELECTOR)).toBeNull();
      expect(screen.getByTestId("plan-simple-step-exportera").querySelector(CHECK_ICON_SELECTOR)).toBeNull();
    });
  });
});
