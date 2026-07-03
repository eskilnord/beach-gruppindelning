import { describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Modal } from "@mantine/core";
import { renderWithProviders } from "../test/renderWithProviders";
import { HelpTip } from "./HelpTip";

describe("HelpTip", () => {
  it("exposes the trigger via its aria-label and keeps the explanation hidden until opened", () => {
    renderWithProviders(<HelpTip label="Förklaring: Vikt">Vikten styr hur tungt regeln väger.</HelpTip>);

    expect(screen.getByRole("button", { name: "Förklaring: Vikt" })).toBeInTheDocument();
    expect(screen.queryByText("Vikten styr hur tungt regeln väger.")).not.toBeInTheDocument();
  });

  it("opens the explanation on click and closes it again on Escape", async () => {
    const user = userEvent.setup();
    renderWithProviders(<HelpTip label="Förklaring: Vikt">Vikten styr hur tungt regeln väger.</HelpTip>);

    await user.click(screen.getByRole("button", { name: "Förklaring: Vikt" }));
    expect(await screen.findByText("Vikten styr hur tungt regeln väger.")).toBeInTheDocument();

    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByText("Vikten styr hur tungt regeln väger.")).not.toBeInTheDocument());
  });

  it("is keyboard-reachable and opens on Enter", async () => {
    const user = userEvent.setup();
    renderWithProviders(<HelpTip label="Förklaring: Antal banor">Styr hur många grupper som får plats.</HelpTip>);

    await user.tab();
    expect(screen.getByRole("button", { name: "Förklaring: Antal banor" })).toHaveFocus();

    await user.keyboard("{Enter}");
    expect(await screen.findByText("Styr hur många grupper som får plats.")).toBeInTheDocument();
  });

  // v0.3.0 WI-3 review fix (regression test): several HelpTips live inside Mantine Modals
  // (NewFieldModal, TimeSlotModal, EditPlanModal...) whose own closeOnEscape listens on WINDOW
  // KEYDOWN IN THE CAPTURE PHASE (ModalBase use-modal.mjs) - it fires before any element-level
  // handler, so an Escape meant for the popover would dismiss the whole modal too, losing the
  // user's half-filled form. Mantine's opt-out is the `data-mantine-stop-propagation` attribute on
  // `event.target` (the same convention Menu/Combobox dropdowns use); HelpTip sets it on its
  // dropdown, which trapFocus guarantees holds focus while open.
  it("does not close a surrounding Mantine Modal when Escape closes the popover", async () => {
    const onModalClose = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <Modal opened onClose={onModalClose} title="Testmodal">
        <HelpTip label="Förklaring: Vikt">Hjälptext.</HelpTip>
      </Modal>,
    );

    await user.click(screen.getByRole("button", { name: "Förklaring: Vikt" }));
    const helpText = await screen.findByText("Hjälptext.");
    // trapFocus moves focus into the dropdown asynchronously (setTimeout in use-focus-trap) - the
    // Escape below must actually land there, as it would for a real keyboard user.
    await waitFor(() => {
      expect(helpText.closest('[role="dialog"]')).toContainElement(document.activeElement as HTMLElement);
    });

    await user.keyboard("{Escape}");

    // The popover closes...
    await waitFor(() => expect(screen.queryByText("Hjälptext.")).not.toBeInTheDocument());
    // ...but the modal must survive the same keypress.
    expect(onModalClose).not.toHaveBeenCalled();
  });

  // Companion to the modal test above: the static data-mantine-stop-propagation attribute lives on
  // the DROPDOWN only, never the trigger - Escape pressed with the popover CLOSED (focus on the
  // trigger) must still reach the modal and close it, as any other Escape in the form would.
  it("still lets Escape close the surrounding Modal when the popover is closed", async () => {
    const onModalClose = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <Modal opened onClose={onModalClose} title="Testmodal">
        <HelpTip label="Förklaring: Vikt">Hjälptext.</HelpTip>
      </Modal>,
    );

    await screen.findByRole("button", { name: "Förklaring: Vikt" });
    await user.keyboard("{Escape}");

    expect(onModalClose).toHaveBeenCalled();
  });
});
