import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { ResourcesPanel } from "./ResourcesPanel";
import type { SlotBlocksView } from "../../../api/types";

/** ResourcesPanel reads planId via useParams - needs a matched route, same pattern as
 *  CapacityPanel.test.tsx. */
function renderResourcesPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={["/plans/plan-1/resurser"]}>
          <Routes>
            <Route path="/plans/:planId/resurser" element={<ResourcesPanel />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

const ENTRY: SlotBlocksView = {
  timeSlot: {
    id: "ts-1",
    activityPlanId: "plan-1",
    dayOfWeek: "THURSDAY",
    startTime: "18:00",
    endTime: "19:30",
    durationMinutes: 90,
    label: "Torsdag 18.00–19.30",
  },
  blocks: [
    { id: "block-1", timeSlotId: "ts-1", courtId: "court-1", courtName: "Bana 1", activityPlanId: "plan-1", active: true, locked: false },
  ],
};

/**
 * v0.3.0 WI-3 smoke test: "Antal banor" gained a HelpTip via its `description` slot (its `label`
 * stays untouched - e2e specs assert it exactly via getByLabel across several files), and the block
 * chips row gained a "Banor" heading + HelpTip explaining the active toggle.
 */
describe("ResourcesPanel help tips", () => {
  it("renders a HelpTip for Antal banor and one for the court-active toggle", async () => {
    server.use(http.get("/api/plans/plan-1/training-blocks", () => HttpResponse.json([ENTRY])));

    renderResourcesPanel();

    await screen.findByText("Torsdag 18.00–19.30");

    // The courts NumberInput's own accessible name must remain exactly "Antal banor" (e2e contract).
    expect(screen.getByLabelText("Antal banor")).toBeInTheDocument();

    const helpTips = screen.getAllByRole("button", { name: /^Förklaring:/ });
    expect(helpTips.length).toBeGreaterThanOrEqual(2);
  });
});
