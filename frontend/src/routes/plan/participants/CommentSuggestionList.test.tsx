import { describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { sv } from "../../../i18n/sv";
import { CommentSuggestionList, type SuggestionApplied } from "./CommentSuggestionList";
import type { CommentSuggestion, FieldValueView, ParticipantCommentSuggestions, TargetCandidate } from "../../../api/types";

const SUGGESTIONS_URL = "/api/plans/plan-1/participants/participant-1/comment-suggestions";
const FIELD_VALUES_URL = "/api/plans/plan-1/participants/participant-1/field-values";
const PARTICIPANT_URL = "/api/participants/participant-1";

function target(overrides: Partial<TargetCandidate> = {}): TargetCandidate {
  return { id: "anna-1", displayName: "Anna Svensson", score: 1.0, applied: false, ...overrides };
}

function playWithSuggestion(overrides: Partial<CommentSuggestion> = {}): CommentSuggestion {
  return {
    fingerprint: "fp-1",
    kind: "PLAY_WITH",
    matchedText: "spela med Anna Svensson",
    fieldKey: "playWith",
    targets: [target()],
    timeSlotIds: [],
    confidence: "HIGH",
    alreadyApplied: false,
    ...overrides,
  };
}

function response(suggestions: CommentSuggestion[]): ParticipantCommentSuggestions {
  return { participantId: "participant-1", suggestions };
}

const EXISTING_FIELD_VALUES: FieldValueView[] = [
  { fieldDefinitionId: "fd-1", key: "playWith", label: "Vill spela med", fieldType: "personRelation", value: ["existing-1"] },
];

function renderList(overrides: { fieldValues?: FieldValueView[]; fieldValuesFetching?: boolean; onApplied?: (c: SuggestionApplied) => void } = {}) {
  return renderWithProviders(
    <CommentSuggestionList
      planId="plan-1"
      participantId="participant-1"
      fieldValues={overrides.fieldValues ?? EXISTING_FIELD_VALUES}
      fieldValuesFetching={overrides.fieldValuesFetching ?? false}
      onApplied={overrides.onApplied ?? (() => {})}
    />,
  );
}

describe("CommentSuggestionList", () => {
  it("renders a suggestion's kind description and matched text", async () => {
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response([playWithSuggestion()]))));

    renderList();

    expect(await screen.findByText(sv.participants.suggestions.templates.PLAY_WITH("Anna Svensson"))).toBeInTheDocument();
    expect(screen.getByText(/spela med Anna Svensson/)).toBeInTheDocument();
  });

  it("apply PUTs the MERGED array, preserving the existing id, and calls onApplied with the merged value", async () => {
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response([playWithSuggestion()]))));
    const putSpy = vi.fn();
    server.use(
      http.put(FIELD_VALUES_URL, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        putSpy(body);
        return HttpResponse.json([]);
      }),
    );
    const onApplied = vi.fn();

    const user = userEvent.setup();
    renderList({ onApplied });

    const applyButton = await screen.findByRole("button", { name: sv.participants.suggestions.applyButton });
    await user.click(applyButton);

    await waitFor(() => expect(putSpy).toHaveBeenCalled());
    const body = putSpy.mock.calls[0][0] as Record<string, string[]>;
    expect(body.playWith).toEqual(expect.arrayContaining(["existing-1", "anna-1"]));
    expect(body.playWith).toHaveLength(2);
    expect(onApplied).toHaveBeenCalledWith({ kind: "field", fieldKey: "playWith", value: body.playWith });
  });

  it("shows a candidate picker for an UNCERTAIN suggestion and disables apply until one is chosen", async () => {
    const uncertain = playWithSuggestion({
      confidence: "UNCERTAIN",
      targets: [target({ id: "anna-1", displayName: "Anna Andersson", score: 0.9 }), target({ id: "anna-2", displayName: "Anna Björk", score: 0.89 })],
    });
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response([uncertain]))));
    const putSpy = vi.fn();
    server.use(
      http.put(FIELD_VALUES_URL, async ({ request }) => {
        putSpy(await request.json());
        return HttpResponse.json([]);
      }),
    );

    const user = userEvent.setup();
    renderList();

    expect(await screen.findByText(sv.participants.suggestions.uncertainBadge)).toBeInTheDocument();
    const applyButton = screen.getByRole("button", { name: sv.participants.suggestions.applyButton });
    expect(applyButton).toBeDisabled();

    const select = screen.getByPlaceholderText(sv.participants.suggestions.pickCandidatePlaceholder);
    await user.click(select);
    await user.click(await screen.findByText("Anna Björk"));

    expect(applyButton).toBeEnabled();
    await user.click(applyButton);

    await waitFor(() => expect(putSpy).toHaveBeenCalled());
    const body = putSpy.mock.calls[0][0] as Record<string, string[]>;
    expect(body.playWith).toEqual(expect.arrayContaining(["existing-1", "anna-2"]));
  });

  it("review fix minor 1: an already-applied candidate is removed from the picker and never blocks applying a different one", async () => {
    // "existing-1" (already in the field's current array) is one of the two UNCERTAIN candidates -
    // it must be filtered out of the Select, and the suggestion itself must NOT be alreadyApplied
    // since the OTHER candidate is still un-applied.
    const uncertain = playWithSuggestion({
      confidence: "UNCERTAIN",
      alreadyApplied: false,
      targets: [
        target({ id: "existing-1", displayName: "Redan Tillagd", score: 0.9, applied: true }),
        target({ id: "anna-2", displayName: "Anna Björk", score: 0.89, applied: false }),
      ],
    });
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response([uncertain]))));

    renderList();

    await screen.findByText(sv.participants.suggestions.uncertainBadge);
    // Only one pickable candidate remains -> auto-chosen, no Select rendered, apply enabled.
    expect(screen.queryByPlaceholderText(sv.participants.suggestions.pickCandidatePlaceholder)).not.toBeInTheDocument();
    const applyButton = screen.getByRole("button", { name: sv.participants.suggestions.applyButton });
    expect(applyButton).toBeEnabled();
  });

  it("dismiss hides the suggestion without calling any write endpoint", async () => {
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response([playWithSuggestion()]))));
    const putSpy = vi.fn();
    server.use(http.put(FIELD_VALUES_URL, () => (putSpy(), HttpResponse.json([]))));

    const user = userEvent.setup();
    renderList();

    const title = await screen.findByText(sv.participants.suggestions.templates.PLAY_WITH("Anna Svensson"));
    expect(title).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: sv.participants.suggestions.dismissButton }));

    await waitFor(() => expect(screen.queryByText(sv.participants.suggestions.templates.PLAY_WITH("Anna Svensson"))).not.toBeInTheDocument());
    expect(putSpy).not.toHaveBeenCalled();
  });

  it("alreadyApplied disables the apply button and shows the alternate label", async () => {
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response([playWithSuggestion({ alreadyApplied: true, targets: [target({ applied: true })] })]))));

    renderList();

    const button = await screen.findByRole("button", { name: sv.participants.suggestions.alreadyAppliedButton });
    expect(button).toBeDisabled();
  });

  it("review fix minor 2: disables apply while the suggestions query is (re)fetching, and while field-values are refetching", async () => {
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response([playWithSuggestion()]))));

    renderList({ fieldValuesFetching: true });

    const button = await screen.findByRole("button", { name: sv.participants.suggestions.applyButton });
    expect(button).toBeDisabled();
  });

  it("a flag-kind suggestion PATCHes manualReviewFlag rather than any field value, and calls onApplied with kind: flag", async () => {
    const injuryNote: CommentSuggestion = {
      fingerprint: "fp-injury",
      kind: "INJURY_NOTE",
      matchedText: "ont i axeln",
      fieldKey: undefined as unknown as string,
      targets: [],
      timeSlotIds: [],
      confidence: "HIGH",
      alreadyApplied: false,
    };
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response([injuryNote]))));
    const patchSpy = vi.fn();
    server.use(
      http.patch(PARTICIPANT_URL, async ({ request }) => {
        patchSpy(await request.json());
        return HttpResponse.json({ id: "participant-1" });
      }),
    );
    const onApplied = vi.fn();

    const user = userEvent.setup();
    renderList({ onApplied });

    await user.click(await screen.findByRole("button", { name: sv.participants.suggestions.applyButton }));

    await waitFor(() => expect(patchSpy).toHaveBeenCalledWith({ manualReviewFlag: true }));
    expect(onApplied).toHaveBeenCalledWith({ kind: "flag" });
  });

  it("renders nothing when there are no suggestions", async () => {
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response([]))));

    renderList();

    await waitFor(() => expect(screen.queryByText(sv.participants.suggestions.heading)).not.toBeInTheDocument());
  });
});
