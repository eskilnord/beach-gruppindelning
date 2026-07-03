import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
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
