import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import { Notifications } from "@mantine/notifications";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "../../test/server";
import { setUiModeForTests } from "../../lib/uiMode/uiModeStore";
import type { UiMode } from "../../lib/uiMode/uiMode";
import { sv } from "../../i18n/sv";
import { PlanLayout } from "./PlanLayout";

const PLAN_ID = "plan-1";
const SEASON_ID = "season-1";

const PLAN = {
  id: PLAN_ID,
  seasonPlanId: SEASON_ID,
  name: "Herr",
  status: "draft",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

const SEASON = {
  id: SEASON_ID,
  name: "VT26",
  status: "active",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

/** PlanLayout reads `planId` via useParams, so - like CoachesPanel.test.tsx - this needs a real
 *  matched nested route (mirroring router.tsx's plan layout shape), not just renderWithProviders'
 *  plain MemoryRouter. Each of the six simple-step routes plus "falt" (an <AdvancedRouteGate>-only
 *  tab, used to exercise a non-step deep link) gets a trivial marker Outlet child. */
function renderPlanLayout(initialPath: string, uiMode: UiMode) {
  setUiModeForTests(uiMode);
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  });
  return render(
    <MantineProvider>
      <Notifications />
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route path="/plans/:planId" element={<PlanLayout />}>
              <Route path="deltagare" element={<div>deltagare-outlet</div>} />
              <Route path="resurser" element={<div>resurser-outlet</div>} />
              <Route path="prioriteringar" element={<div>prioriteringar-outlet</div>} />
              <Route path="optimering" element={<div>optimering-outlet</div>} />
              <Route path="resultat" element={<div>resultat-outlet</div>} />
              <Route path="export" element={<div>export-outlet</div>} />
              <Route path="falt" element={<div>falt-outlet</div>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

function mockPlan() {
  server.use(
    http.get(`/api/plans/${PLAN_ID}`, () => HttpResponse.json(PLAN)),
    http.get(`/api/seasons/${SEASON_ID}`, () => HttpResponse.json(SEASON)),
  );
}

/** Simple-mode-only chrome (PlanSimpleStepper) fires these four cheap GETs - see
 *  PlanSimpleStepper.tsx's doc comment. */
function mockSimpleStepData() {
  server.use(
    http.get(`/api/plans/${PLAN_ID}/participants`, () => HttpResponse.json([])),
    http.get(`/api/plans/${PLAN_ID}/time-slots`, () => HttpResponse.json([])),
    http.get(`/api/plans/${PLAN_ID}/runs`, () => HttpResponse.json([])),
    http.get(`/api/plans/${PLAN_ID}/saved-plans`, () => HttpResponse.json([])),
  );
}

describe("PlanLayout - ADVANCED mode (unchanged)", () => {
  it("renders the 9-tab bar, not the simple stepper", async () => {
    mockPlan();
    renderPlanLayout(`/plans/${PLAN_ID}/deltagare`, "ADVANCED");

    expect(await screen.findByRole("tablist")).toBeInTheDocument();
    Object.values(sv.plan.tabs).forEach((label) => {
      expect(screen.getByRole("tab", { name: label })).toBeInTheDocument();
    });
    expect(screen.queryByTestId("plan-simple-stepper")).not.toBeInTheDocument();
  });

  it("keeps the separate Redigera/Ta bort buttons and the status badge, no header menu", async () => {
    mockPlan();
    renderPlanLayout(`/plans/${PLAN_ID}/deltagare`, "ADVANCED");

    expect(await screen.findByRole("button", { name: sv.plan.editButton })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: sv.plan.deleteButton })).toBeInTheDocument();
    expect(screen.getByText(PLAN.status)).toBeInTheDocument();
    expect(screen.queryByTestId("plan-header-menu-button")).not.toBeInTheDocument();
  });
});

describe("PlanLayout - SIMPLE mode", () => {
  it("renders the simple stepper, not the 9-tab bar", async () => {
    mockPlan();
    mockSimpleStepData();
    renderPlanLayout(`/plans/${PLAN_ID}/deltagare`, "SIMPLE");

    expect(await screen.findByTestId("plan-simple-stepper")).toBeInTheDocument();
    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
  });

  it("collapses Redigera/Ta bort into one menu behind the IconDots button, hides the status badge", async () => {
    mockPlan();
    mockSimpleStepData();
    const user = userEvent.setup();
    renderPlanLayout(`/plans/${PLAN_ID}/deltagare`, "SIMPLE");

    const menuButton = await screen.findByTestId("plan-header-menu-button");
    expect(screen.queryByRole("button", { name: sv.plan.editButton })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: sv.plan.deleteButton })).not.toBeInTheDocument();
    expect(screen.queryByText(PLAN.status)).not.toBeInTheDocument();

    // getByText (not getByRole) - Mantine's Menu.Dropdown positions itself via floating-ui, which
    // has no real layout to measure in jsdom, so testing-library's hidden-element filtering on
    // getByRole is unreliable here; the item text itself is present in the DOM regardless.
    await user.click(menuButton);
    expect(await screen.findByText(sv.plan.menu.edit)).toBeInTheDocument();
    expect(screen.getByText(sv.plan.menu.delete)).toBeInTheDocument();
  });

  it("footer 'Nästa'/'Tillbaka' navigate between steps and swap the rendered outlet", async () => {
    mockPlan();
    mockSimpleStepData();
    const user = userEvent.setup();
    renderPlanLayout(`/plans/${PLAN_ID}/deltagare`, "SIMPLE");

    expect(await screen.findByText("deltagare-outlet")).toBeInTheDocument();
    expect(screen.queryByTestId("simple-step-back")).not.toBeInTheDocument(); // first step: no back

    await user.click(screen.getByTestId("simple-step-next"));
    expect(await screen.findByText("resurser-outlet")).toBeInTheDocument();

    await user.click(screen.getByTestId("simple-step-back"));
    expect(await screen.findByText("deltagare-outlet")).toBeInTheDocument();
  });

  it("hides the footer on a non-step route (e.g. falt, opened via deep link)", async () => {
    mockPlan();
    mockSimpleStepData();
    renderPlanLayout(`/plans/${PLAN_ID}/falt`, "SIMPLE");

    expect(await screen.findByText("falt-outlet")).toBeInTheDocument();
    expect(screen.queryByTestId("simple-step-back")).not.toBeInTheDocument();
    expect(screen.queryByTestId("simple-step-next")).not.toBeInTheDocument();
  });

  it("hides the 'Nästa' button on the last step", async () => {
    mockPlan();
    mockSimpleStepData();
    renderPlanLayout(`/plans/${PLAN_ID}/export`, "SIMPLE");

    expect(await screen.findByText("export-outlet")).toBeInTheDocument();
    expect(screen.getByTestId("simple-step-back")).toBeInTheDocument();
    expect(screen.queryByTestId("simple-step-next")).not.toBeInTheDocument();
  });
});
