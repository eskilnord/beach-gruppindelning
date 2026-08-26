import { describe, expect, it } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { sv } from "../../../i18n/sv";
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

/**
 * v0.6.0 audit-fix B10 (P1, REAL DEFECT - "Gunilla" persona): before this fix, SlotRow seeded its
 * courts NumberInput draft from `entry.blocks.length` (active + inactive TOTAL) rather than the
 * ACTIVE count, and `commitCourts` early-returned whenever the draft equalled that TOTAL. Net
 * effect on a shrunk slot (here: 4 blocks total, 2 active - as if the club shrank 4 -> 2 courts):
 * the input showed "4" (wrong - should show the current active count, 2), and retyping "4" to
 * restore capacity was a silent no-op, because 4 already equalled the (wrong) comparison target.
 */
describe("ResourcesPanel B10: courts draft seeds from ACTIVE count, not total", () => {
  const SHRUNK_ENTRY: SlotBlocksView = {
    timeSlot: {
      id: "ts-shrunk",
      activityPlanId: "plan-1",
      dayOfWeek: "THURSDAY",
      startTime: "18:00",
      endTime: "19:30",
      durationMinutes: 90,
      label: "Torsdag 18.00–19.30",
    },
    blocks: [
      { id: "block-1", timeSlotId: "ts-shrunk", courtId: "court-1", courtName: "Bana 1", activityPlanId: "plan-1", active: true, locked: false },
      { id: "block-2", timeSlotId: "ts-shrunk", courtId: "court-2", courtName: "Bana 2", activityPlanId: "plan-1", active: true, locked: false },
      { id: "block-3", timeSlotId: "ts-shrunk", courtId: "court-3", courtName: "Bana 3", activityPlanId: "plan-1", active: false, locked: false },
      { id: "block-4", timeSlotId: "ts-shrunk", courtId: "court-4", courtName: "Bana 4", activityPlanId: "plan-1", active: false, locked: false },
    ],
  };

  it('seeds the "Antal banor" input from the ACTIVE count (2), not the total (4)', async () => {
    server.use(http.get("/api/plans/plan-1/training-blocks", () => HttpResponse.json([SHRUNK_ENTRY])));
    renderResourcesPanel();
    await screen.findByText("Torsdag 18.00–19.30");

    expect(screen.getByLabelText("Antal banor")).toHaveValue("2");
  });

  it('retyping the OLD total (4) actually fires a PUT (no silent no-op) and reactivates the courts', async () => {
    let capturedBody: unknown = null;
    server.use(
      http.get("/api/plans/plan-1/training-blocks", () => HttpResponse.json([SHRUNK_ENTRY])),
      http.put("/api/plans/plan-1/time-slots/ts-shrunk/courts", async ({ request }) => {
        capturedBody = await request.json();
        return HttpResponse.json(
          SHRUNK_ENTRY.blocks.map((b) => ({ ...b, active: true })),
        );
      }),
    );
    renderResourcesPanel();
    await screen.findByText("Torsdag 18.00–19.30");

    const input = screen.getByLabelText("Antal banor");
    expect(input).toHaveValue("2");

    const user = userEvent.setup();
    await user.clear(input);
    await user.type(input, "4");
    await user.tab(); // blur -> commitCourts

    await waitFor(() => expect(capturedBody).toEqual({ count: 4 }));
  });
});

/** v0.6.0 audit-fix B9 (P1, "Gunilla" persona): a 0-active-court slot gets an always-visible orange
 *  warning - never collapsible/dismissable, and rendered regardless of ui mode. */
describe("ResourcesPanel B9: zero-active-courts warning", () => {
  const ZERO_COURTS_ENTRY: SlotBlocksView = {
    timeSlot: {
      id: "ts-zero",
      activityPlanId: "plan-1",
      dayOfWeek: "FRIDAY",
      startTime: "10:00",
      endTime: "11:00",
      durationMinutes: 60,
      label: "Fredag 10.00–11.00",
    },
    blocks: [],
  };

  it("shows the orange zero-courts warning for a slot with no active banor", async () => {
    server.use(http.get("/api/plans/plan-1/training-blocks", () => HttpResponse.json([ZERO_COURTS_ENTRY])));
    renderResourcesPanel();
    await screen.findByText("Fredag 10.00–11.00");

    expect(screen.getByTestId("zero-courts-warning")).toHaveTextContent(sv.resources.zeroCourtsWarning);
  });

  it("does NOT show the warning for a slot with at least one active bana", async () => {
    server.use(http.get("/api/plans/plan-1/training-blocks", () => HttpResponse.json([ENTRY])));
    renderResourcesPanel();
    await screen.findByText("Torsdag 18.00–19.30");

    expect(screen.queryByTestId("zero-courts-warning")).not.toBeInTheDocument();
  });
});

/** v0.6.0 audit-fix B14 ("Gunilla" persona): the delete-confirmation body now names the ACTIVE
 *  court count that disappears along with the time, not just the time's own label. */
describe("ResourcesPanel B14: delete-confirmation names the active court count", () => {
  it("shows the slot's active court count in the confirm message", async () => {
    server.use(http.get("/api/plans/plan-1/training-blocks", () => HttpResponse.json([ENTRY])));
    renderResourcesPanel();
    await screen.findByText("Torsdag 18.00–19.30");

    await userEvent.setup().click(screen.getByRole("button", { name: sv.resources.deleteButton }));

    expect(await screen.findByText(sv.resources.deleteModal.message(1))).toBeInTheDocument();
  });
});
