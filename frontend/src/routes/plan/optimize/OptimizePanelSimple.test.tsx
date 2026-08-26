import { describe, expect, it } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import { Notifications } from "@mantine/notifications";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { sv } from "../../../i18n/sv";
import { buildSimpleSolveRequest } from "./simpleSolveRequest";
import { OptimizePanelSimple } from "./OptimizePanelSimple";
import type { SlotBlocksView, SolveRequestBody } from "../../../api/types";

/**
 * v0.6.0 F4 review fix (FIX 9, MAJOR): OptimizePanelSimple had NO dedicated spec file at all before
 * this fix - only the coach-hiding sweep (uiModeCoachHiding.test.tsx) touched it, and only for the
 * "Optimera endast: Tränare" checkbox's absence. Covers: the button's disabled-when-empty gate, the
 * happy-path generate-THEN-solve ordering with the exact pinned body, the 409-on-generate error path
 * (solve must never fire), and all four terminal outcome states (FIX 1).
 */
function renderOptimizePanelSimple() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <MantineProvider>
      <Notifications />
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={["/plans/plan-1/optimering"]}>
          <Routes>
            <Route path="/plans/:planId/optimering" element={<OptimizePanelSimple />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

const ACTIVE_BLOCKS: SlotBlocksView[] = [
  {
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
  },
];

const PARTICIPANTS = [{ id: "p-1", personId: "person-1", activityPlanId: "plan-1" }];

interface HandlerOverrides {
  participants?: unknown[];
  blocks?: SlotBlocksView[];
  groups?: unknown[];
  stale?: boolean;
  runs?: unknown[];
}

/** Every query OptimizePanelSimple fires unconditionally on mount (FIX 7's prerequisite set plus
 *  suggest-duration/solve-status/runs/priority-order) - individual tests override just the
 *  endpoint(s) they care about via a LATER server.use() call (MSW resolves the last-registered
 *  matching handler first), same convention as OptimizePanel.test.tsx's own baseHandlers(). */
function baseHandlers(overrides: HandlerOverrides = {}) {
  return [
    http.get("/api/plans/plan-1/participants", () => HttpResponse.json(overrides.participants ?? PARTICIPANTS)),
    http.get("/api/plans/plan-1/training-blocks", () => HttpResponse.json(overrides.blocks ?? ACTIVE_BLOCKS)),
    http.get("/api/plans/plan-1/priority-order", () => HttpResponse.json({ customWeightsActive: false })),
    http.get("/api/plans/plan-1/groups", () => HttpResponse.json(overrides.groups ?? [])),
    http.get("/api/plans/plan-1/groups/sync-status", () =>
      HttpResponse.json({ stale: overrides.stale ?? false, reasons: [] }),
    ),
    http.get("/api/plans/plan-1/solve/status", () => HttpResponse.json({ status: "NOT_SOLVING" })),
    http.get("/api/plans/plan-1/runs", () => HttpResponse.json(overrides.runs ?? [])),
    http.post("/api/plans/plan-1/solve/suggest-duration", () =>
      HttpResponse.json({
        suggestedSeconds: 60,
        machineSpeedFactor: 1,
        benchmarkMs: 1000,
        problemSize: { participants: 1, groups: 1, activeBlocks: 1, coaches: 0, wishes: 0, customFieldConstraints: 0 },
        rationaleSv: "Baserat på planens storlek föreslås 60 sekunder.",
      }),
    ),
  ];
}

describe("OptimizePanelSimple: disabled when empty", () => {
  it("disables the button when there are no participants", async () => {
    server.use(...baseHandlers({ participants: [] }));

    renderOptimizePanelSimple();

    expect(await screen.findByTestId("simple-optimize-button")).toBeDisabled();
    expect(await screen.findByText(sv.simple.optimize.missingLabel.participants)).toBeInTheDocument();
  });
});

describe("OptimizePanelSimple: happy path", () => {
  it("fires generate THEN solve in order, with the pinned solve body", async () => {
    const order: string[] = [];
    let solveBody: SolveRequestBody | null = null;
    server.use(...baseHandlers({ groups: [] }));
    server.use(
      http.post("/api/plans/plan-1/groups/generate", () => {
        order.push("generate");
        return HttpResponse.json([{ id: "g-1", activityPlanId: "plan-1", name: "Grupp 1" }]);
      }),
      http.post("/api/plans/plan-1/solve", async ({ request }) => {
        order.push("solve");
        solveBody = (await request.json()) as SolveRequestBody;
        return HttpResponse.json({ runId: "run-1", status: "SOLVING_SCHEDULED" }, { status: 202 });
      }),
    );

    const user = userEvent.setup();
    renderOptimizePanelSimple();

    const button = await screen.findByTestId("simple-optimize-button");
    await waitFor(() => expect(button).toBeEnabled());
    await user.click(button);

    await waitFor(() => expect(order).toEqual(["generate", "solve"]));
    expect(solveBody).toEqual(buildSimpleSolveRequest(60));
  });

  it("skips generate (goes straight to solve) when groups already exist and are in sync", async () => {
    const order: string[] = [];
    server.use(...baseHandlers({ groups: [{ id: "g-1", activityPlanId: "plan-1", name: "Grupp 1" }], stale: false }));
    server.use(
      http.post("/api/plans/plan-1/groups/generate", () => {
        order.push("generate");
        return HttpResponse.json([]);
      }),
      http.post("/api/plans/plan-1/solve", () => {
        order.push("solve");
        return HttpResponse.json({ runId: "run-1", status: "SOLVING_SCHEDULED" }, { status: 202 });
      }),
    );

    const user = userEvent.setup();
    renderOptimizePanelSimple();

    const button = await screen.findByTestId("simple-optimize-button");
    await waitFor(() => expect(button).toBeEnabled());
    await user.click(button);

    await waitFor(() => expect(order).toEqual(["solve"]));
  });
});

describe("OptimizePanelSimple: 409 on generate", () => {
  it("shows an error notification and never calls /solve", async () => {
    const lockedMessage = "Grupper är låsta - kan inte återskapa grupper utan uttrycklig bekräftelse";
    let solveCalled = false;
    server.use(...baseHandlers({ groups: [] }));
    server.use(
      http.post("/api/plans/plan-1/groups/generate", () => HttpResponse.json({ error: lockedMessage }, { status: 409 })),
      http.post("/api/plans/plan-1/solve", () => {
        solveCalled = true;
        return HttpResponse.json({ runId: "run-1", status: "SOLVING_SCHEDULED" }, { status: 202 });
      }),
    );

    const user = userEvent.setup();
    renderOptimizePanelSimple();

    const button = await screen.findByTestId("simple-optimize-button");
    await waitFor(() => expect(button).toBeEnabled());
    await user.click(button);

    expect(await screen.findByText(lockedMessage)).toBeInTheDocument();
    expect(solveCalled).toBe(false);
  });
});

function run(overrides: { status: string; resultSummaryJson?: string | null }) {
  return {
    id: "run-1",
    activityPlanId: "plan-1",
    status: overrides.status,
    startedAt: "2026-07-03T10:00:00Z",
    finishedAt: "2026-07-03T10:00:05Z",
    durationMs: 5000,
    resultSummaryJson: overrides.resultSummaryJson ?? null,
  };
}

describe("OptimizePanelSimple: terminal outcome states (FIX 1)", () => {
  it("FAILED run (or one with no parseable summary) -> red failed alert, no Visa grupperna button", async () => {
    server.use(...baseHandlers({ runs: [run({ status: "FAILED", resultSummaryJson: null })] }));

    renderOptimizePanelSimple();

    const outcome = await screen.findByTestId("simple-optimize-outcome");
    expect(outcome).toHaveTextContent(sv.simple.optimize.failedAlert);
    expect(screen.queryByTestId("simple-optimize-view-groups-button")).not.toBeInTheDocument();
  });

  it("CANCELLED run -> gray cancelled alert, WITH Visa grupperna button", async () => {
    server.use(
      ...baseHandlers({
        runs: [
          run({
            status: "CANCELLED",
            resultSummaryJson: JSON.stringify({ hard: 0, medium: 0, soft: -10, feasible: true, unassignedCount: 0 }),
          }),
        ],
      }),
    );

    renderOptimizePanelSimple();

    const outcome = await screen.findByTestId("simple-optimize-outcome");
    expect(outcome).toHaveTextContent(sv.simple.optimize.cancelledAlert);
    expect(screen.getByTestId("simple-optimize-view-groups-button")).toBeInTheDocument();
  });

  it("infeasible FINISHED run (hard != 0 or !feasible) -> yellow infeasible alert, WITH Visa grupperna button", async () => {
    server.use(
      ...baseHandlers({
        runs: [
          run({
            status: "FINISHED",
            resultSummaryJson: JSON.stringify({ hard: -5, medium: 0, soft: -10, feasible: false, unassignedCount: 2 }),
          }),
        ],
      }),
    );

    renderOptimizePanelSimple();

    const outcome = await screen.findByTestId("simple-optimize-outcome");
    expect(outcome).toHaveTextContent(sv.simple.optimize.infeasibleAlert);
    expect(screen.getByTestId("simple-optimize-view-groups-button")).toBeInTheDocument();
  });

  it("feasible FINISHED run -> green success alert with the group count, WITH Visa grupperna button", async () => {
    server.use(
      ...baseHandlers({
        groups: [
          { id: "g-1", activityPlanId: "plan-1", name: "Grupp 1" },
          { id: "g-2", activityPlanId: "plan-1", name: "Grupp 2" },
        ],
        runs: [
          run({
            status: "FINISHED",
            resultSummaryJson: JSON.stringify({ hard: 0, medium: 0, soft: -10, feasible: true, unassignedCount: 0 }),
          }),
        ],
      }),
    );

    renderOptimizePanelSimple();

    const outcome = await screen.findByTestId("simple-optimize-outcome");
    expect(outcome).toHaveTextContent(sv.simple.optimize.successAlert(2));
    expect(screen.getByTestId("simple-optimize-view-groups-button")).toBeInTheDocument();
  });
});
