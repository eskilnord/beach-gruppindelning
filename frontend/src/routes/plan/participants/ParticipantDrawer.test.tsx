import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { notifications } from "@mantine/notifications";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { sv } from "../../../i18n/sv";
import { ParticipantDrawer } from "./ParticipantDrawer";
import type { ParticipantRow } from "./participantRow";

// Mantine's notifications queue is a module-level singleton, not scoped to the <Notifications/>
// instance renderWithProviders mounts per test - several tests below show a "Sparat."/failure toast,
// and a leftover one from an earlier test can otherwise turn up as a stray extra match in a later
// test's own text queries (see CommentSuggestionList.test.tsx's own beforeEach for the same fix).
beforeEach(() => {
  notifications.clean();
});

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
    // B18.6 (v0.6.0 audit-fix batch B) added a description to this Switch - Mantine nests the
    // description INSIDE the same <label> as the "Kölista" text, so the label's full accessible text
    // is now "Kölista Spelaren placeras inte i någon grupp.", not just "Kölista" - exact: false makes
    // this a substring match again.
    await user.click(await screen.findByLabelText(sv.participants.drawer.waitlistedLabel, { exact: false }));
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

/**
 * B17 (v0.6.0 audit-fix batch B, P1): the drawer must never close outright while it has unsaved
 * field edits - "Stäng", an overlay click, and Escape all funnel through the same
 * confirm/save/discard guard (see ParticipantDrawer.tsx's attemptClose/requestCloseRef doc comments).
 * Covers all three confirmation outcomes explicitly, plus one interception check proving Mantine's
 * own built-in Escape-close is actually being intercepted, not bypassed.
 */
describe("ParticipantDrawer unsaved-changes close guard (B17)", () => {
  async function openAndMakeDirty() {
    mockDrawerEndpoints();
    const onClose = vi.fn();
    renderWithProviders(
      <ParticipantDrawer planId="plan-1" participant={PARTICIPANT} allParticipants={[PARTICIPANT]} onClose={onClose} />,
    );
    const user = userEvent.setup();
    // exact: false - B18.6 nests the Switch's description inside the same <label> as its text.
    const waitlistedSwitch = await screen.findByLabelText(sv.participants.drawer.waitlistedLabel, { exact: false });
    await user.click(waitlistedSwitch);
    expect(waitlistedSwitch).toBeChecked();
    return { user, onClose, waitlistedSwitch };
  }

  it("closing with no unsaved edits closes immediately - no confirmation shown", async () => {
    mockDrawerEndpoints();
    const onClose = vi.fn();
    renderWithProviders(
      <ParticipantDrawer planId="plan-1" participant={PARTICIPANT} allParticipants={[PARTICIPANT]} onClose={onClose} />,
    );
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: sv.participants.drawer.closeButton }));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(screen.queryByText(sv.participants.drawer.unsavedChangesModal.message)).not.toBeInTheDocument();
  });

  it("Stäng with unsaved edits shows the confirmation instead of closing", async () => {
    const { user, onClose } = await openAndMakeDirty();
    await user.click(screen.getByRole("button", { name: sv.participants.drawer.closeButton }));
    expect(await screen.findByText(sv.participants.drawer.unsavedChangesModal.message)).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });

  it("Escape with unsaved edits shows the confirmation too - proves Mantine's built-in Escape-close is intercepted", async () => {
    const { onClose } = await openAndMakeDirty();
    fireEvent.keyDown(document.body, { key: "Escape", code: "Escape" });
    expect(await screen.findByText(sv.participants.drawer.unsavedChangesModal.message)).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });

  it("[Fortsätt redigera] cancels the close - dialog closes, drawer stays open, edit is untouched", async () => {
    const { user, onClose, waitlistedSwitch } = await openAndMakeDirty();
    await user.click(screen.getByRole("button", { name: sv.participants.drawer.closeButton }));
    await screen.findByText(sv.participants.drawer.unsavedChangesModal.message);

    await user.click(screen.getByRole("button", { name: sv.participants.drawer.unsavedChangesModal.continueEditing }));

    await waitFor(() =>
      expect(screen.queryByText(sv.participants.drawer.unsavedChangesModal.message)).not.toBeInTheDocument(),
    );
    expect(onClose).not.toHaveBeenCalled();
    expect(waitlistedSwitch).toBeChecked();
  });

  it("[Släng ändringar] discards the edit and closes - no PATCH is ever sent", async () => {
    const patchSpy = vi.fn();
    server.use(
      http.patch("/api/participants/participant-1", async ({ request }) => {
        patchSpy(await request.json());
        return HttpResponse.json(PARTICIPANT);
      }),
    );
    const { user, onClose, waitlistedSwitch } = await openAndMakeDirty();
    await user.click(screen.getByRole("button", { name: sv.participants.drawer.closeButton }));
    await screen.findByText(sv.participants.drawer.unsavedChangesModal.message);

    await user.click(screen.getByRole("button", { name: sv.participants.drawer.unsavedChangesModal.discard }));

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    expect(patchSpy).not.toHaveBeenCalled();
    // The draft is reverted to the original (unchecked) value.
    expect(waitlistedSwitch).not.toBeChecked();
  });

  it("[Spara] saves then closes on success", async () => {
    let patchBody: unknown;
    server.use(
      http.patch("/api/participants/participant-1", async ({ request }) => {
        patchBody = await request.json();
        return HttpResponse.json({ ...PARTICIPANT, waitlisted: true });
      }),
    );
    const { user, onClose } = await openAndMakeDirty();
    await user.click(screen.getByRole("button", { name: sv.participants.drawer.closeButton }));
    await screen.findByText(sv.participants.drawer.unsavedChangesModal.message);

    // Scoped to the confirmation dialog - its "Spara" button shares its accessible name with the
    // drawer's OWN (still-mounted-behind-the-modal) main "Spara" button.
    const confirmDialog = screen.getByRole("dialog", { name: sv.participants.drawer.unsavedChangesModal.title });
    await user.click(within(confirmDialog).getByRole("button", { name: sv.participants.drawer.unsavedChangesModal.save }));

    await waitFor(() => expect(patchBody).toEqual({ waitlisted: true }));
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
  });

  it("[Spara] that FAILS leaves the drawer open (still dirty) rather than closing", async () => {
    // A network-level failure (not a non-2xx response) is what actually falls back to the generic
    // saveFailed text - a non-2xx response always produces an ApiError with SOME message (either the
    // backend's own {"error": "..."} string or client.ts's "Request failed with status N" fallback),
    // see api/client.ts's parseErrorMessage.
    server.use(http.patch("/api/participants/participant-1", () => HttpResponse.error()));
    const { user, onClose, waitlistedSwitch } = await openAndMakeDirty();
    await user.click(screen.getByRole("button", { name: sv.participants.drawer.closeButton }));
    await screen.findByText(sv.participants.drawer.unsavedChangesModal.message);

    const confirmDialog = screen.getByRole("dialog", { name: sv.participants.drawer.unsavedChangesModal.title });
    await user.click(within(confirmDialog).getByRole("button", { name: sv.participants.drawer.unsavedChangesModal.save }));

    await screen.findByText(sv.participants.drawer.saveFailed);
    expect(onClose).not.toHaveBeenCalled();
    // The unsaved edit is still there - nothing was silently discarded on a failed save.
    expect(waitlistedSwitch).toBeChecked();
  });
});

