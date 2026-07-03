import { describe, expect, it } from "vitest";
import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { NewCoachModal } from "./NewCoachModal";

/**
 * v0.3.0 WI-3 smoke test: coachLevel/canCoachMin/maxGroupsPerDay/maxGroupsPerWeek/alsoParticipant
 * each gained a HelpTip via their `description` slot - none of these labels are asserted by exact
 * name in the e2e suite, but the slot pattern is kept uniform across the app regardless.
 */
describe("NewCoachModal help tips", () => {
  it("renders a HelpTip for each explained profile field", async () => {
    server.use(http.get("/api/persons", () => HttpResponse.json([])));

    renderWithProviders(<NewCoachModal planId="plan-1" opened onClose={() => {}} />);

    await screen.findByText("Ny tränare");

    const helpTips = screen.getAllByRole("button", { name: /^Förklaring:/ });
    // coachLevel + canCoachRange + maxGroupsPerDay + maxGroupsPerWeek + alsoParticipant = 5
    expect(helpTips.length).toBeGreaterThanOrEqual(5);
  });
});
