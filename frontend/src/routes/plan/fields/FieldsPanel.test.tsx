import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { FieldsPanel } from "./FieldsPanel";
import type { FieldDefinition } from "../../../api/types";

/** FieldsPanel reads planId via useParams, so - like CapacityPanel.test.tsx - this needs a real
 *  matched route rather than the plain renderWithProviders helper. */
function renderFieldsPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={["/plans/plan-1/falt"]}>
          <Routes>
            <Route path="/plans/:planId/falt" element={<FieldsPanel />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

const FIELD: FieldDefinition = {
  id: "field-playwith",
  key: "playWith",
  label: "Vill spela med",
  fieldType: "personRelation",
  isStandard: true,
  storageKind: "CUSTOM",
  affectsOptimization: true,
  constraintType: "SAME_GROUP",
  hardOrSoft: "SOFT",
  weight: 80,
};

/**
 * v0.3.0 WI-3 smoke test: the "Alla fält" table header row gained one HelpTip per explained column
 * (Påverkar optimering/Constraint/Hård-Mjuk/Vikt/Förklaringstext) plus a section-level one on the
 * "Fältbyggare" heading. Only asserts the trigger buttons render - copy is covered by sv.ts, not
 * snapshotted here.
 */
describe("FieldsPanel help tips", () => {
  it("renders the section heading HelpTip plus one per explained table column", async () => {
    server.use(http.get("/api/plans/plan-1/field-definitions", () => HttpResponse.json([FIELD])));

    renderFieldsPanel();

    await screen.findByText("Vill spela med");

    const helpTips = screen.getAllByRole("button", { name: /^Förklaring:/ });
    // heading + affectsOptimization + constraint + hardOrSoft + weight + explanation = 6
    expect(helpTips.length).toBeGreaterThanOrEqual(6);
  });
});
