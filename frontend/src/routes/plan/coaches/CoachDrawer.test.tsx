import { describe, expect, it } from "vitest";
import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { CoachDrawer } from "./CoachDrawer";
import type { CoachRow } from "./coachRow";

const COACH: CoachRow = {
  id: "coach-1",
  personId: "person-1",
  activityPlanId: "plan-1",
  canAlsoTrainAsParticipant: false,
  name: "Karin Lindqvist",
};

/**
 * v0.3.0 WI-3 smoke test: the drawer's profile fields (coachLevel/canCoachMin/maxGroupsPerDay/
 * maxGroupsPerWeek/alsoParticipant) each gained a `description`-slot HelpTip, and the availability
 * matrix's "Tillgänglighet" heading gained a section-level one explaining the 4-state semantics
 * (Okänd/Tillgänglig are neutral, Otillgänglig is hard, Föredrar is a soft bonus - verified against
 * CoachTimeSlot.java/GroupPlanConstraintProvider.java, not guessed).
 */
describe("CoachDrawer help tips", () => {
  it("renders a HelpTip for each explained profile field plus the availability section", async () => {
    server.use(
      http.get("/api/plans/plan-1/time-slots", () => HttpResponse.json([])),
      http.get("/api/plans/plan-1/coaches/coach-1/availability", () => HttpResponse.json([])),
      http.get("/api/plans/plan-1/field-definitions", () => HttpResponse.json([])),
      http.get("/api/plans/plan-1/coaches/coach-1/field-values", () => HttpResponse.json([])),
    );

    renderWithProviders(
      <CoachDrawer planId="plan-1" coach={COACH} allParticipants={[]} allCoaches={[]} onClose={() => {}} />,
    );

    await screen.findByText("Tillgänglighet");

    const helpTips = screen.getAllByRole("button", { name: /^Förklaring:/ });
    // coachLevel + alsoParticipant + canCoachRange + maxGroupsPerDay + maxGroupsPerWeek + availability = 6
    expect(helpTips.length).toBeGreaterThanOrEqual(6);
  });
});
