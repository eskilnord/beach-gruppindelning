import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { sv } from "../../../i18n/sv";
import { CoachDrawer } from "./CoachDrawer";
import type { CoachRow } from "./coachRow";

const COACH: CoachRow = {
  id: "coach-1",
  personId: "person-1",
  activityPlanId: "plan-1",
  canAlsoTrainAsParticipant: false,
  reviewedDone: false,
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

/**
 * M7 review finding: a failed availability GET must not surface an empty EDITABLE matrix - the
 * initializing useEffect never populated availabilityDraft from server data, so Save would PUT an
 * empty entries array and CoachController#putAvailability (delete-all-then-insert) would wipe the
 * coach's real availability. The drawer must show an error state instead and disable Save entirely.
 */
describe("CoachDrawer availability load failure", () => {
  it("shows an error alert instead of an editable matrix and disables Save when the availability GET fails", async () => {
    server.use(
      http.get("/api/plans/plan-1/time-slots", () => HttpResponse.json([{ id: "slot-1", label: "Mån 18-19" }])),
      http.get("/api/plans/plan-1/coaches/coach-1/availability", () => HttpResponse.json(null, { status: 500 })),
      http.get("/api/plans/plan-1/field-definitions", () => HttpResponse.json([])),
      http.get("/api/plans/plan-1/coaches/coach-1/field-values", () => HttpResponse.json([])),
    );

    renderWithProviders(
      <CoachDrawer planId="plan-1" coach={COACH} allParticipants={[]} allCoaches={[]} onClose={() => {}} />,
    );

    await screen.findByText(sv.coaches.drawer.availabilityLoadFailed);

    expect(screen.queryByRole("group", { name: "Mån 18-19" })).not.toBeInTheDocument();

    // Dirty the drawer via a profile field: without the availabilityLoadFailed guard the isDirty
    // check alone would re-enable Save, so this proves the guard rather than the default state.
    const user = userEvent.setup();
    await user.clear(screen.getByLabelText(sv.coaches.drawer.coachLevelLabel));
    await user.type(screen.getByLabelText(sv.coaches.drawer.coachLevelLabel), "500");
    expect(screen.getByRole("button", { name: sv.coaches.drawer.saveButton })).toBeDisabled();
    expect(screen.getByRole("button", { name: sv.coaches.drawer.retryButton })).toBeInTheDocument();
  });
});

/**
 * WP3 ("Spara och markera som färdig"): the drawer's "done" footer button flips depending on
 * {@code coach.reviewedDone}. `reviewedDone` is sent as its OWN trailing PATCH, only after any
 * dirty profile diff (and availability/custom fields) has already been saved successfully - never
 * folded into the same PATCH - so a mid-chain failure never leaves the row falsely "Klarmarkerad".
 */
describe("CoachDrawer done workflow", () => {
  it("sends reviewedDone: true in the profile PATCH when marking an undone coach as done", async () => {
    let patchBody: unknown;
    server.use(
      http.get("/api/plans/plan-1/time-slots", () => HttpResponse.json([])),
      http.get("/api/plans/plan-1/coaches/coach-1/availability", () => HttpResponse.json([])),
      http.get("/api/plans/plan-1/field-definitions", () => HttpResponse.json([])),
      http.get("/api/plans/plan-1/coaches/coach-1/field-values", () => HttpResponse.json([])),
      http.patch("/api/coaches/coach-1", async ({ request }) => {
        patchBody = await request.json();
        return HttpResponse.json({ ...COACH, reviewedDone: true });
      }),
    );

    renderWithProviders(
      <CoachDrawer planId="plan-1" coach={COACH} allParticipants={[]} allCoaches={[]} onClose={() => {}} />,
    );

    const doneButton = await screen.findByRole("button", { name: sv.coaches.drawer.saveAndMarkDoneButton });
    expect(doneButton).toBeEnabled(); // Always enabled, even with zero edits - the primary flow.
    const user = userEvent.setup();
    await user.click(doneButton);

    await screen.findByText(sv.coaches.drawer.saveSuccess);
    expect(patchBody).toEqual({ reviewedDone: true });
  });

  it("shows the unmark-done button and doneIndicator for an already-done coach", async () => {
    server.use(
      http.get("/api/plans/plan-1/time-slots", () => HttpResponse.json([])),
      http.get("/api/plans/plan-1/coaches/coach-1/availability", () => HttpResponse.json([])),
      http.get("/api/plans/plan-1/field-definitions", () => HttpResponse.json([])),
      http.get("/api/plans/plan-1/coaches/coach-1/field-values", () => HttpResponse.json([])),
    );

    renderWithProviders(
      <CoachDrawer
        planId="plan-1"
        coach={{ ...COACH, reviewedDone: true }}
        allParticipants={[]}
        allCoaches={[]}
        onClose={() => {}}
      />,
    );

    await screen.findByRole("button", { name: sv.coaches.drawer.unmarkDoneButton });
    expect(screen.queryByRole("button", { name: sv.coaches.drawer.saveAndMarkDoneButton })).not.toBeInTheDocument();
    expect(screen.getByText(sv.coaches.drawer.doneIndicator)).toBeInTheDocument();
  });

  it("sends a dirty profile field PATCH first, then a separate trailing reviewedDone: true PATCH", async () => {
    const patchBodies: unknown[] = [];
    server.use(
      http.get("/api/plans/plan-1/time-slots", () => HttpResponse.json([])),
      http.get("/api/plans/plan-1/coaches/coach-1/availability", () => HttpResponse.json([])),
      http.get("/api/plans/plan-1/field-definitions", () => HttpResponse.json([])),
      http.get("/api/plans/plan-1/coaches/coach-1/field-values", () => HttpResponse.json([])),
      http.patch("/api/coaches/coach-1", async ({ request }) => {
        const body = await request.json();
        patchBodies.push(body);
        return HttpResponse.json({ ...COACH, coachLevel: 500, reviewedDone: true });
      }),
    );

    renderWithProviders(
      <CoachDrawer planId="plan-1" coach={COACH} allParticipants={[]} allCoaches={[]} onClose={() => {}} />,
    );

    const user = userEvent.setup();
    await user.clear(await screen.findByLabelText(sv.coaches.drawer.coachLevelLabel));
    await user.type(screen.getByLabelText(sv.coaches.drawer.coachLevelLabel), "500");
    await user.click(screen.getByRole("button", { name: sv.coaches.drawer.saveAndMarkDoneButton }));

    // Two SEPARATE PATCHes, in order: the dirty profile diff first, then the reviewedDone flip as
    // its own trailing call - never folded together (see handleSave's doc comment).
    await waitFor(() => expect(patchBodies).toEqual([{ coachLevel: 500 }, { reviewedDone: true }]));
  });
});
