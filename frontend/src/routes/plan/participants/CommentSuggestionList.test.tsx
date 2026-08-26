import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { notifications } from "@mantine/notifications";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { sv } from "../../../i18n/sv";
import { CommentSuggestionList, visibleSuggestionKinds, type SuggestionApplied } from "./CommentSuggestionList";
import type { CommentSuggestion, FieldValueView, ParticipantCommentSuggestions, TargetCandidate } from "../../../api/types";

const SUGGESTIONS_URL = "/api/plans/plan-1/participants/participant-1/comment-suggestions";
const FIELD_VALUES_URL = "/api/plans/plan-1/participants/participant-1/field-values";
const PARTICIPANT_URL = "/api/participants/participant-1";

// B18.3 (v0.6.0 audit-fix batch B): dismissals now persist in localStorage (keyed per plan) - several
// tests below reuse the same planId/participantId/fingerprint combo, so a leftover dismissal from one
// test would silently make an UNRELATED later test's suggestion disappear too. Cleared before every
// test in this file, same fail-safe spirit as dismissedSuggestionsStorage.ts itself.
//
// notifications.clean(): Mantine's notifications queue is a module-level singleton, not scoped to
// the <Notifications/> instance renderWithProviders mounts per test - without this, a "Tillagd ✓"
// toast shown by one test (B18.2) is still in the store (and re-rendered) when a LATER test's
// <Notifications/> mounts, breaking that later test's own text queries with a duplicate match.
beforeEach(() => {
  try {
    window.localStorage.clear();
  } catch {
    // best-effort only
  }
  notifications.clean();
});

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

  // B18.2 (v0.6.0 audit-fix batch B): a successful apply now shows a confirmation toast.
  it("shows the 'Tillagd ✓' success toast after a successful apply", async () => {
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response([playWithSuggestion()]))));
    server.use(http.put(FIELD_VALUES_URL, () => HttpResponse.json([])));

    const user = userEvent.setup();
    renderList();

    await user.click(await screen.findByRole("button", { name: sv.participants.suggestions.applyButton }));

    expect(await screen.findByText(sv.participants.suggestions.applySuccess)).toBeInTheDocument();
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

  // B18.3 (v0.6.0 audit-fix batch B): a dismissal must survive the component remounting for the SAME
  // participant (e.g. reopening the drawer) - previously dismissed was session-local React state only
  // (`useState(new Set())`), which reset to empty on every remount.
  it("a dismissal persists across a remount (drawer reopened for the same participant)", async () => {
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response([playWithSuggestion()]))));

    const user = userEvent.setup();
    const { unmount } = renderList();

    await screen.findByText(sv.participants.suggestions.templates.PLAY_WITH("Anna Svensson"));
    await user.click(screen.getByRole("button", { name: sv.participants.suggestions.dismissButton }));
    await waitFor(() =>
      expect(screen.queryByText(sv.participants.suggestions.templates.PLAY_WITH("Anna Svensson"))).not.toBeInTheDocument(),
    );

    // Simulates the drawer closing and reopening for the same participant (ParticipantDrawer.tsx
    // remounts CommentSuggestionList per participant via `key={participant.id}`).
    unmount();
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response([playWithSuggestion()]))));
    renderList();

    // The whole list (including its own heading) stays gone - never resurrected.
    await waitFor(() => expect(screen.queryByText(sv.participants.suggestions.heading)).not.toBeInTheDocument());
    expect(screen.queryByText(sv.participants.suggestions.templates.PLAY_WITH("Anna Svensson"))).not.toBeInTheDocument();
  });

  // B18.3: a corrupt/unreadable localStorage value must never crash the component - fail-safe, same
  // as dismissedSuggestionsStorage.ts's own try/catch contract.
  it("tolerates corrupt localStorage for the dismissed-suggestions key without crashing", async () => {
    window.localStorage.setItem("gp.dismissedSuggestions.plan-1", "{not valid json");
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(response([playWithSuggestion()]))));

    renderList();

    expect(await screen.findByText(sv.participants.suggestions.templates.PLAY_WITH("Anna Svensson"))).toBeInTheDocument();
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

// v0.6.0 F4 (M-S4): pure filter unit tests for the coach-hiding gate - see the sibling
// uiModeCoachHiding.test.tsx sweep for the full component-level assertion.
describe("visibleSuggestionKinds", () => {
  const ALL_KINDS = [
    "PLAY_WITH",
    "MUST_PLAY_WITH",
    "AVOID_PLAY_WITH",
    "COACH_WISH",
    "COACH_AVOID",
    "TIME_CANNOT",
    "TIME_PREFER",
    "NEW_TO_CLUB",
    "LEVEL_CHANGE",
    "INJURY_NOTE",
  ];

  it("passes every kind through unchanged in ADVANCED mode", () => {
    expect(visibleSuggestionKinds(ALL_KINDS, false)).toEqual(ALL_KINDS);
  });

  it("drops every COACH_-prefixed kind in SIMPLE mode, keeping the rest in order", () => {
    expect(visibleSuggestionKinds(ALL_KINDS, true)).toEqual([
      "PLAY_WITH",
      "MUST_PLAY_WITH",
      "AVOID_PLAY_WITH",
      "TIME_CANNOT",
      "TIME_PREFER",
      "NEW_TO_CLUB",
      "LEVEL_CHANGE",
      "INJURY_NOTE",
    ]);
  });

  it("is a no-op on a list with no COACH_ kinds at all", () => {
    expect(visibleSuggestionKinds(["PLAY_WITH", "NEW_TO_CLUB"], true)).toEqual(["PLAY_WITH", "NEW_TO_CLUB"]);
  });

  it("returns an empty array unchanged", () => {
    expect(visibleSuggestionKinds([], true)).toEqual([]);
    expect(visibleSuggestionKinds([], false)).toEqual([]);
  });
});
