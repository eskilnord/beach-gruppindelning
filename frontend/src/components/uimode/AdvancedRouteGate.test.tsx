import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "../../test/server";
import { renderWithProviders } from "../../test/renderWithProviders";
import { setUiModeForTests } from "../../lib/uiMode/uiModeStore";
import type { UiMode } from "../../lib/uiMode/uiMode";
import { sv } from "../../i18n/sv";
import { AdvancedRouteGate } from "./AdvancedRouteGate";

describe("AdvancedRouteGate", () => {
  it("renders children unchanged in ADVANCED mode", () => {
    renderWithProviders(
      <AdvancedRouteGate tabLabel={sv.plan.tabs.fields}>gated-panel-content</AdvancedRouteGate>,
      { uiMode: "ADVANCED" },
    );

    expect(screen.getByText("gated-panel-content")).toBeInTheDocument();
    expect(screen.queryByTestId("ui-mode-route-gate")).not.toBeInTheDocument();
  });

  it("renders the gate card instead of children in SIMPLE mode", () => {
    renderWithProviders(
      <AdvancedRouteGate tabLabel={sv.plan.tabs.fields}>gated-panel-content</AdvancedRouteGate>,
      { uiMode: "SIMPLE" },
    );

    expect(screen.queryByText("gated-panel-content")).not.toBeInTheDocument();
    expect(screen.getByTestId("ui-mode-route-gate")).toBeInTheDocument();
    expect(screen.getByText(sv.uiMode.routeGate.title)).toBeInTheDocument();
    expect(screen.getByText(sv.uiMode.routeGate.body(sv.plan.tabs.fields))).toBeInTheDocument();
  });

  // v0.6.0 audit-fix A6: "Öppna avancerat läge" now opens the shared confirm modal
  // (useConfirmedAdvancedMode) instead of flipping the mode directly.
  it("the gate card's button opens the confirm modal; confirming flips the mode to ADVANCED, revealing children in place", async () => {
    server.use(
      http.put("/api/app-settings", async () => HttpResponse.json({ uiMode: "ADVANCED" })),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <AdvancedRouteGate tabLabel={sv.plan.tabs.fields}>gated-panel-content</AdvancedRouteGate>,
      { uiMode: "SIMPLE" },
    );

    await user.click(screen.getByTestId("ui-mode-gate-open-advanced"));
    const confirmModal = await screen.findByRole("dialog", { name: sv.uiMode.enableConfirm.title });
    await user.click(within(confirmModal).getByTestId("confirmed-advanced-mode-confirm"));

    expect(screen.getByText("gated-panel-content")).toBeInTheDocument();
    expect(screen.queryByTestId("ui-mode-route-gate")).not.toBeInTheDocument();
  });

  // v0.6.0 audit-fix A6: the second, default-weight escape hatch back to the plan's own step flow.
  it("'Tillbaka till mina steg' navigates to the plan's deltagare step", async () => {
    setUiModeForTests("SIMPLE" as UiMode);
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    render(
      <MantineProvider>
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={["/plans/plan-1/falt"]}>
            <Routes>
              <Route
                path="/plans/:planId/falt"
                element={<AdvancedRouteGate tabLabel={sv.plan.tabs.fields}>gated-panel-content</AdvancedRouteGate>}
              />
              <Route path="/plans/:planId/deltagare" element={<div data-testid="deltagare-stub" />} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>
      </MantineProvider>,
    );

    await user.click(screen.getByTestId("ui-mode-gate-back-to-steps"));

    expect(await screen.findByTestId("deltagare-stub")).toBeInTheDocument();
  });
});
