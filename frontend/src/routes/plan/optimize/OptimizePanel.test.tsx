import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { sv } from "../../../i18n/sv";
import { OptimizePanel } from "./OptimizePanel";
import type { ActivityPlan, SuggestDurationResponse } from "../../../api/types";

/** OptimizePanel reads planId via useParams - needs a matched route, same pattern as
 *  CapacityPanel.test.tsx. */
function renderOptimizePanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={["/plans/plan-1/optimering"]}>
          <Routes>
            <Route path="/plans/:planId/optimering" element={<OptimizePanel />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

const PLAN: ActivityPlan = {
  id: "plan-1",
  seasonPlanId: "season-1",
  name: "Herr",
  status: "draft",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

const SUGGESTION: SuggestDurationResponse = {
  suggestedSeconds: 60,
  machineSpeedFactor: 1,
  benchmarkMs: 1000,
  problemSize: { participants: 10, groups: 1, activeBlocks: 1, coaches: 0, wishes: 0, customFieldConstraints: 0 },
  rationaleSv: "Baserat på planens storlek föreslås 60 sekunder.",
};

/**
 * v0.3.0 WI-3 smoke test: the three "Optimera endast" checkboxes, the four blocking checkboxes, the
 * "Grupper" heading (defaults pointer), the solve-profiles Radio.Group and the suggested-time card's
 * heading each gained a HelpTip. Only asserts the trigger buttons render - copy lives in sv.ts.
 */
describe("OptimizePanel help tips", () => {
  it("renders a HelpTip for every explained optimize/blocking/profile/suggestion setting", async () => {
    server.use(
      http.get("/api/plans/plan-1", () => HttpResponse.json(PLAN)),
      http.get("/api/plans/plan-1/groups", () => HttpResponse.json([])),
      http.get("/api/plans/plan-1/constraint-weights", () => HttpResponse.json([])),
      http.get("/api/plans/plan-1/solve/status", () => HttpResponse.json({ status: "NOT_SOLVING" })),
      http.get("/api/plans/plan-1/runs", () => HttpResponse.json([])),
      http.post("/api/plans/plan-1/solve/suggest-duration", () => HttpResponse.json(SUGGESTION)),
    );

    renderOptimizePanel();

    // The blocking checkboxes' own accessible names must remain exact (e2e contract).
    await screen.findByRole("checkbox", { name: "Blockera spelare/personer" });

    // The profiles Radio.Group's HelpTip lives inside the collapsed "Avancerat" accordion - open it
    // (mirrors how the e2e specs reach the profile radios). Mantine's Collapse animates the reveal
    // and keeps the panel `aria-hidden` until the transition finishes, so a text-only wait resolves
    // too early for role queries (which, unlike text queries, respect `aria-hidden`) - wait for the
    // HelpTip's own role instead, since role-based queries only resolve once it's truly accessible.
    await userEvent.setup().click(screen.getByTestId("advanced-toggle"));
    await screen.findByRole("button", { name: sv.help.ariaLabel(sv.optimize.profileHeading) });

    const helpTips = screen.getAllByRole("button", { name: /^Förklaring:/ });
    // groups heading + 3 optimizeOnly + 4 blocking + profiles + suggested time = 10
    expect(helpTips.length).toBeGreaterThanOrEqual(10);
  });
});
