import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { sv } from "../../../i18n/sv";
import { ParticipantDrawer } from "./ParticipantDrawer";
import type { ParticipantRow } from "./participantRow";

const PARTICIPANT: ParticipantRow = {
  id: "participant-1",
  personId: "person-1",
  activityPlanId: "plan-1",
  manualReviewFlag: false,
  waitlisted: false,
  reviewedDone: false,
  name: "Karin Lindqvist",
};

function mockDrawerEndpoints() {
  server.use(
    http.get("/api/plans/plan-1/field-definitions", () => HttpResponse.json([])),
    http.get("/api/plans/plan-1/participants/participant-1/field-values", () => HttpResponse.json([])),
    http.get("/api/plans/plan-1/coaches", () => HttpResponse.json([])),
    http.get("/api/persons", () => HttpResponse.json([])),
    http.get("/api/plans/plan-1/time-slots", () => HttpResponse.json([])),
  );
}

/**
 * WP3 ("Spara och markera som färdig"): the drawer's "done" footer button flips depending on
 * {@code participant.reviewedDone}. `reviewedDone` is sent as its OWN trailing PATCH, only after
 * any dirty structured-field diff has already been saved successfully - never folded into the same
 * PATCH - so a mid-chain failure never leaves the row falsely "Klarmarkerad". The done button is
 * always enabled, even with zero edits, since marking done with no changes is the primary flow.
 */
describe("ParticipantDrawer done workflow", () => {
  it("sends reviewedDone: true in the participant PATCH when marking an undone participant as done", async () => {
    mockDrawerEndpoints();
    let patchBody: unknown;
    server.use(
      http.patch("/api/participants/participant-1", async ({ request }) => {
        patchBody = await request.json();
        return HttpResponse.json({ ...PARTICIPANT, reviewedDone: true });
      }),
    );

    renderWithProviders(
      <ParticipantDrawer planId="plan-1" participant={PARTICIPANT} allParticipants={[PARTICIPANT]} onClose={() => {}} />,
    );

    const doneButton = await screen.findByRole("button", { name: sv.participants.drawer.saveAndMarkDoneButton });
    expect(doneButton).toBeEnabled();
    const user = userEvent.setup();
    await user.click(doneButton);

    await screen.findByText(sv.participants.drawer.saveSuccess);
    expect(patchBody).toEqual({ reviewedDone: true });
  });

  it("shows the unmark-done button and doneIndicator for an already-done participant", async () => {
    mockDrawerEndpoints();

    renderWithProviders(
      <ParticipantDrawer
        planId="plan-1"
        participant={{ ...PARTICIPANT, reviewedDone: true }}
        allParticipants={[PARTICIPANT]}
        onClose={() => {}}
      />,
    );

    await screen.findByRole("button", { name: sv.participants.drawer.unmarkDoneButton });
    expect(
      screen.queryByRole("button", { name: sv.participants.drawer.saveAndMarkDoneButton }),
    ).not.toBeInTheDocument();
    expect(screen.getByText(sv.participants.drawer.doneIndicator)).toBeInTheDocument();
  });

  it("sends a dirty structured field PATCH first, then a separate trailing reviewedDone: false PATCH when unmarking done", async () => {
    mockDrawerEndpoints();
    const patchBodies: unknown[] = [];
    server.use(
      http.patch("/api/participants/participant-1", async ({ request }) => {
        const body = await request.json();
        patchBodies.push(body);
        return HttpResponse.json({ ...PARTICIPANT, reviewedDone: false, waitlisted: true });
      }),
    );

    renderWithProviders(
      <ParticipantDrawer
        planId="plan-1"
        participant={{ ...PARTICIPANT, reviewedDone: true }}
        allParticipants={[PARTICIPANT]}
        onClose={() => {}}
      />,
    );

    const user = userEvent.setup();
    await user.click(await screen.findByLabelText(sv.participants.drawer.waitlistedLabel));
    await user.click(screen.getByRole("button", { name: sv.participants.drawer.unmarkDoneButton }));

    // Two SEPARATE PATCHes, in order: the dirty structured-field diff first, then the reviewedDone
    // flip as its own trailing call - never folded together (see handleSave's doc comment).
    await waitFor(() => expect(patchBodies).toEqual([{ waitlisted: true }, { reviewedDone: false }]));
  });
});

/** Review fix MAJOR 5: applying a "Tolkningsförslag" must never silently discard an unrelated
 *  unsaved edit sitting in the drawer's own draft state - the field-values PUT the apply triggers
 *  invalidates the SAME query the drawer's resync effect listens to. */
describe("ParticipantDrawer comment-suggestion apply (MAJOR 5)", () => {
  const PARTICIPANT_WITH_COMMENT: ParticipantRow = {
    ...PARTICIPANT,
    importedComment: "Vill gärna spela med Target Person.",
  };

  function mockSuggestionEndpoints() {
    server.use(
      http.get("/api/plans/plan-1/participants/participant-1/field-values", () =>
        HttpResponse.json([
          { fieldDefinitionId: "fd-play", key: "playWith", label: "Vill spela med", fieldType: "personRelation", value: [] },
          { fieldDefinitionId: "fd-note", key: "noteField", label: "Anteckning", fieldType: "text", value: "original" },
        ]),
      ),
      http.get("/api/plans/plan-1/participants/participant-1/comment-suggestions", () =>
        HttpResponse.json({
          participantId: "participant-1",
          suggestions: [
            {
              fingerprint: "fp-1",
              kind: "PLAY_WITH",
              matchedText: "spela med Target Person",
              fieldKey: "playWith",
              targets: [{ id: "target-1", displayName: "Target Person", score: 1.0, applied: false }],
              timeSlotIds: [],
              confidence: "HIGH",
              alreadyApplied: false,
            },
          ],
        }),
      ),
    );
  }

  it("a dirty unsaved field edit survives applying a suggestion, and the applied id lands in the merged field", async () => {
    mockDrawerEndpoints();
    mockSuggestionEndpoints();
    let putBody: unknown;
    server.use(
      http.put("/api/plans/plan-1/participants/participant-1/field-values", async ({ request }) => {
        putBody = await request.json();
        return HttpResponse.json([]);
      }),
    );

    renderWithProviders(
      <ParticipantDrawer
        planId="plan-1"
        participant={PARTICIPANT_WITH_COMMENT}
        allParticipants={[PARTICIPANT_WITH_COMMENT]}
        onClose={() => {}}
      />,
    );

    const user = userEvent.setup();
    const noteInput = await screen.findByLabelText("Anteckning");
    await user.type(noteInput, " extra");
    await waitFor(() => expect(noteInput).toHaveValue("original extra"));

    const applyButton = await screen.findByRole("button", { name: sv.participants.suggestions.applyButton });
    await user.click(applyButton);

    await waitFor(() => expect(putBody).toEqual({ playWith: ["target-1"] }));

    // The unrelated dirty edit must still be showing - never reverted by the post-apply resync.
    expect(screen.getByLabelText("Anteckning")).toHaveValue("original extra");
  });
});
