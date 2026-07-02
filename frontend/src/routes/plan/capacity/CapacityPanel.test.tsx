import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { sv } from "../../../i18n/sv";
import { CapacityPanel } from "./CapacityPanel";

/** CapacityPanel reads planId via useParams, so unlike renderWithProviders this needs a real
 *  matched route (same providers otherwise). */
function renderCapacityPanel() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return render(
    <MantineProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={["/plans/plan-1/kapacitet"]}>
          <Routes>
            <Route path="/plans/:planId/kapacitet" element={<CapacityPanel />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

const BASE_RESPONSE = {
  participantCount: 8,
  waitlistedCount: 0,
  activeTrainingBlockCount: 2,
  targetGroupSize: 10,
  maxGroupSize: 12,
  targetCapacity: 20,
  maxCapacity: 24,
  waitlistRisk: "NONE",
  waitlistMessage: "Ingen risk för kölista.",
  groupsRequiringCoachEstimate: 2,
  perTimeSlot: [
    { timeSlotId: "ts-1", label: "Torsdag 18.00–19.30", activeBlockCount: 2, coachesAvailableCount: 0, coachesPreferredCount: 0 },
  ],
};

describe("CapacityPanel coach section", () => {
  it("renders the neutral no-coaches info state (blue, with Tränare hint) when noCoaches is true", async () => {
    server.use(
      http.get("/api/plans/plan-1/capacity", () =>
        HttpResponse.json({
          ...BASE_RESPONSE,
          coachCount: 0,
          coachShortageRisk: false,
          coachShortageMessage: "Inga tränare registrerade",
          noCoaches: true,
        }),
      ),
    );

    renderCapacityPanel();

    const info = await screen.findByTestId("no-coaches-info");
    expect(info).toHaveTextContent(sv.capacity.noCoaches.title);
    expect(info).toHaveTextContent(sv.capacity.noCoaches.body);
    expect(screen.getByRole("button", { name: sv.capacity.noCoaches.goToCoachesButton })).toBeInTheDocument();

    // Neither shortage-banner variant renders: zero coaches is not a shortage to alarm on.
    expect(screen.queryByText(sv.capacity.coachShortage.risk)).not.toBeInTheDocument();
    expect(screen.queryByText(sv.capacity.coachShortage.ok)).not.toBeInTheDocument();
  });

  it("keeps the red shortage banner for a real shortage (coaches exist but too few)", async () => {
    server.use(
      http.get("/api/plans/plan-1/capacity", () =>
        HttpResponse.json({
          ...BASE_RESPONSE,
          coachCount: 1,
          coachShortageRisk: true,
          coachShortageMessage: "Risk för tränarbrist: för få tillgängliga tränare vid: Torsdag 18.00–19.30",
          noCoaches: false,
        }),
      ),
    );

    renderCapacityPanel();

    expect(await screen.findByText(sv.capacity.coachShortage.risk)).toBeInTheDocument();
    expect(screen.queryByTestId("no-coaches-info")).not.toBeInTheDocument();
  });
});
