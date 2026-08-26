import { describe, expect, it, vi } from "vitest";
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

  it("feasible FINISHED run with everyone placed -> green success alert counting PARTICIPANTS (not groups), WITH Visa grupperna button", async () => {
    // v0.6.0 audit fix C1: N is participants actually placed, never the group count - 2 participants,
    // 3 groups, unassignedCount 0 must read "Klart! Alla 2 deltagare fick en grupp.", not "3".
    server.use(
      ...baseHandlers({
        participants: [
          { id: "p-1", personId: "person-1", activityPlanId: "plan-1" },
          { id: "p-2", personId: "person-2", activityPlanId: "plan-1" },
        ],
        groups: [
          { id: "g-1", activityPlanId: "plan-1", name: "Grupp 1" },
          { id: "g-2", activityPlanId: "plan-1", name: "Grupp 2" },
          { id: "g-3", activityPlanId: "plan-1", name: "Grupp 3" },
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
    // v0.6.0 audit fix C2: a previous run exists and nothing is running -> the button relabels.
    expect(screen.getByTestId("simple-optimize-button")).toHaveTextContent(sv.simple.optimize.rerunButton);
  });

  it("feasible FINISHED run that still left participants unassigned -> yellow waitlist warning, NEVER green, WITH Visa grupperna button", async () => {
    // v0.6.0 audit fix C1 (BLOCKER, "the green lie about waitlisted kids"): feasible + hard === 0 is
    // no longer enough for the green success alert - unassignedCount > 0 must fall into the new
    // warning state instead.
    server.use(
      ...baseHandlers({
        participants: [
          { id: "p-1", personId: "person-1", activityPlanId: "plan-1" },
          { id: "p-2", personId: "person-2", activityPlanId: "plan-1" },
          { id: "p-3", personId: "person-3", activityPlanId: "plan-1" },
        ],
        runs: [
          run({
            status: "FINISHED",
            resultSummaryJson: JSON.stringify({ hard: 0, medium: -400, soft: -10, feasible: true, unassignedCount: 2 }),
          }),
        ],
      }),
    );

    renderOptimizePanelSimple();

    const outcome = await screen.findByTestId("simple-optimize-outcome");
    expect(outcome).toHaveTextContent(sv.simple.optimize.waitlistAlert(2));
    expect(outcome).not.toHaveTextContent("Klart! Alla");
    expect(screen.getByTestId("simple-optimize-view-groups-button")).toBeInTheDocument();
  });
});

describe("OptimizePanelSimple: re-run affordances (C2)", () => {
  it("does not show the confirm modal on a first-time click (no previous run), even when stale is somehow true", async () => {
    const order: string[] = [];
    server.use(...baseHandlers({ groups: [], stale: true, runs: [] }));
    server.use(
      http.post("/api/plans/plan-1/groups/generate", () => {
        order.push("generate");
        return HttpResponse.json([{ id: "g-1", activityPlanId: "plan-1", name: "Grupp 1" }]);
      }),
      http.post("/api/plans/plan-1/solve", () => {
        order.push("solve");
        return HttpResponse.json({ runId: "run-1", status: "SOLVING_SCHEDULED" }, { status: 202 });
      }),
    );

    const user = userEvent.setup();
    renderOptimizePanelSimple();

    const button = await screen.findByTestId("simple-optimize-button");
    expect(button).toHaveTextContent(sv.simple.optimize.createButton);
    await waitFor(() => expect(button).toBeEnabled());
    await user.click(button);

    await waitFor(() => expect(order).toEqual(["generate", "solve"]));
    expect(screen.queryByText(sv.simple.optimize.confirmRerun.message)).not.toBeInTheDocument();
  });

  it("does not show the confirm modal re-running when groups are NOT stale", async () => {
    const order: string[] = [];
    server.use(
      ...baseHandlers({
        groups: [{ id: "g-1", activityPlanId: "plan-1", name: "Grupp 1" }],
        stale: false,
        runs: [run({ status: "FINISHED", resultSummaryJson: JSON.stringify({ hard: 0, medium: 0, soft: 0, feasible: true, unassignedCount: 0 }) })],
      }),
    );
    server.use(
      http.post("/api/plans/plan-1/groups/generate", () => {
        order.push("generate");
        return HttpResponse.json([]);
      }),
      http.post("/api/plans/plan-1/solve", () => {
        order.push("solve");
        return HttpResponse.json({ runId: "run-2", status: "SOLVING_SCHEDULED" }, { status: 202 });
      }),
    );

    const user = userEvent.setup();
    renderOptimizePanelSimple();

    const button = await screen.findByTestId("simple-optimize-button");
    // The button relabels once `runs` resolves (a beat after the button itself first renders).
    await waitFor(() => expect(button).toHaveTextContent(sv.simple.optimize.rerunButton));
    await waitFor(() => expect(button).toBeEnabled());
    await user.click(button);

    await waitFor(() => expect(order).toEqual(["solve"]));
    expect(screen.queryByText(sv.simple.optimize.confirmRerun.message)).not.toBeInTheDocument();
  });

  it("shows a confirm modal before re-running a STALE plan; Avbryt cancels without calling generate/solve", async () => {
    let called = false;
    server.use(
      ...baseHandlers({
        groups: [{ id: "g-1", activityPlanId: "plan-1", name: "Grupp 1" }],
        stale: true,
        runs: [run({ status: "FINISHED", resultSummaryJson: JSON.stringify({ hard: 0, medium: 0, soft: 0, feasible: true, unassignedCount: 0 }) })],
      }),
    );
    server.use(
      http.post("/api/plans/plan-1/groups/generate", () => {
        called = true;
        return HttpResponse.json([]);
      }),
    );

    const user = userEvent.setup();
    renderOptimizePanelSimple();

    const button = await screen.findByTestId("simple-optimize-button");
    // The button relabels once `runs` resolves (a beat after the button itself first renders).
    await waitFor(() => expect(button).toHaveTextContent(sv.simple.optimize.rerunButton));
    await waitFor(() => expect(button).toBeEnabled());
    await user.click(button);

    expect(await screen.findByText(sv.simple.optimize.confirmRerun.message)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: sv.common.cancel }));

    await waitFor(() => expect(screen.queryByText(sv.simple.optimize.confirmRerun.message)).not.toBeInTheDocument());
    expect(called).toBe(false);
  });

  it("confirming the modal re-runs (generate THEN solve) for a stale plan", async () => {
    const order: string[] = [];
    server.use(
      ...baseHandlers({
        groups: [{ id: "g-1", activityPlanId: "plan-1", name: "Grupp 1" }],
        stale: true,
        runs: [run({ status: "FINISHED", resultSummaryJson: JSON.stringify({ hard: 0, medium: 0, soft: 0, feasible: true, unassignedCount: 0 }) })],
      }),
    );
    server.use(
      http.post("/api/plans/plan-1/groups/generate", () => {
        order.push("generate");
        return HttpResponse.json([]);
      }),
      http.post("/api/plans/plan-1/solve", () => {
        order.push("solve");
        return HttpResponse.json({ runId: "run-2", status: "SOLVING_SCHEDULED" }, { status: 202 });
      }),
    );

    const user = userEvent.setup();
    renderOptimizePanelSimple();

    const button = await screen.findByTestId("simple-optimize-button");
    await waitFor(() => expect(button).toBeEnabled());
    await user.click(button);

    await screen.findByText(sv.simple.optimize.confirmRerun.message);
    await user.click(screen.getByTestId("simple-optimize-confirm-rerun-button"));

    await waitFor(() => expect(order).toEqual(["generate", "solve"]));
  });
});

