import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../../test/server";
import { renderWithProviders } from "../../test/renderWithProviders";
import { StartPage } from "./StartPage";
import { sv } from "../../i18n/sv";

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
});
