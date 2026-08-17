import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { CoachesPanel } from "./CoachesPanel";

/** CoachesPanel reads planId via useParams, so unlike renderWithProviders this needs a real
 *  matched route (same providers otherwise) - mirrors CapacityPanel.test.tsx's setup. */
function renderCoachesPanel() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return render(
    <MantineProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={["/plans/plan-1/coaches"]}>
          <Routes>
            <Route path="/plans/:planId/coaches" element={<CoachesPanel />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

const DONE_COACH = {
  id: "coach-1",
  personId: "person-1",
  activityPlanId: "plan-1",
  canAlsoTrainAsParticipant: false,
  reviewedDone: true,
};

const NOT_DONE_COACH = {
  id: "coach-2",
  personId: "person-2",
  activityPlanId: "plan-1",
  canAlsoTrainAsParticipant: false,
  reviewedDone: false,
};

/**
 * WP3 ("Spara och markera som färdig"): the "Färdig" column shows the green check icon only for
 * coaches with `reviewedDone: true`.
 */
describe("CoachesPanel done column", () => {
  it("renders the done icon only for reviewedDone coaches", async () => {
    server.use(
      http.get("/api/plans/plan-1/coaches", () => HttpResponse.json([DONE_COACH, NOT_DONE_COACH])),
      http.get("/api/persons", () =>
        HttpResponse.json([
          { id: "person-1", firstName: "Anna", lastName: "Andersson", canBeParticipant: false, canBeCoach: true },
          { id: "person-2", firstName: "Björn", lastName: "Berg", canBeParticipant: false, canBeCoach: true },
        ]),
      ),
      http.get("/api/plans/plan-1/participants", () => HttpResponse.json([])),
      http.get("/api/plans/plan-1/coaches/coach-1/availability", () => HttpResponse.json([])),
      http.get("/api/plans/plan-1/coaches/coach-2/availability", () => HttpResponse.json([])),
    );

    const { container } = renderCoachesPanel();

    await screen.findByText("Anna Andersson");
    expect(screen.getByText("Björn Berg")).toBeInTheDocument();

    // One done icon (@tabler/icons-react renders a `tabler-icon-circle-check` class) for the single
    // reviewedDone: true coach - Tooltip content itself is only mounted on hover, so this checks the
    // icon it wraps rather than the (not-yet-open) tooltip text.
    expect(container.querySelectorAll(".tabler-icon-circle-check")).toHaveLength(1);
  });
});