describe("OptimizePanelSimple: outcome card visibility (C2)", () => {
  it("the outcome card is aria-live=polite", async () => {
    server.use(
      ...baseHandlers({
        runs: [run({ status: "FINISHED", resultSummaryJson: JSON.stringify({ hard: 0, medium: 0, soft: 0, feasible: true, unassignedCount: 0 }) })],
      }),
    );

    renderOptimizePanelSimple();

    const card = await screen.findByTestId("simple-optimize-result");
    expect(card).toHaveAttribute("aria-live", "polite");
  });

  it("scrolls the outcome card into view exactly once when a run is already settled on mount", async () => {
    const scrollIntoViewMock = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoViewMock;
    server.use(
      ...baseHandlers({
        runs: [run({ status: "FINISHED", resultSummaryJson: JSON.stringify({ hard: 0, medium: 0, soft: 0, feasible: true, unassignedCount: 0 }) })],
      }),
    );

    renderOptimizePanelSimple();

    await screen.findByTestId("simple-optimize-outcome");
    await waitFor(() => expect(scrollIntoViewMock).toHaveBeenCalledTimes(1));
  });

  it("FAILED run shows a persistent retry button (not just a transient toast) that re-triggers handleCreateGroups", async () => {
    let generateCalled = false;
    server.use(...baseHandlers({ groups: [], runs: [run({ status: "FAILED", resultSummaryJson: null })] }));
    server.use(
      http.post("/api/plans/plan-1/groups/generate", () => {
        generateCalled = true;
        return HttpResponse.json([]);
      }),
      http.post("/api/plans/plan-1/solve", () => HttpResponse.json({ runId: "run-2", status: "SOLVING_SCHEDULED" }, { status: 202 })),
    );

    const user = userEvent.setup();
    renderOptimizePanelSimple();

    await screen.findByTestId("simple-optimize-outcome");
    const retryButton = screen.getByTestId("simple-optimize-retry-button");
    expect(retryButton).toHaveTextContent(sv.simple.optimize.retryButton);

    await user.click(retryButton);
    await waitFor(() => expect(generateCalled).toBe(true));
  });
});

