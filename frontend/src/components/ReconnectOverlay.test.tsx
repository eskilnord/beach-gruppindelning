import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../test/server";
import { renderWithProviders } from "../test/renderWithProviders";
import { sv } from "../i18n/sv";
import { ReconnectOverlay } from "./ReconnectOverlay";

const restartBackendMock = vi.hoisted(() => vi.fn());

vi.mock("../api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/client")>();
  return {
    ...actual,
    restartBackend: restartBackendMock,
  };
});

describe("ReconnectOverlay", () => {
  beforeEach(() => {
    restartBackendMock.mockReset();
    // Every test in this file exercises the "backend unreachable" branch, so the health poll
    // always reports it down (docs/design/01-architecture.md §4 failure UX).
    server.use(http.get("/api/health", () => HttpResponse.json({ status: "DOWN" }, { status: 503 })));
  });

  it("renders nothing while the backend is healthy", async () => {
    server.use(http.get("/api/health", () => HttpResponse.json({ status: "UP" })));

    renderWithProviders(<ReconnectOverlay />);

    await waitFor(() => expect(screen.queryByText(sv.backendStatus.reconnecting)).not.toBeInTheDocument());
    expect(screen.queryByRole("button", { name: sv.backendStatus.retryButton })).not.toBeInTheDocument();
  });

  it("calls restartBackend() and shows a pending state while the retry is in flight", async () => {
    const user = userEvent.setup();
    let resolveRestart!: () => void;
    restartBackendMock.mockReturnValue(
      new Promise<void>((resolve) => {
        resolveRestart = resolve;
      }),
    );

    renderWithProviders(<ReconnectOverlay />);

    const button = await screen.findByRole("button", { name: sv.backendStatus.retryButton });
    await user.click(button);

    expect(restartBackendMock).toHaveBeenCalledTimes(1);
    expect(button).toHaveAttribute("data-loading", "true");

    resolveRestart();
    await waitFor(() => expect(button).not.toHaveAttribute("data-loading"));
  });

  it("shows an error message when restartBackend() rejects", async () => {
    const user = userEvent.setup();
    restartBackendMock.mockRejectedValue(new Error("respawn failed"));

    renderWithProviders(<ReconnectOverlay />);

    const button = await screen.findByRole("button", { name: sv.backendStatus.retryButton });
    await user.click(button);

    expect(await screen.findByText(sv.backendStatus.restartFailed)).toBeInTheDocument();
    await waitFor(() => expect(button).not.toHaveAttribute("data-loading"));
  });
});
