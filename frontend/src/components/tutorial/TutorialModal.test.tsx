import { act } from "react";
import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../../test/renderWithProviders";
import { sv } from "../../i18n/sv";
import { setUiModeForTests } from "../../lib/uiMode/uiModeStore";
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

  // v0.6.0 F6 review fix (FIX 6, MINOR): a mode flip while the modal is open (the header's mode
  // switch/badge stay reachable independently of this modal) swaps `steps`/`stepConfig` for the
  // SHORTER 6-entry SIMPLE arrays. Without clamping the `active` index, staying on a deep ADVANCED
  // step (e.g. index 8 - beyond SIMPLE's 6 entries) would index past the end of those arrays and
  // crash the render.
  it("clamps the active step index (never crashes) when the mode flips to SIMPLE mid-walkthrough on a deep step", async () => {
    const user = userEvent.setup();
    renderWithProviders(<TutorialModal opened planId="plan-1" onClose={() => {}} />);

    // Step index 8 ("Lås & kör om") - beyond SIMPLE's 6-entry array (valid indices 0-5).
    for (let i = 0; i < 8; i += 1) {
      await user.click(screen.getByRole("button", { name: sv.tutorial.nextButton }));
    }
    expect(screen.getByTestId("tutorial-active-step-title")).toHaveTextContent(sv.tutorial.steps[8].title);

    act(() => setUiModeForTests("SIMPLE"));

    // Clamped to the last valid SIMPLE index (5, "Resultat & export") instead of crashing.
    expect(screen.getByTestId("tutorial-active-step-title")).toHaveTextContent(sv.tutorial.simpleSteps[5].title);
    expect(screen.getByText(sv.tutorial.stepLabel(6, 6))).toBeInTheDocument();
  });
});

// v0.6.0 F6 (M-S6): SIMPLE mode gets its own 6-step walkthrough instead of the 10-step ADVANCED one
// tested above (which stays completely untouched - same renderWithProviders default).
describe("TutorialModal (SIMPLE mode)", () => {
  it("renders the 6-step sv.tutorial.simpleSteps walkthrough instead of the 10-step ADVANCED one", async () => {
    renderWithProviders(<TutorialModal opened planId="plan-1" onClose={() => {}} />, { uiMode: "SIMPLE" });

    expect(sv.tutorial.simpleSteps).toHaveLength(6);
    expect(screen.getByTestId("tutorial-active-step-title")).toHaveTextContent(sv.tutorial.simpleSteps[0].title);
    expect(screen.getByText(sv.tutorial.stepLabel(1, 6))).toBeInTheDocument();
  });

  it("'Ta mig dit' on the Prioriteringar step navigates straight to the plan's prioriteringar route", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    navigateMock.mockClear();
    renderWithProviders(<TutorialModal opened planId="plan-1" onClose={onClose} />, { uiMode: "SIMPLE" });

    // Step index 3 = "Prioriteringar" (sv.tutorial.simpleSteps).
    await user.click(screen.getByRole("button", { name: sv.tutorial.nextButton }));
    await user.click(screen.getByRole("button", { name: sv.tutorial.nextButton }));
    await user.click(screen.getByRole("button", { name: sv.tutorial.nextButton }));
    expect(screen.getByTestId("tutorial-active-step-title")).toHaveTextContent("Prioriteringar");

    await user.click(screen.getByTestId("tutorial-go-there"));
    expect(navigateMock).toHaveBeenCalledWith("/plans/plan-1/prioriteringar");
    expect(onClose).toHaveBeenCalled();
  });

  it("shows 'Klar' on the last (6th) step", async () => {
    const user = userEvent.setup();
    renderWithProviders(<TutorialModal opened planId="plan-1" onClose={() => {}} />, { uiMode: "SIMPLE" });

    for (let i = 0; i < 5; i += 1) {
      await user.click(screen.getByRole("button", { name: sv.tutorial.nextButton }));
    }
    expect(screen.getByTestId("tutorial-active-step-title")).toHaveTextContent("Resultat & export");
    expect(screen.queryByRole("button", { name: sv.tutorial.nextButton })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: sv.tutorial.doneButton })).toBeInTheDocument();
  });
});