describe("OptimizePanelSimple: readiness copy (C4)", () => {
  it("shows the loading tooltip (not the missing-things text) while prerequisites are still loading", async () => {
    let resolveParticipants!: () => void;
    const gate = new Promise<void>((resolve) => {
      resolveParticipants = resolve;
    });
    server.use(...baseHandlers({ participants: [] }));
    server.use(
      http.get("/api/plans/plan-1/participants", async () => {
        await gate;
        return HttpResponse.json([]);
      }),
    );

    const user = userEvent.setup();
    renderOptimizePanelSimple();

    const button = await screen.findByTestId("simple-optimize-button");
    await user.hover(button);
    expect(await screen.findByText(sv.simple.optimize.notReadyTooltipLoading)).toBeInTheDocument();

    resolveParticipants();
    await waitFor(() => expect(screen.queryByText(sv.simple.optimize.notReadyTooltipLoading)).not.toBeInTheDocument());
    expect(await screen.findByText(sv.simple.optimize.notReadyTooltip)).toBeInTheDocument();
  });

  it("zero training slots at all -> 'Lägg till träningstider' CTA (missingLabel.resourcesNoSlots)", async () => {
    server.use(...baseHandlers({ blocks: [] }));

    renderOptimizePanelSimple();

    expect(await screen.findByText(sv.simple.optimize.missingLabel.resourcesNoSlots)).toBeInTheDocument();
    expect(screen.queryByText(sv.simple.optimize.missingLabel.resourcesNoCourts)).not.toBeInTheDocument();
  });

  it("slots exist but zero ACTIVE courts -> 'Ange antal banor' CTA (missingLabel.resourcesNoCourts)", async () => {
    const noActiveCourts: SlotBlocksView[] = [
      {
        ...ACTIVE_BLOCKS[0],
        blocks: ACTIVE_BLOCKS[0].blocks.map((block) => ({ ...block, active: false })),
      },
    ];
    server.use(...baseHandlers({ blocks: noActiveCourts }));

    renderOptimizePanelSimple();

    expect(await screen.findByText(sv.simple.optimize.missingLabel.resourcesNoCourts)).toBeInTheDocument();
    expect(screen.queryByText(sv.simple.optimize.missingLabel.resourcesNoSlots)).not.toBeInTheDocument();
  });
});

describe("OptimizePanelSimple: stale-groups banner (C4)", () => {
  it("shows the stale banner when returning to an old run whose plan has since changed", async () => {
    server.use(
      ...baseHandlers({
        stale: true,
        runs: [run({ status: "FINISHED", resultSummaryJson: JSON.stringify({ hard: 0, medium: 0, soft: 0, feasible: true, unassignedCount: 0 }) })],
      }),
    );

    renderOptimizePanelSimple();

    expect(await screen.findByTestId("simple-optimize-stale-banner")).toHaveTextContent(sv.simple.optimize.staleBanner);
  });

  it("does not show the stale banner with no previous run, even if stale is somehow true", async () => {
    server.use(...baseHandlers({ stale: true, runs: [] }));

    renderOptimizePanelSimple();

    await screen.findByTestId("simple-optimize-button");
    expect(screen.queryByTestId("simple-optimize-stale-banner")).not.toBeInTheDocument();
  });

  it("does not show the stale banner when groups are in sync", async () => {
    server.use(
      ...baseHandlers({
        stale: false,
        runs: [run({ status: "FINISHED", resultSummaryJson: JSON.stringify({ hard: 0, medium: 0, soft: 0, feasible: true, unassignedCount: 0 }) })],
      }),
    );

    renderOptimizePanelSimple();

    await screen.findByTestId("simple-optimize-outcome");
    expect(screen.queryByTestId("simple-optimize-stale-banner")).not.toBeInTheDocument();
  });
});

describe("OptimizePanelSimple: calm progress copy while running (C3)", () => {
  it("shows the simple-scoped progress heading and the pre-cancel note", async () => {
    server.use(...baseHandlers());
    server.use(
      http.get("/api/plans/plan-1/solve/status", () =>
        HttpResponse.json({ status: "SOLVING_ACTIVE", elapsedMs: 1000, limitMs: 60000 }),
      ),
      http.get("/api/plans/plan-1/solve/live", () => new HttpResponse(null, { status: 204 })),
    );

    renderOptimizePanelSimple();

    expect(await screen.findByText(sv.simple.optimize.progressHeading)).toBeInTheDocument();
    expect(screen.getByTestId("simple-optimize-cancel-hint")).toHaveTextContent(sv.simple.optimize.cancelHint);
  });
});
