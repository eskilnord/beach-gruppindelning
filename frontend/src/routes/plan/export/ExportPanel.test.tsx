import { describe, expect, it } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { setUiModeForTests } from "../../../lib/uiMode/uiModeStore";
import { ExportPanel } from "./ExportPanel";

/** ExportPanel reads planId via useParams - needs a matched route, same pattern as
 *  CapacityPanel.test.tsx. */
function renderExportPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={["/plans/plan-1/export"]}>
          <Routes>
            <Route path="/plans/:planId/export" element={<ExportPanel />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

/**
 * v0.3.0 WI-3 smoke test: "Inkludera kommentarer i export" gained a privacy-reinforcing HelpTip via
 * its `description` slot (its `label` stays untouched - the e2e suite asserts it exactly via
 * getByRole("checkbox", ...)), and the anonymized-export card's heading gained one too.
 */
describe("ExportPanel help tips", () => {
  it("renders a HelpTip for the comments checkbox and one for the anonymized export card", async () => {
    server.use(http.get("/api/plans/plan-1/runs", () => HttpResponse.json([])));

    renderExportPanel();

    await screen.findByTestId("export-empty-hint");

    // The checkbox's own accessible name must remain exact (e2e contract).
    expect(screen.getByRole("checkbox", { name: "Inkludera kommentarer i export" })).toBeInTheDocument();

    const helpTips = screen.getAllByRole("button", { name: /^Förklaring:/ });
    expect(helpTips.length).toBeGreaterThanOrEqual(2);
  });
});

// v0.6.0 final pre-release fix round (FIX 1, MAJOR): the advanced export gate now checks
// hasUsableResult (FINISHED, or CANCELLED with a parseable summary) rather than `runs.length > 0` -
// a run existing isn't enough on its own.
describe("ExportPanel export gate - hasUsableResult (FIX 1)", () => {
  it("stays gated (empty hint, export disabled) when the only run FAILED", async () => {
    server.use(
      http.get("/api/plans/plan-1/runs", () =>
        HttpResponse.json([{ id: "run-1", activityPlanId: "plan-1", status: "FAILED", startedAt: "2026-01-01T00:00:00Z" }]),
      ),
    );

    renderExportPanel();

    await screen.findByTestId("export-empty-hint");
    expect(screen.getByRole("button", { name: "Exportera" })).toBeDisabled();
  });

  it("un-gates (export enabled) once a run has actually FINISHED", async () => {
    server.use(
      http.get("/api/plans/plan-1/runs", () =>
        HttpResponse.json([{ id: "run-1", activityPlanId: "plan-1", status: "FINISHED", startedAt: "2026-01-01T00:00:00Z" }]),
      ),
    );

    renderExportPanel();

    await screen.findByTestId("export-card");
    // The runs query itself is still async at this point (the Card renders regardless of loading
    // state) - waitFor lets it actually resolve before asserting the gate has lifted.
    await waitFor(() => expect(screen.getByRole("button", { name: "Exportera" })).toBeEnabled());
    expect(screen.queryByTestId("export-empty-hint")).not.toBeInTheDocument();
  });
});

// v0.6.0 F6 (M-S6): ExportPanel now renders SimpleSaveExportCard (SIMPLE) or the full advanced
// surface tested above (ADVANCED) via a <SimpleOnly>/<AdvancedOnly> split - same pattern
// ResourcesPanel.tsx already uses.
describe("ExportPanel SIMPLE/ADVANCED split", () => {
  it("renders SimpleSaveExportCard, not the advanced export/anonymized cards, in SIMPLE mode", async () => {
    setUiModeForTests("SIMPLE");
    server.use(
      http.get("/api/plans/plan-1", () => HttpResponse.json({ id: "plan-1", name: "Herr A" })),
      http.get("/api/plans/plan-1/runs", () => HttpResponse.json([])),
    );

    renderExportPanel();

    expect(await screen.findByTestId("simple-save-export-card")).toBeInTheDocument();
    expect(screen.queryByTestId("export-card")).not.toBeInTheDocument();
    expect(screen.queryByTestId("anonymized-export-card")).not.toBeInTheDocument();
  });

  it("renders the full advanced surface, not SimpleSaveExportCard, in ADVANCED mode", async () => {
    setUiModeForTests("ADVANCED");
    server.use(http.get("/api/plans/plan-1/runs", () => HttpResponse.json([])));

    renderExportPanel();

    expect(await screen.findByTestId("export-card")).toBeInTheDocument();
    expect(screen.getByTestId("anonymized-export-card")).toBeInTheDocument();
    expect(screen.queryByTestId("simple-save-export-card")).not.toBeInTheDocument();
  });
});
