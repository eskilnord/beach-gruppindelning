import { describe, expect, it } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
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

/** Base handlers every test below needs regardless of what it's actually exercising (OptimizePanel
 *  fetches all of these unconditionally on mount) - individual tests override just the endpoint(s)
 *  they care about via an extra `server.use(...)` call after this one (MSW resolves the LAST
 *  matching handler registered, so overrides simply need to be registered afterward). */
function baseHandlers(overrides: { stale?: boolean; reasons?: string[]; runs?: unknown[] } = {}) {
  return [
    http.get("/api/plans/plan-1", () => HttpResponse.json(PLAN)),
    http.get("/api/plans/plan-1/groups", () => HttpResponse.json([])),
    http.get("/api/plans/plan-1/groups/sync-status", () =>
      HttpResponse.json({ stale: overrides.stale ?? false, reasons: overrides.reasons ?? [] }),
    ),
    http.get("/api/plans/plan-1/constraint-weights", () => HttpResponse.json([])),
    http.get("/api/plans/plan-1/solve/status", () => HttpResponse.json({ status: "NOT_SOLVING" })),
    http.get("/api/plans/plan-1/runs", () => HttpResponse.json(overrides.runs ?? [])),
    http.post("/api/plans/plan-1/solve/suggest-duration", () => HttpResponse.json(SUGGESTION)),
  ];
}

// WI-C ("re-run doesn't feel like it re-runs" user feedback v0.4 #4): staleness banner, its
// regenerate button (success + 409), the unchanged-from-previous note, and the cold-start checkbox.
describe("OptimizePanel WI-C staleness/unchanged/cold-start", () => {
  it("renders the staleness banner with the server's reasons when groups are out of sync", async () => {
    server.use(...baseHandlers({ stale: true, reasons: ["Inga grupper är genererade ännu."] }));

    renderOptimizePanel();

    const banner = await screen.findByTestId("groups-stale-banner");
    expect(banner).toHaveTextContent("Inga grupper är genererade ännu.");
    expect(within(banner).getByRole("button", { name: sv.optimize.groups.staleBanner.regenerateButton })).toBeInTheDocument();
  });

  it("does not render the staleness banner when groups are in sync", async () => {
    server.use(...baseHandlers({ stale: false }));

    renderOptimizePanel();

    await screen.findByTestId("groups-summary"); // wait for the panel to settle before asserting absence.
    expect(screen.queryByTestId("groups-stale-banner")).not.toBeInTheDocument();
  });

  it("clicking the banner's regenerate button calls the generate-groups endpoint", async () => {
    let generateCalled = false;
    server.use(
      ...baseHandlers({ stale: true, reasons: ["Inga grupper är genererade ännu."] }),
      http.post("/api/plans/plan-1/groups/generate", () => {
        generateCalled = true;
        return HttpResponse.json([]);
      }),
    );

    renderOptimizePanel();
    const banner = await screen.findByTestId("groups-stale-banner");
    await userEvent.setup().click(within(banner).getByRole("button", { name: sv.optimize.groups.staleBanner.regenerateButton }));

    await waitFor(() => expect(generateCalled).toBe(true));
  });

  it("shows the server's Swedish 409 message inline on the banner when regeneration is refused", async () => {
    const lockedMessage = "Grupper är låsta - kan inte återskapa grupper utan uttrycklig bekräftelse (grupp: Grupp 1)";
    server.use(
      ...baseHandlers({ stale: true, reasons: ["Inga grupper är genererade ännu."] }),
      http.post("/api/plans/plan-1/groups/generate", () => HttpResponse.json({ error: lockedMessage }, { status: 409 })),
    );

    renderOptimizePanel();
    const banner = await screen.findByTestId("groups-stale-banner");
    await userEvent.setup().click(within(banner).getByRole("button", { name: sv.optimize.groups.staleBanner.regenerateButton }));

    await waitFor(() => expect(screen.getByTestId("groups-stale-banner")).toHaveTextContent(lockedMessage));
  });

  it("renders the unchanged-from-previous note when the latest run's summary flags it", async () => {
    server.use(
      ...baseHandlers({
        runs: [
          {
            id: "run-1",
            activityPlanId: "plan-1",
            status: "FINISHED",
            startedAt: "2026-07-03T10:00:00Z",
            finishedAt: "2026-07-03T10:00:05Z",
            durationMs: 5000,
            resultSummaryJson: JSON.stringify({
              hard: 0, medium: 0, soft: -100, feasible: true, unassignedCount: 0, unchangedFromPrevious: true,
            }),
          },
        ],
      }),
    );

    renderOptimizePanel();

    expect(await screen.findByTestId("last-run-unchanged-note")).toHaveTextContent(sv.optimize.lastRun.unchangedNote);
  });

  it("does not render the unchanged-from-previous note when the flag is absent", async () => {
    server.use(
      ...baseHandlers({
        runs: [
          {
            id: "run-1",
            activityPlanId: "plan-1",
            status: "FINISHED",
            startedAt: "2026-07-03T10:00:00Z",
            finishedAt: "2026-07-03T10:00:05Z",
            durationMs: 5000,
            resultSummaryJson: JSON.stringify({ hard: 0, medium: 0, soft: -100, feasible: true, unassignedCount: 0 }),
          },
        ],
      }),
    );

    renderOptimizePanel();

    await screen.findByTestId("last-run-score-line");
    expect(screen.queryByTestId("last-run-unchanged-note")).not.toBeInTheDocument();
  });

  it("passes coldStart: true in the solve POST body when 'Börja om från grunden' is checked", async () => {
    const captured: { body: { coldStart?: boolean } | null } = { body: null };
    server.use(
      ...baseHandlers(),
      http.post("/api/plans/plan-1/solve", async ({ request }) => {
        captured.body = (await request.json()) as { coldStart?: boolean };
        return HttpResponse.json({ runId: "run-1", status: "SOLVING_SCHEDULED" }, { status: 202 });
      }),
    );

    const user = userEvent.setup();
    renderOptimizePanel();

    await user.click(screen.getByTestId("advanced-toggle"));
    const coldStartCheckbox = await screen.findByRole("checkbox", { name: sv.optimize.advanced.coldStartLabel });
    await user.click(coldStartCheckbox);
    await user.click(screen.getByRole("button", { name: sv.optimize.startButton }));

    await waitFor(() => expect(captured.body).not.toBeNull());
    expect(captured.body?.coldStart).toBe(true);
  });

  it("defaults coldStart to false in the solve POST body when the checkbox is left unchecked", async () => {
    const captured: { body: { coldStart?: boolean } | null } = { body: null };
    server.use(
      ...baseHandlers(),
      http.post("/api/plans/plan-1/solve", async ({ request }) => {
        captured.body = (await request.json()) as { coldStart?: boolean };
        return HttpResponse.json({ runId: "run-1", status: "SOLVING_SCHEDULED" }, { status: 202 });
      }),
    );

    const user = userEvent.setup();
    renderOptimizePanel();

    await user.click(screen.getByTestId("advanced-toggle"));
    await screen.findByRole("checkbox", { name: sv.optimize.advanced.coldStartLabel });
    await user.click(screen.getByRole("button", { name: sv.optimize.startButton }));

    await waitFor(() => expect(captured.body).not.toBeNull());
    expect(captured.body?.coldStart).toBe(false);
  });
});
