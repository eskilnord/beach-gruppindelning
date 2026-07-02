import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../../../../test/renderWithProviders";
import { sv } from "../../../../i18n/sv";
import type { IndirectFactorView } from "../../../../api/types";
import { IndirectFactorsSection } from "./IndirectFactorsSection";

const FACTOR: IndirectFactorView = {
  otherParticipantProfileId: "participant-lisa",
  otherPersonName: "Lisa Larsson",
  coachPersonName: "Anna Andersson",
  coachWishType: "MUST",
  groupName: "Grupp A",
  messageSv:
    "Kalle Karlsson:s placering kan delvis bero på att Lisa Larsson (önskad medspelare) behöver tränaren Anna Andersson, som är knuten till Grupp A",
};

describe("IndirectFactorsSection", () => {
  it("shows the empty state when there are no indirect factors", () => {
    renderWithProviders(<IndirectFactorsSection indirectFactors={[]} onNavigateToParticipant={() => {}} />);

    expect(screen.getByText(sv.results.explain.indirectFactorsHeading)).toBeInTheDocument();
    expect(screen.getByText(sv.results.explain.noIndirectFactors)).toBeInTheDocument();
    expect(screen.queryByTestId("explain-indirect-factor")).not.toBeInTheDocument();
  });

  it("renders one row per indirect factor with the backend's finished Swedish sentence", () => {
    renderWithProviders(<IndirectFactorsSection indirectFactors={[FACTOR]} onNavigateToParticipant={() => {}} />);

    const rows = screen.getAllByTestId("explain-indirect-factor");
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent(FACTOR.messageSv);
    expect(screen.queryByText(sv.results.explain.noIndirectFactors)).not.toBeInTheDocument();
  });

  it("renders a row per factor when there is more than one", () => {
    const second: IndirectFactorView = {
      ...FACTOR,
      otherParticipantProfileId: "participant-erik",
      otherPersonName: "Erik Eriksson",
      coachWishType: "WANT",
      messageSv: "Kalle Karlsson:s placering kan delvis bero på att Erik Eriksson (önskad medspelare) önskar tränaren Anna Andersson, som är knuten till Grupp A",
    };
    renderWithProviders(<IndirectFactorsSection indirectFactors={[FACTOR, second]} onNavigateToParticipant={() => {}} />);

    expect(screen.getAllByTestId("explain-indirect-factor")).toHaveLength(2);
  });

  it("navigates to the wish partner's own explanation via the link button", async () => {
    const onNavigateToParticipant = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<IndirectFactorsSection indirectFactors={[FACTOR]} onNavigateToParticipant={onNavigateToParticipant} />);

    await user.click(screen.getByRole("button", { name: sv.results.explain.friendExplanationLink(FACTOR.otherPersonName) }));

    expect(onNavigateToParticipant).toHaveBeenCalledWith(FACTOR.otherParticipantProfileId, FACTOR.otherPersonName);
  });
});
