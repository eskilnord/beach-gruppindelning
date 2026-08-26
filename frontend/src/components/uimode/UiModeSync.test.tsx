import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse, delay } from "msw";
import { server } from "../../test/server";
import { renderWithProviders } from "../../test/renderWithProviders";
import { UiModeSync } from "./UiModeSync";
import { useUiMode } from "../../lib/uiMode/useUiMode";
import { useUiModeStore } from "../../lib/uiMode/uiModeStore";

/** Minimal harness: exposes the current mode + a button driving the real useUiMode().setMode (the
 *  same optimistic store-write + PUT path a real toggle uses), alongside the real <UiModeSync />. */
function Harness() {
  const { mode, setMode } = useUiMode();
  return (
    <div>
      <span data-testid="current-mode">{mode}</span>
      <button onClick={() => setMode("ADVANCED")}>toggle</button>
      <UiModeSync />
    </div>
  );
}

describe("UiModeSync", () => {
  it("a backend GET resolving AFTER a user toggle does not revert the user's choice (B3 reconcile policy)", async () => {
    server.use(
      http.put("/api/app-settings", () => HttpResponse.json({ uiMode: "ADVANCED" })),
      http.get("/api/app-settings", async () => {
        // The GET is deliberately slow, resolving well after the user's own toggle below, carrying
        // a stale backend value ("SIMPLE") that must NOT overwrite the user's later choice.
        await delay(80);
        return HttpResponse.json({ uiMode: "SIMPLE" });
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(<Harness />, { uiMode: "SIMPLE" });

    expect(screen.getByTestId("current-mode")).toHaveTextContent("SIMPLE");

    await user.click(screen.getByText("toggle"));
    expect(screen.getByTestId("current-mode")).toHaveTextContent("ADVANCED");

    // Give the slow GET plenty of time to resolve and for UiModeSync's effect to run.
    await new Promise((resolve) => setTimeout(resolve, 150));

    expect(screen.getByTestId("current-mode")).toHaveTextContent("ADVANCED");
    expect(useUiModeStore.getState().mode).toBe("ADVANCED");
  });

  it("reconciles a differing backend value when the user has NOT changed the mode this session", async () => {
    server.use(http.get("/api/app-settings", () => HttpResponse.json({ uiMode: "ADVANCED" })));

    renderWithProviders(<Harness />, { uiMode: "SIMPLE" });

    expect(screen.getByTestId("current-mode")).toHaveTextContent("SIMPLE");
    await waitFor(() => expect(screen.getByTestId("current-mode")).toHaveTextContent("ADVANCED"));
  });
});
