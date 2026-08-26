import { describe, expect, it } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../../test/server";
import { renderWithProviders } from "../../test/renderWithProviders";
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

  it("the gate card's button flips the mode to ADVANCED, revealing children in place", async () => {
    server.use(
      http.put("/api/app-settings", async () => HttpResponse.json({ uiMode: "ADVANCED" })),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <AdvancedRouteGate tabLabel={sv.plan.tabs.fields}>gated-panel-content</AdvancedRouteGate>,
      { uiMode: "SIMPLE" },
    );

    await user.click(screen.getByTestId("ui-mode-gate-open-advanced"));

    expect(screen.getByText("gated-panel-content")).toBeInTheDocument();
    expect(screen.queryByTestId("ui-mode-route-gate")).not.toBeInTheDocument();
  });
});
