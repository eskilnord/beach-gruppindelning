import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../../test/renderWithProviders";
import { sv } from "../../i18n/sv";
import { TutorialModal } from "./TutorialModal";

const navigateMock = vi.fn();
vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual<typeof import("react-router-dom")>("react-router-dom");
  return { ...actual, useNavigate: () => navigateMock };
});

describe("TutorialModal", () => {
  it("steps forward and back through the workflow stages", async () => {
    const user = userEvent.setup();
    renderWithProviders(<TutorialModal opened planId="plan-1" onClose={() => {}} />);

    expect(screen.getByTestId("tutorial-active-step-title")).toHaveTextContent(sv.tutorial.steps[0].title);

    await user.click(screen.getByRole("button", { name: sv.tutorial.nextButton }));
    expect(screen.getByTestId("tutorial-active-step-title")).toHaveTextContent(sv.tutorial.steps[1].title);

    await user.click(screen.getByRole("button", { name: sv.tutorial.nextButton }));
    expect(screen.getByTestId("tutorial-active-step-title")).toHaveTextContent(sv.tutorial.steps[2].title);

    await user.click(screen.getByRole("button", { name: sv.tutorial.prevButton }));
    expect(screen.getByTestId("tutorial-active-step-title")).toHaveTextContent(sv.tutorial.steps[1].title);
  });

  it("'Ta mig dit' navigates to the step's target and closes the modal", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    navigateMock.mockClear();
    renderWithProviders(<TutorialModal opened planId="plan-1" onClose={onClose} />);

    // Step index 2 ("Strukturera fält") targets the Deltagare tab.
    await user.click(screen.getByRole("button", { name: sv.tutorial.nextButton }));
    await user.click(screen.getByRole("button", { name: sv.tutorial.nextButton }));

    await user.click(screen.getByTestId("tutorial-go-there"));
    expect(navigateMock).toHaveBeenCalledWith("/plans/plan-1/deltagare");
    expect(onClose).toHaveBeenCalled();
  });

  it("disables 'Ta mig dit' with a tooltip when no plan is active, except for the first step", async () => {
    const user = userEvent.setup();
    renderWithProviders(<TutorialModal opened planId={undefined} onClose={() => {}} />);

    // Step 0 ("Säsong & plan") targets Startvy, always reachable.
    expect(screen.getByTestId("tutorial-go-there")).toBeEnabled();

    // Step 1 ("Importera anmälningar") needs an active plan.
    await user.click(screen.getByRole("button", { name: sv.tutorial.nextButton }));
    expect(screen.getByTestId("tutorial-go-there")).toBeDisabled();
  });

  it("shows 'Klar' on the last step and calls onClose when clicked", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderWithProviders(<TutorialModal opened planId="plan-1" onClose={onClose} />);

    const totalSteps = sv.tutorial.steps.length;
    for (let i = 0; i < totalSteps - 1; i += 1) {
      await user.click(screen.getByRole("button", { name: sv.tutorial.nextButton }));
    }
    expect(screen.getByTestId("tutorial-active-step-title")).toHaveTextContent(sv.tutorial.steps[totalSteps - 1].title);
    expect(screen.queryByRole("button", { name: sv.tutorial.nextButton })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: sv.tutorial.doneButton }));
    expect(onClose).toHaveBeenCalled();
  });
});
