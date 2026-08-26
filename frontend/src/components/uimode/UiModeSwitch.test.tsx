import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../../test/server";
import { renderWithProviders } from "../../test/renderWithProviders";
import { sv } from "../../i18n/sv";
import { UiModeSwitch } from "./UiModeSwitch";

describe("UiModeSwitch", () => {
  it("cancelling the enable-confirm modal keeps SIMPLE mode and never fires the PUT", async () => {
    let putCallCount = 0;
    server.use(
      http.put("/api/app-settings", () => {
        putCallCount++;
        return HttpResponse.json({ uiMode: "ADVANCED" });
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(<UiModeSwitch />, { uiMode: "SIMPLE" });

    const toggle = screen.getByRole("switch", { name: sv.uiMode.switchAriaLabel });
    expect(toggle).not.toBeChecked();

    await user.click(toggle);
    const confirmModal = await screen.findByRole("dialog", { name: sv.uiMode.enableConfirm.title });
    expect(confirmModal).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: sv.uiMode.enableConfirm.cancel }));

    await waitFor(() => expect(screen.queryByRole("dialog", { name: sv.uiMode.enableConfirm.title })).not.toBeInTheDocument());
    expect(toggle).not.toBeChecked();
    expect(putCallCount).toBe(0);
  });

  it("toggling ADVANCED -> SIMPLE is friction-free: no confirm modal", async () => {
    server.use(http.put("/api/app-settings", () => HttpResponse.json({ uiMode: "SIMPLE" })));

    const user = userEvent.setup();
    renderWithProviders(<UiModeSwitch />, { uiMode: "ADVANCED" });

    const toggle = screen.getByRole("switch", { name: sv.uiMode.switchAriaLabel });
    expect(toggle).toBeChecked();

    await user.click(toggle);

    expect(screen.queryByRole("dialog", { name: sv.uiMode.enableConfirm.title })).not.toBeInTheDocument();
    await waitFor(() => expect(toggle).not.toBeChecked());
  });
});
