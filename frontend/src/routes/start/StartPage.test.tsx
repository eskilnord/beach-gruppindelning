import { describe, expect, it } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MantineProvider } from "@mantine/core";
import { Notifications } from "@mantine/notifications";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { server } from "../../test/server";
import { renderWithProviders } from "../../test/renderWithProviders";
import { StartPage } from "./StartPage";
import { sv } from "../../i18n/sv";

/** StartPage navigates to a real plan route after creating demo data, so (unlike
 *  renderWithProviders' route-less MemoryRouter) this needs an actual matched destination route to
 *  assert navigation happened - same pattern as CapacityPanel.test.tsx. */
function renderStartPageWithRouting() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  });
  return render(
    <MantineProvider>
      <Notifications />
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={["/"]}>
          <Routes>
            <Route path="/" element={<StartPage />} />
            <Route path="/plans/:planId/deltagare" element={<div data-testid="participants-stub" />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

const SEASON = {
  id: "season-1",
  name: "VT27",
  startDate: null,
  endDate: null,
  status: "active",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

describe("StartPage", () => {
  it("renders the heading, actions, and existing seasons", async () => {
    server.use(
      http.get("/api/seasons", () => HttpResponse.json([SEASON])),
      http.get("/api/seasons/season-1/plans", () => HttpResponse.json([])),
    );

    renderWithProviders(<StartPage />);

    expect(screen.getByRole("heading", { name: sv.start.heading })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: sv.start.createSeasonButton })).toBeInTheDocument();

    const importButton = screen.getByRole("button", { name: sv.start.importButton });
    expect(importButton).toBeEnabled();

    await waitFor(() => {
      expect(screen.getByText("VT27")).toBeInTheDocument();
    });
  });

  it("shows a required-name validation error when submitting the create-season form empty", async () => {
    server.use(
      http.get("/api/seasons", () => HttpResponse.json([])),
      http.post("/api/seasons", () => {
        throw new Error("must not be called when validation fails");
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(<StartPage />);

    await user.click(screen.getByRole("button", { name: sv.start.createSeasonButton }));

    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: sv.createSeasonModal.submit }));

    expect(await within(dialog).findByText(sv.common.nameRequired)).toBeInTheDocument();
  });

  it("opens the import entry modal (season/plan picker) from 'Importera ny fil'", async () => {
    server.use(
      http.get("/api/seasons", () => HttpResponse.json([SEASON])),
      http.get("/api/seasons/season-1/plans", () => HttpResponse.json([])),
    );

    const user = userEvent.setup();
    renderWithProviders(<StartPage />);

    await user.click(screen.getByRole("button", { name: sv.start.importButton }));

    const dialog = await screen.findByRole("dialog", { name: sv.importEntry.title });
    expect(within(dialog).getByText(sv.importEntry.seasonLabel)).toBeInTheDocument();
    // No season chosen yet, so the plan picker and continue button aren't actionable.
    expect(within(dialog).getByRole("button", { name: sv.importEntry.continueButton })).toBeDisabled();
  });

  it("shows the demo-data CTA both in the empty state and as the always-available header button", async () => {
    server.use(http.get("/api/seasons", () => HttpResponse.json([])));

    renderWithProviders(<StartPage />);

    // Always-available header button, regardless of whether any seasons exist yet.
    expect(screen.getByTestId("load-demo-data")).toBeInTheDocument();

    // Prominent empty-state CTA, only shown when there are zero seasons.
    const emptyStateButton = await screen.findByTestId("load-demo-data-empty-state");
    expect(emptyStateButton).toBeInTheDocument();
    expect(screen.getByText(sv.start.demoDataEmptyStateBody)).toBeInTheDocument();

    expect(screen.getAllByRole("button", { name: sv.start.demoDataButton })).toHaveLength(2);
  });

  it("creates demo data via POST /api/demo, shows a success notification, and navigates to the new plan's participants tab", async () => {
    server.use(
      http.get("/api/seasons", () => HttpResponse.json([])),
      http.post("/api/demo", () =>
        HttpResponse.json({ seasonId: "demo-season-1", planId: "demo-plan-1" }, { status: 201 }),
      ),
    );

    const user = userEvent.setup();
    renderStartPageWithRouting();

    await user.click(await screen.findByTestId("load-demo-data"));

    expect(await screen.findByTestId("participants-stub")).toBeInTheDocument();
    expect(await screen.findByText(sv.start.demoDataSuccess)).toBeInTheDocument();
  });

  it("shows an error notification and does not navigate when demo-data creation fails", async () => {
    server.use(
      http.get("/api/seasons", () => HttpResponse.json([SEASON])),
      http.get("/api/seasons/season-1/plans", () => HttpResponse.json([])),
      http.post("/api/demo", () => HttpResponse.json({ error: "Kunde inte skapa demodata just nu" }, { status: 500 })),
    );

    const user = userEvent.setup();
    renderStartPageWithRouting();

    await user.click(await screen.findByTestId("load-demo-data"));

    expect(await screen.findByText("Kunde inte skapa demodata just nu")).toBeInTheDocument();
    expect(screen.queryByTestId("participants-stub")).not.toBeInTheDocument();
  });
});