/** B18 sub-items (v0.6.0 audit-fix batch B, P2) that live in the structured-fields column. */
describe("ParticipantDrawer B18 copy/behavior fixes", () => {
  it("B18.4: renders the renamed section headings", async () => {
    mockDrawerEndpoints();
    renderWithProviders(
      <ParticipantDrawer planId="plan-1" participant={PARTICIPANT} allParticipants={[PARTICIPANT]} onClose={() => {}} />,
    );
    expect(await screen.findByText(sv.participants.drawer.structuredHeading)).toBeInTheDocument();
    expect(screen.getByText(sv.participants.drawer.customFieldsHeading)).toBeInTheDocument();
    expect(screen.queryByText("Strukturerade fält")).not.toBeInTheDocument();
    expect(screen.queryByText("Anpassade fält")).not.toBeInTheDocument();
  });

  it("B18.5/6/7: shows the manualLevelScore/waitlisted/manualReviewFlag descriptions", async () => {
    mockDrawerEndpoints();
    renderWithProviders(
      <ParticipantDrawer planId="plan-1" participant={PARTICIPANT} allParticipants={[PARTICIPANT]} onClose={() => {}} />,
    );
    await screen.findByLabelText(sv.participants.drawer.manualLevelScoreLabel);
    expect(screen.getByText(sv.participants.drawer.manualLevelScoreDescription)).toBeInTheDocument();
    expect(screen.getByText(sv.participants.drawer.waitlistedDescription)).toBeInTheDocument();
    expect(screen.getByText(sv.participants.drawer.manualReviewFlagDescription)).toBeInTheDocument();
  });

  it("B18.8: appends the saved-value note ONLY while previousGroupName is dirty", async () => {
    mockDrawerEndpoints();
    const withOrder: ParticipantRow = { ...PARTICIPANT, previousGroupName: "Herr 3", previousGroupOrder: 3 };
    renderWithProviders(
      <ParticipantDrawer planId="plan-1" participant={withOrder} allParticipants={[withOrder]} onClose={() => {}} />,
    );
    const hint = await screen.findByTestId("previous-group-order-hint");
    expect(hint).toHaveTextContent(sv.participants.drawer.previousGroupOrderParsed(3));
    expect(hint).not.toHaveTextContent(sv.participants.drawer.previousGroupSavedValueNote);

    fireEvent.change(screen.getByLabelText(sv.participants.drawer.previousGroupNameLabel), {
      target: { value: "Herr 3!" },
    });

    await waitFor(() => expect(hint).toHaveTextContent(sv.participants.drawer.previousGroupSavedValueNote));
  });

  it("B18.9: the 'Känslig' badge only renders when a comment actually exists", async () => {
    mockDrawerEndpoints();
    const { rerender } = renderWithProviders(
      <ParticipantDrawer planId="plan-1" participant={PARTICIPANT} allParticipants={[PARTICIPANT]} onClose={() => {}} />,
    );
    await screen.findByText(sv.participants.drawer.commentsHeading);
    expect(screen.queryByText(sv.participants.drawer.sensitiveBadge)).not.toBeInTheDocument();

    const withComment: ParticipantRow = { ...PARTICIPANT, importedComment: "Har lite kommentar." };
    rerender(
      <ParticipantDrawer planId="plan-1" participant={withComment} allParticipants={[withComment]} onClose={() => {}} />,
    );
    expect(await screen.findByText(sv.participants.drawer.sensitiveBadge)).toBeInTheDocument();
  });
});
