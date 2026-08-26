/**
 * v0.6.0 F4 (M-S4): the coach-hiding regression net. Table-driven-in-spirit (one describe block per
 * coach-touching surface, each with a SIMPLE test asserting absence and, where the same component
 * actually renders in both modes, an ADVANCED test asserting presence): render a component with
 * fixture data that CONTAINS coach info, and assert the coach-identifying string is ABSENT in SIMPLE
 * / PRESENT in ADVANCED.
 *
 * Covered here: ParticipantDrawer's coach wish MultiSelect (via CustomFieldEditor's coachRelation
 * case, shared by both ParticipantDrawer and CoachDrawer), CustomFieldEditor's coachRelation case
 * directly, CommentSuggestionList's COACH_ suggestion kinds, MappingStep's coach target row,
 * ReviewStep's coach target row (FIX 6), OptimizeRoute's "Optimera endast" Tränare checkbox (present
 * only via the ADVANCED branch, OptimizePanel), SimpleCapacitySummary (never surfaces coach-shortage
 * risk, by construction), and ResourcesPanel (never had coach content to begin with - a light sanity
 * check over a POPULATED fixture, not an empty one - see FIX 9).
 *
 * v0.6.0 F4 review fix (FIX 9): also the general (non-coach) SIMPLE/ADVANCED sweep for four gates
 * this file previously left untested in either direction - LiveSolveView's score line (AdvancedOnly),
 * ReviewStep's "Justera" button (AdvancedOnly), ResourcesPanel's per-court Switch chips vs its
 * read-only SIMPLE summary text, and ParticipantsPanel's SIMPLE-only summary strip.
 *
 * v0.6.0 F5 (M-S5): extended with the Resultat-screen coach surfaces this file's own F4 TODO above
 * promised - GroupCard's coach chip/rows (`showCoachSection` prop), ScheduleView's coach names in
 * schedule cells (`coachNameByGroupId` prop), ImprovementSuggestions' COACH_TIME/COACH_MAX rows (a
 * light sanity check here - the thorough coverage of `filterSuggestionsForUiMode` itself lives in
 * ImprovementSuggestions.test.tsx). ResultsSummary's coach-coverage badge was ALREADY covered by
 * ResultsSummary.test.tsx's own `coachCoverage={null}` cases (the prop, not a uiMode gate - SIMPLE
 * gating happens one level up, in ResultsPanel.tsx, by always passing `null`) - what F5 actually
 * ADDS to ResultsSummary is the raw soft-score line going ADVANCED-only, covered below.
 */
import type { ReactElement } from "react";
import { describe, expect, it } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "../../test/server";
import { renderWithProviders } from "../../test/renderWithProviders";
import { setUiModeForTests } from "../../lib/uiMode/uiModeStore";
import type { UiMode } from "../../lib/uiMode/uiMode";
import { sv } from "../../i18n/sv";
import type {
  ActivityPlan,
  CoachProfile,
  CommentSuggestion,
  FieldValueView,
  ParticipantCommentSuggestions,
  Person,
  PersonExplanationResponse,
  PlanExplanationResponse,
  RunResultSummary,
  SuggestDurationResponse,
  TargetCandidate,
  TrainingGroup,
} from "../../api/types";
import type { ImportAnalysis, ImportColumns } from "../../api/import";
import type { LiveSnapshot, SlotBlocksView } from "../../api/types";
import { CustomFieldEditor } from "./participants/CustomFieldEditor";
import { ParticipantDrawer } from "./participants/ParticipantDrawer";
import { CommentSuggestionList } from "./participants/CommentSuggestionList";
import type { ParticipantRow } from "./participants/participantRow";
import { MappingStep } from "../import/steps/MappingStep";
import { ReviewStep } from "../import/steps/ReviewStep";
import { OptimizeRoute } from "./optimize/OptimizeRoute";
import { LiveSolveView } from "./optimize/LiveSolveView";
import { SimpleCapacitySummary } from "./resources/SimpleCapacitySummary";
import { ResourcesPanel } from "./resources/ResourcesPanel";
import { ParticipantsPanel } from "./participants/ParticipantsPanel";
import { ExplainDrawer } from "./results/explain/ExplainDrawer";
import { GroupCard } from "./results/GroupCard";
import { ImprovementSuggestions } from "./results/ImprovementSuggestions";
import { ResultsSummary } from "./results/ResultsSummary";
import { ScheduleView } from "./results/ScheduleView";

/** OptimizeRoute/ResourcesPanel read `planId` via `useParams` (not a prop, unlike every other
 *  component covered in this file) - renderWithProviders' own MemoryRouter has no route table, so
 *  `useParams` would resolve to `{}` there. Mirrors OptimizePanel.test.tsx/ResourcesPanel.test.tsx's
 *  own render helper, plus an explicit `setUiModeForTests` (renderWithProviders' `uiMode` option
 *  isn't available on this lower-level render). */
function renderAtRoute(path: string, initialEntry: string, element: ReactElement, uiMode: UiMode) {
  setUiModeForTests(uiMode);
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[initialEntry]}>
          <Routes>
            <Route path={path} element={element} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

const COACH_NAME = "Anna Tränare";

describe("CustomFieldEditor coachRelation", () => {
  function renderIt() {
    return renderWithProviders(
      <CustomFieldEditor
        fieldValue={{ fieldDefinitionId: "fd-coach", key: "coachWish", label: "Önskad tränare", fieldType: "coachRelation" }}
        definition={undefined}
        value={["coach-1"]}
        onChange={() => {}}
        participants={[]}
        coaches={[{ id: "coach-1", name: COACH_NAME }]}
        timeSlots={[]}
        selfId="participant-1"
      />,
      { uiMode: "ADVANCED" },
    );
  }

  it("shows the coach's name in ADVANCED", () => {
    renderIt();
    // Mantine's Combobox keeps its (closed) dropdown option list mounted alongside the selected
    // Pill (see MappingStep.test.tsx's own doc comment on this) - both match the same label text.
    expect(screen.getAllByText(COACH_NAME).length).toBeGreaterThan(0);
  });

  it("hides the coach and shows the advanced-only note in SIMPLE", () => {
    renderWithProviders(
      <CustomFieldEditor
        fieldValue={{ fieldDefinitionId: "fd-coach", key: "coachWish", label: "Önskad tränare", fieldType: "coachRelation" }}
        definition={undefined}
        value={["coach-1"]}
        onChange={() => {}}
        participants={[]}
        coaches={[{ id: "coach-1", name: COACH_NAME }]}
        timeSlots={[]}
        selfId="participant-1"
      />,
      { uiMode: "SIMPLE" },
    );
    expect(screen.queryByText(COACH_NAME)).not.toBeInTheDocument();
    expect(screen.getByText(sv.uiMode.handledInAdvanced)).toBeInTheDocument();
  });
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

const COACH: CoachProfile = {
  id: "coach-1",
  personId: "person-2",
  activityPlanId: "plan-1",
  canAlsoTrainAsParticipant: false,
  reviewedDone: false,
};

const COACH_PERSON: Person = {
  id: "person-2",
  firstName: "Anna",
  lastName: "Tränare",
  displayName: COACH_NAME,
  canBeParticipant: false,
  canBeCoach: true,
};

const COACH_WISH_FIELD_VALUE: FieldValueView = {
  fieldDefinitionId: "fd-coach",
  key: "coachWish",
  label: "Önskad tränare",
  fieldType: "coachRelation",
  value: ["coach-1"],
};

function mockDrawerEndpoints() {
  server.use(
    http.get("/api/plans/plan-1/field-definitions", () => HttpResponse.json([])),
    http.get("/api/plans/plan-1/participants/participant-1/field-values", () => HttpResponse.json([COACH_WISH_FIELD_VALUE])),
    http.get("/api/plans/plan-1/coaches", () => HttpResponse.json([COACH])),
    http.get("/api/persons", () => HttpResponse.json([COACH_PERSON])),
    http.get("/api/plans/plan-1/time-slots", () => HttpResponse.json([])),
  );
}

describe("ParticipantDrawer coach wish MultiSelect", () => {
  it("shows the coach's name in ADVANCED", async () => {
    mockDrawerEndpoints();
    renderWithProviders(
      <ParticipantDrawer planId="plan-1" participant={PARTICIPANT} allParticipants={[PARTICIPANT]} onClose={() => {}} />,
      { uiMode: "ADVANCED" },
    );
    // Mantine's Combobox keeps its (closed) dropdown option list mounted alongside the selected
    // Pill (see MappingStep.test.tsx's own doc comment on this) - both match the same label text,
    // hence findAllByText rather than findByText.
    const matches = await screen.findAllByText(COACH_NAME);
    expect(matches.length).toBeGreaterThan(0);
  });

  it("hides the coach and shows the advanced-only note in SIMPLE", async () => {
    mockDrawerEndpoints();
    renderWithProviders(
      <ParticipantDrawer planId="plan-1" participant={PARTICIPANT} allParticipants={[PARTICIPANT]} onClose={() => {}} />,
      { uiMode: "SIMPLE" },
    );
    await screen.findByText(sv.uiMode.handledInAdvanced);
    expect(screen.queryByText(COACH_NAME)).not.toBeInTheDocument();
  });
});

function target(overrides: Partial<TargetCandidate> = {}): TargetCandidate {
  return { id: "coach-1", displayName: COACH_NAME, score: 1.0, applied: false, ...overrides };
}

function coachWishSuggestion(): CommentSuggestion {
  return {
    fingerprint: "fp-coach",
    kind: "COACH_WISH",
    matchedText: `vill ha ${COACH_NAME} som tränare`,
    fieldKey: "coachWish",
    targets: [target()],
    timeSlotIds: [],
    confidence: "HIGH",
    alreadyApplied: false,
  };
}

function suggestionsResponse(): ParticipantCommentSuggestions {
  return { participantId: "participant-1", suggestions: [coachWishSuggestion()] };
}

describe("CommentSuggestionList COACH_ kinds", () => {
  const SUGGESTIONS_URL = "/api/plans/plan-1/participants/participant-1/comment-suggestions";

  it("shows the COACH_WISH suggestion (with the coach's name) in ADVANCED", async () => {
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(suggestionsResponse())));
    renderWithProviders(
      <CommentSuggestionList
        planId="plan-1"
        participantId="participant-1"
        fieldValues={[]}
        fieldValuesFetching={false}
        onApplied={() => {}}
      />,
      { uiMode: "ADVANCED" },
    );
    expect(await screen.findByText(sv.participants.suggestions.templates.COACH_WISH(COACH_NAME))).toBeInTheDocument();
  });

  it("drops the COACH_WISH suggestion entirely in SIMPLE", async () => {
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(suggestionsResponse())));
    renderWithProviders(
      <CommentSuggestionList
        planId="plan-1"
        participantId="participant-1"
        fieldValues={[]}
        fieldValuesFetching={false}
        onApplied={() => {}}
      />,
      { uiMode: "SIMPLE" },
    );
    // No suggestions left to show at all (this participant has only the one COACH_WISH candidate) -
    // the whole list (heading included) renders nothing, same as the "no suggestions" case.
    await waitFor(() => expect(screen.queryByText(sv.participants.suggestions.heading)).not.toBeInTheDocument());
    expect(screen.queryByText(COACH_NAME)).not.toBeInTheDocument();
  });
});

const PLAN_ID = "plan-1";
const SESSION_ID = "session-1";

const COLUMNS: ImportColumns = {
  sheet: "Blad1",
  headerRowIndex: 0,
  columns: [
    { columnIndex: 0, headerText: "Förnamn", sampleValues: ["Anna"], suggestedTarget: "firstName" },
    { columnIndex: 1, headerText: "Önskad tränare", sampleValues: [COACH_NAME], suggestedTarget: "coachName" },
  ],
};

function mockMappingEndpoints() {
  server.use(
    http.get(`/api/plans/${PLAN_ID}/import/sessions/${SESSION_ID}/columns`, () => HttpResponse.json(COLUMNS)),
    http.get(`/api/plans/${PLAN_ID}/field-definitions`, () => HttpResponse.json([])),
  );
}

describe("MappingStep coach targets", () => {
  it("keeps the auto-suggested coachName row editable in ADVANCED", async () => {
    mockMappingEndpoints();
    renderWithProviders(
      <MappingStep planId={PLAN_ID} sessionId={SESSION_ID} onNext={() => {}} onExpired={() => {}} />,
      { uiMode: "ADVANCED" },
    );
    const select = await screen.findByRole("textbox", { name: "Mappning för kolumn Önskad tränare" });
    expect(select).toHaveValue(sv.importWizard.mapping.targets.coachName);
    expect(select).toBeEnabled();
    expect(screen.queryByText(sv.uiMode.handledInAdvanced)).not.toBeInTheDocument();
  });

  it("shows only the advanced-only note for the auto-suggested coachName row in SIMPLE - no select, no target label, no sample/coach name", async () => {
    mockMappingEndpoints();
    renderWithProviders(
      <MappingStep planId={PLAN_ID} sessionId={SESSION_ID} onNext={() => {}} onExpired={() => {}} />,
      { uiMode: "SIMPLE" },
    );
    // v0.6.0 F4 review fix (FIX 6, MAJOR): the row used to keep a DISABLED select that still leaked
    // the target label ("Önskad tränare (fritext)") and the sample column still showed the coach's
    // actual name - both were a coach-identifying leak on the confident import path. Now: no select
    // at all for this row, no target label, no sample text.
    await screen.findByTestId("mapping-coach-row-note");
    expect(screen.queryByRole("textbox", { name: "Mappning för kolumn Önskad tränare" })).not.toBeInTheDocument();
    expect(screen.queryByText(sv.importWizard.mapping.targets.coachName)).not.toBeInTheDocument();
    expect(screen.queryByText(COACH_NAME)).not.toBeInTheDocument();
    expect(screen.getByTestId("mapping-coach-row-note")).toHaveTextContent(sv.uiMode.handledInAdvanced);
  });

  it("still submits the coachName mapping on Nästa in SIMPLE - hidden from view, never dropped from the payload", async () => {
    mockMappingEndpoints();
    let submittedMappings: { columnIndex: number; target: string }[] | null = null;
    server.use(
      http.put(`/api/plans/${PLAN_ID}/import/sessions/${SESSION_ID}/mapping`, async ({ request }) => {
        const body = (await request.json()) as { sheet: string; mappings: { columnIndex: number; target: string }[] };
        submittedMappings = body.mappings;
        return HttpResponse.json(body);
      }),
    );
    renderWithProviders(
      <MappingStep planId={PLAN_ID} sessionId={SESSION_ID} onNext={() => {}} onExpired={() => {}} />,
      { uiMode: "SIMPLE" },
    );
    await screen.findByTestId("mapping-coach-row-note");
    await userEvent.setup().click(screen.getByRole("button", { name: sv.importWizard.mapping.nextButton }));
    await waitFor(() => expect(submittedMappings).not.toBeNull());
    expect(submittedMappings).toContainEqual({ columnIndex: 1, target: "coachName" });
  });
});

const PLAN: ActivityPlan = {
  id: "plan-1",
  seasonPlanId: "season-1",
  name: "Herr",
  status: "draft",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

const SUGGESTION: SuggestDurationResponse = {
  suggestedSeconds: 60,
  machineSpeedFactor: 1,
  benchmarkMs: 1000,
  problemSize: { participants: 0, groups: 0, activeBlocks: 0, coaches: 0, wishes: 0, customFieldConstraints: 0 },
  rationaleSv: "Baserat på planens storlek föreslås 60 sekunder.",
};

function mockOptimizeEndpoints() {
  server.use(
    http.get("/api/plans/plan-1", () => HttpResponse.json(PLAN)),
    http.get("/api/plans/plan-1/groups", () => HttpResponse.json([])),
    http.get("/api/plans/plan-1/groups/sync-status", () => HttpResponse.json({ stale: false, reasons: [] })),
    http.get("/api/plans/plan-1/constraint-weights", () => HttpResponse.json([])),
    http.get("/api/plans/plan-1/solve/status", () => HttpResponse.json({ status: "NOT_SOLVING" })),
    http.get("/api/plans/plan-1/runs", () => HttpResponse.json([])),
    http.post("/api/plans/plan-1/solve/suggest-duration", () => HttpResponse.json(SUGGESTION)),
    http.get("/api/plans/plan-1/participants", () => HttpResponse.json([])),
    http.get("/api/plans/plan-1/training-blocks", () => HttpResponse.json([])),
    http.get("/api/plans/plan-1/priority-order", () => HttpResponse.json({ error: "Not found" }, { status: 404 })),
  );
}

describe("OptimizeRoute Tränare checkbox", () => {
  it("shows the 'Optimera endast: Tränare' checkbox in ADVANCED (behind the Avancerat accordion)", async () => {
    mockOptimizeEndpoints();
    renderAtRoute("/plans/:planId/optimering", "/plans/plan-1/optimering", <OptimizeRoute />, "ADVANCED");
    expect(await screen.findByRole("checkbox", { name: sv.optimize.optimizeOnly.coaches })).toBeInTheDocument();
  });

  it("never renders a Tränare checkbox at all in SIMPLE", async () => {
    mockOptimizeEndpoints();
    renderAtRoute("/plans/:planId/optimering", "/plans/plan-1/optimering", <OptimizeRoute />, "SIMPLE");
    await screen.findByTestId("simple-optimize-button");
    expect(screen.queryByRole("checkbox", { name: sv.optimize.optimizeOnly.coaches })).not.toBeInTheDocument();
    expect(screen.queryByText(sv.optimize.optimizeOnly.coaches)).not.toBeInTheDocument();
  });
});

describe("SimpleCapacitySummary (no coach shortage)", () => {
  it("never renders coach-shortage/no-coaches copy, regardless of the plan's own coach data", async () => {
    server.use(
      http.get("/api/plans/plan-1/capacity", () =>
        HttpResponse.json({
          participantCount: 5,
          waitlistedCount: 0,
          activeTrainingBlockCount: 2,
          targetGroupSize: 8,
          maxGroupSize: 10,
          targetCapacity: 16,
          maxCapacity: 20,
          waitlistRisk: "NONE",
          waitlistMessage: "Kapacitet räcker till alla anmälda",
          coachCount: 0,
          groupsRequiringCoachEstimate: 2,
          coachShortageRisk: true,
          coachShortageMessage: "Risk för tränarbrist",
          noCoaches: true,
          perTimeSlot: [],
        }),
      ),
    );
    renderWithProviders(<SimpleCapacitySummary planId="plan-1" />, { uiMode: "SIMPLE" });

    await screen.findByTestId("simple-capacity-summary");
    expect(screen.queryByText(sv.capacity.coachShortage.risk)).not.toBeInTheDocument();
    expect(screen.queryByText(sv.capacity.coachShortage.ok)).not.toBeInTheDocument();
    expect(screen.queryByText(sv.capacity.noCoaches.title)).not.toBeInTheDocument();
  });
});

const RESOURCES_ENTRY: SlotBlocksView = {
  timeSlot: {
    id: "ts-1",
    activityPlanId: "plan-1",
    dayOfWeek: "THURSDAY",
    startTime: "18:00",
    endTime: "19:30",
    durationMinutes: 90,
    label: "Torsdag 18.00–19.30",
  },
  blocks: [
    { id: "block-1", timeSlotId: "ts-1", courtId: "court-1", courtName: "Bana 1", activityPlanId: "plan-1", active: true, locked: false },
    { id: "block-2", timeSlotId: "ts-1", courtId: "court-2", courtName: "Bana 2", activityPlanId: "plan-1", active: true, locked: false },
  ],
};

describe("ResourcesPanel (no coach content)", () => {
  // v0.6.0 F4 review fix (FIX 9): the previous version of this test rendered ADVANCED with an EMPTY
  // blocks array - a vacuous check, since ResourcesPanel never had any coach content to begin with
  // regardless of mode or data, so nothing here could actually have failed. Rendering SIMPLE over a
  // POPULATED fixture instead at least exercises the panel's real per-slot summary rendering path
  // while asserting the same "never mentions tränare" invariant.
  it("never mentions tränare at all, even in SIMPLE over a populated fixture", async () => {
    server.use(http.get("/api/plans/plan-1/training-blocks", () => HttpResponse.json([RESOURCES_ENTRY])));
    renderAtRoute("/plans/:planId/resurser", "/plans/plan-1/resurser", <ResourcesPanel />, "SIMPLE");
    await screen.findByText(sv.simple.resources.heading);
    expect(screen.queryByText(/tränare/i)).not.toBeInTheDocument();
  });
});

// v0.6.0 F4 review fix (FIX 9): ResourcesPanel's per-court Switch chips (ADVANCED-only, interactive)
// vs the plain "N block" summary line (rendered unconditionally in BOTH modes) that's SIMPLE's only
// court-count presentation - the chips add manual-exception control ADVANCED gets and SIMPLE
// deliberately doesn't (see the minor "triple-stated court count" review fix: SIMPLE used to ALSO
// get its own redundant read-only text block here, removed since the summary line already covers it).
describe("ResourcesPanel court chips vs summary", () => {
  it("shows the per-court Switch chips in ADVANCED", async () => {
    server.use(http.get("/api/plans/plan-1/training-blocks", () => HttpResponse.json([RESOURCES_ENTRY])));
    renderAtRoute("/plans/:planId/resurser", "/plans/plan-1/resurser", <ResourcesPanel />, "ADVANCED");
    await screen.findAllByTestId("block-chip");
    expect(screen.getAllByTestId("block-chip")).toHaveLength(2);
  });

  it("hides the per-court Switch chips in SIMPLE, leaving only the plain block-count summary line", async () => {
    server.use(http.get("/api/plans/plan-1/training-blocks", () => HttpResponse.json([RESOURCES_ENTRY])));
    renderAtRoute("/plans/:planId/resurser", "/plans/plan-1/resurser", <ResourcesPanel />, "SIMPLE");
    const row = await screen.findByTestId("time-slot-row");
    expect(row).toHaveTextContent(sv.resources.blocksCount(2));
    expect(screen.queryByTestId("block-chip")).not.toBeInTheDocument();
  });
});

const REVIEW_PLAN_ID = "plan-1";
const REVIEW_SESSION_ID = "session-1";

function reviewAnalysis(): ImportAnalysis {
  return {
    readyToCommit: true,
    selectedSheet: "Blad1",
    headerRowIndex: 0,
    sheetReason: "Enda bladet i filen",
    sheetConfidence: 1,
    usedTemplate: false,
    templateId: null,
    templateName: null,
    columns: [
      { columnIndex: 0, headerText: "Förnamn", target: "firstName", reason: "Matchar kolumnnamnet", confidence: 1, synthetic: false },
      {
        columnIndex: 1,
        headerText: "Önskad tränare",
        target: "coachName",
        // The coach's own name embedded in the reason text too - proves the WHOLE row disappears in
        // SIMPLE, not just the target-label cell (FIX 6: "no samples, no target label, no coach names").
        reason: `Kolumnen innehåller värden som "${COACH_NAME}"`,
        confidence: 0.9,
        synthetic: false,
      },
    ],
    mappedCount: 2,
    ignoredCount: 0,
    playerRowCount: 3,
    warnRowCount: 0,
    skipRowCount: 0,
    warnings: [],
  };
}

function mockReviewEndpoints() {
  server.use(
    http.get(`/api/plans/${REVIEW_PLAN_ID}/import/sessions/${REVIEW_SESSION_ID}/analysis`, () =>
      HttpResponse.json(reviewAnalysis()),
    ),
  );
}

describe("ReviewStep coach target row", () => {
  it("shows the coachName column - header, target label, and the coach's name from the reason text - in ADVANCED", async () => {
    mockReviewEndpoints();
    renderWithProviders(
      <ReviewStep planId={REVIEW_PLAN_ID} sessionId={REVIEW_SESSION_ID} onAdjust={() => {}} onExpired={() => {}} />,
      { uiMode: "ADVANCED" },
    );
    await screen.findByText("Önskad tränare");
    expect(screen.getByText(sv.importWizard.mapping.targets.coachName)).toBeInTheDocument();
    expect(screen.getByText(`Kolumnen innehåller värden som "${COACH_NAME}"`)).toBeInTheDocument();
  });

  it("drops the whole coachName row - header, target label, and coach name - in SIMPLE", async () => {
    mockReviewEndpoints();
    renderWithProviders(
      <ReviewStep planId={REVIEW_PLAN_ID} sessionId={REVIEW_SESSION_ID} onAdjust={() => {}} onExpired={() => {}} />,
      { uiMode: "SIMPLE" },
    );
    // Waits on the firstName row's REASON text (unique - unlike "Förnamn", which also matches the
    // firstName row's own target-label cell, sv.importWizard.mapping.targets.firstName) for the
    // table to render before asserting the coachName row's absence.
    await screen.findByText("Matchar kolumnnamnet");
    expect(screen.queryByText("Önskad tränare")).not.toBeInTheDocument();
    expect(screen.queryByText(sv.importWizard.mapping.targets.coachName)).not.toBeInTheDocument();
    expect(screen.queryByText(COACH_NAME)).not.toBeInTheDocument();
  });
});

// v0.6.0 F4 review fix (FIX 9): ReviewStep's "Justera" escape hatch (AdvancedOnly) - never
// previously asserted absent in SIMPLE, only present in ADVANCED (ReviewStep.test.tsx renders the
// component's default ADVANCED-equivalent uiMode only).
describe("ReviewStep Justera button", () => {
  it("shows the Justera button in ADVANCED", async () => {
    mockReviewEndpoints();
    renderWithProviders(
      <ReviewStep planId={REVIEW_PLAN_ID} sessionId={REVIEW_SESSION_ID} onAdjust={() => {}} onExpired={() => {}} />,
      { uiMode: "ADVANCED" },
    );
    expect(await screen.findByRole("button", { name: sv.importWizard.review.adjustButton })).toBeInTheDocument();
  });

  it("hides the Justera button in SIMPLE, showing the dimmed adjust-in-advanced hint instead", async () => {
    mockReviewEndpoints();
    renderWithProviders(
      <ReviewStep planId={REVIEW_PLAN_ID} sessionId={REVIEW_SESSION_ID} onAdjust={() => {}} onExpired={() => {}} />,
      { uiMode: "SIMPLE" },
    );
    await screen.findByRole("button", { name: sv.importWizard.review.importButton });
    expect(screen.queryByRole("button", { name: sv.importWizard.review.adjustButton })).not.toBeInTheDocument();
    expect(screen.getByTestId("review-simple-adjust-hint")).toHaveTextContent(sv.importWizard.review.simpleAdjustHint);
  });
});

function liveSnapshot(): LiveSnapshot {
  return {
    runId: "run-1",
    sequence: 1,
    hard: 0,
    medium: -300,
    soft: -20,
    feasible: true,
    improvementCount: 1,
    capturedAtMillis: 1_000,
    groups: [{ groupId: "g1", name: "Grupp 1", players: [] }],
    waitlist: [],
  };
}

// v0.6.0 F4 review fix (FIX 9): LiveSolveView's raw hard/waitlist/soft score line (AdvancedOnly) -
// never previously asserted absent in SIMPLE, only present in ADVANCED (LiveSolveView.test.tsx's own
// specs all render the component's default ADVANCED-equivalent uiMode).
describe("LiveSolveView score line", () => {
  it("shows the raw score line in ADVANCED", () => {
    renderWithProviders(<LiveSolveView planId="plan-1" snapshot={liveSnapshot()} running />, { uiMode: "ADVANCED" });
    expect(screen.getByTestId("live-solve-score-line")).toBeInTheDocument();
  });

  it("hides the raw score line in SIMPLE", () => {
    renderWithProviders(<LiveSolveView planId="plan-1" snapshot={liveSnapshot()} running />, { uiMode: "SIMPLE" });
    expect(screen.queryByTestId("live-solve-score-line")).not.toBeInTheDocument();
  });
});

function mockParticipantsPanelEndpoints() {
  server.use(
    http.get("/api/plans/plan-1/participants", () =>
      HttpResponse.json([
        {
          id: "participant-1",
          personId: "person-1",
          activityPlanId: "plan-1",
          manualReviewFlag: false,
          waitlisted: false,
          reviewedDone: false,
          estimatedLevel: null,
          manualLevelScore: null,
        },
      ]),
    ),
    http.get("/api/persons", () =>
      HttpResponse.json([
        { id: "person-1", firstName: "Karin", lastName: "Lindqvist", displayName: "Karin Lindqvist", canBeParticipant: true, canBeCoach: false },
      ]),
    ),
    http.get("/api/plans/plan-1/comment-suggestions", () => HttpResponse.json([])),
  );
}

// v0.6.0 F4 review fix (FIX 9): ParticipantsPanel's SIMPLE-only summary strip ("N deltagare · N utan
// nivå") - never previously asserted absent in ADVANCED, only present in SIMPLE (there is no
// ParticipantsPanel.test.tsx at all yet).
describe("ParticipantsPanel summary strip", () => {
  it("shows the summary strip in SIMPLE", async () => {
    mockParticipantsPanelEndpoints();
    renderAtRoute("/plans/:planId/deltagare", "/plans/plan-1/deltagare", <ParticipantsPanel />, "SIMPLE");
    expect(await screen.findByTestId("simple-participants-summary")).toBeInTheDocument();
  });

  it("hides the summary strip in ADVANCED", async () => {
    mockParticipantsPanelEndpoints();
    renderAtRoute("/plans/:planId/deltagare", "/plans/plan-1/deltagare", <ParticipantsPanel />, "ADVANCED");
    await screen.findByText("Karin Lindqvist");
    expect(screen.queryByTestId("simple-participants-summary")).not.toBeInTheDocument();
  });
});

// --- v0.6.0 F5 (M-S5): Resultat-screen coach surfaces (this file's own F4 TODO, resolved above) ---

const RUN_SUMMARY: RunResultSummary = {
  hard: 0,
  medium: 0,
  soft: -300,
  feasible: true,
  unassignedCount: 0,
  note: null,
  unchangedFromPrevious: false,
};

const PLAN_EXPLANATION: PlanExplanationResponse = {
  runId: "run-1",
  basedOnRevision: 1,
  currentRevision: 1,
  stale: false,
  score: { hard: 0, medium: 0, soft: -300 },
  feasible: true,
  constraintSummaries: [],
  hardViolations: [],
  waitlist: [],
  problematicGroups: [],
  manualReview: [],
};

describe("ResultsSummary raw score line", () => {
  it("shows the soft-score line in ADVANCED", async () => {
    server.use(http.get("/api/plans/plan-1/runs/run-1/explanations/plan", () => HttpResponse.json(PLAN_EXPLANATION)));
    renderWithProviders(
      <ResultsSummary planId="plan-1" runId="run-1" runStartedAtLabel="10:00" runSummary={RUN_SUMMARY} coachCoverage={null} />,
      { uiMode: "ADVANCED" },
    );
    expect(await screen.findByText((text) => text.includes("mjukt"))).toBeInTheDocument();
  });

  it("hides the soft-score line in SIMPLE - a raw solver score is exactly the jargon SIMPLE hides", async () => {
    server.use(http.get("/api/plans/plan-1/runs/run-1/explanations/plan", () => HttpResponse.json(PLAN_EXPLANATION)));
    renderWithProviders(
      <ResultsSummary planId="plan-1" runId="run-1" runStartedAtLabel="10:00" runSummary={RUN_SUMMARY} coachCoverage={null} />,
      { uiMode: "SIMPLE" },
    );
    await screen.findByTestId("results-quality-summary");
    expect(screen.queryByText((text) => text.includes("mjukt"))).not.toBeInTheDocument();
  });
});

const RESULTS_GROUP: TrainingGroup = {
  id: "group-1",
  activityPlanId: "plan-1",
  name: "Grupp A",
  requiredCoachCount: 1,
  locked: false,
};

const GROUP_CARD_COACH = { coachProfileId: "coach-1", name: COACH_NAME, locked: false };

describe("GroupCard coach chip/rows (showCoachSection)", () => {
  it("shows the coach chip and coach row in ADVANCED (showCoachSection defaults to true)", () => {
    renderWithProviders(
      <GroupCard
        planId="plan-1"
        group={RESULTS_GROUP}
        timeBanaLabel={null}
        coaches={[GROUP_CARD_COACH]}
        members={[]}
        runId="run-1"
        onExplain={() => {}}
        onTestMove={() => {}}
        onExplainGroup={() => {}}
      />,
    );
    expect(screen.getByText(COACH_NAME)).toBeInTheDocument();
    expect(screen.getByText(sv.results.quality.chips.coachLabel(1, 1))).toBeInTheDocument();
  });

  it("hides the coach chip, coach row, and 'Ingen tränare' text when showCoachSection is false, coaches empty", () => {
    renderWithProviders(
      <GroupCard
        planId="plan-1"
        group={RESULTS_GROUP}
        timeBanaLabel={null}
        coaches={[]}
        showCoachSection={false}
        members={[]}
        runId="run-1"
        onExplain={() => {}}
        onTestMove={() => {}}
        onExplainGroup={() => {}}
      />,
    );
    expect(screen.queryByText(COACH_NAME)).not.toBeInTheDocument();
    expect(screen.queryByText(sv.results.groupCard.noCoach)).not.toBeInTheDocument();
    expect(screen.queryByText(sv.results.quality.chips.coachLabel(0, 1))).not.toBeInTheDocument();
    // Block-lock stays - locks are core, not coach-only.
    expect(screen.getByTestId(`block-lock-${RESULTS_GROUP.id}`)).toBeInTheDocument();
  });
});

const SCHEDULE_SLOT_BLOCKS: SlotBlocksView[] = [
  {
    timeSlot: {
      id: "ts-1",
      activityPlanId: "plan-1",
      dayOfWeek: "THURSDAY",
      startTime: "18:00",
      endTime: "19:30",
      durationMinutes: 90,
      label: "Torsdag 18.00–19.30",
    },
    blocks: [{ id: "block-1", timeSlotId: "ts-1", courtId: "court-1", courtName: "Bana 1", activityPlanId: "plan-1", active: true, locked: false }],
  },
];

const SCHEDULE_GROUPS: TrainingGroup[] = [{ ...RESULTS_GROUP, assignedTrainingBlockId: "block-1" }];

describe("ScheduleView coach names in cells", () => {
  it("shows the coach's name in a schedule cell in ADVANCED", async () => {
    server.use(http.get("/api/seasons/season-1/conflicts", () => HttpResponse.json([])));
    renderWithProviders(
      <ScheduleView
        planId="plan-1"
        seasonPlanId="season-1"
        slotBlocks={SCHEDULE_SLOT_BLOCKS}
        groups={SCHEDULE_GROUPS}
        coachNameByGroupId={{ "group-1": COACH_NAME }}
      />,
    );
    expect(await screen.findByText(`${RESULTS_GROUP.name} / ${COACH_NAME}`)).toBeInTheDocument();
  });

  it("omits the coach's name from schedule cells in SIMPLE (coachNameByGroupId passed as {})", async () => {
    server.use(http.get("/api/seasons/season-1/conflicts", () => HttpResponse.json([])));
    renderWithProviders(
      <ScheduleView planId="plan-1" seasonPlanId="season-1" slotBlocks={SCHEDULE_SLOT_BLOCKS} groups={SCHEDULE_GROUPS} coachNameByGroupId={{}} />,
    );
    expect(await screen.findByText(RESULTS_GROUP.name)).toBeInTheDocument();
    expect(screen.queryByText(`${RESULTS_GROUP.name} / ${COACH_NAME}`)).not.toBeInTheDocument();
    expect(screen.queryByText(COACH_NAME)).not.toBeInTheDocument();
  });
});

describe("ImprovementSuggestions COACH_TIME/COACH_MAX kinds", () => {
  const SUGGESTIONS_URL = "/api/plans/plan-1/runs/run-1/suggestions";
  const RESPONSE = {
    runId: "run-1",
    basedOnRevision: 1,
    currentRevision: 1,
    stale: false,
    omittedCount: 0,
    suggestions: [
      {
        kind: "COACH_TIME" as const,
        titleSv: `Om ${COACH_NAME} kunde ta Torsdag 18.00-19.30 skulle Grupp A få en tränare.`,
        detailSv: undefined,
        impactSv: "1 grupp utan tränare åtgärdas",
        groupId: "group-1",
        participantProfileId: undefined,
        coachProfileId: "coach-1",
        timeSlotId: "slot-1",
      },
    ],
  };

  it("shows the COACH_TIME suggestion (with the coach's name) in ADVANCED", async () => {
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(RESPONSE)));
    renderWithProviders(<ImprovementSuggestions planId="plan-1" runId="run-1" />, { uiMode: "ADVANCED" });
    expect(await screen.findByText(RESPONSE.suggestions[0].titleSv)).toBeInTheDocument();
  });

  it("drops the COACH_TIME suggestion entirely in SIMPLE", async () => {
    server.use(http.get(SUGGESTIONS_URL, () => HttpResponse.json(RESPONSE)));
    renderWithProviders(<ImprovementSuggestions planId="plan-1" runId="run-1" />, { uiMode: "SIMPLE" });
    const empty = await screen.findByTestId("improvement-suggestions-empty");
    expect(screen.queryByText(RESPONSE.suggestions[0].titleSv)).not.toBeInTheDocument();
    expect(screen.queryByText(COACH_NAME)).not.toBeInTheDocument();
    // v0.6.0 F6 review fix (FIX 1, BLOCKER): this fixture's only suggestion is COACH_TIME, so SIMPLE's
    // own coach filter is what emptied the list - the empty-state copy itself must not leak a
    // "tränar..." word either (the ADVANCED `sv.results.suggestions.empty` string names
    // "tränartäckningen"; SIMPLE must render `emptySimple` instead). Asserted against the whole
    // rendered node's text, not a specific string, so this would catch ANY coach wording, not just
    // the one variant this test happens to know about today.
    expect(empty.textContent ?? "").not.toMatch(/tränar/i);
  });
});

// v0.6.0 F5 review fix (FIX 1, BLOCKER): the person-level explain drawer's own coach-hiding row -
// a coach-wish positive factor AND a COACH: unmet wish must both survive verbatim in ADVANCED but
// disappear entirely in SIMPLE (SimpleExplainBody's own unit tests cover the filtering mechanics in
// detail; this is the coach-hiding sweep's cross-surface regression net).
const COACH_EXPLAIN_RESPONSE: PersonExplanationResponse = {
  runId: "run-1",
  basedOnRevision: 1,
  currentRevision: 1,
  stale: false,
  participantProfileId: "participant-1",
  name: "Karin Lindqvist",
  selectedGroup: { groupId: "group-1", name: "Grupp A", size: 1 },
  positiveFactors: [{ messageSv: `Karin Lindqvist fick önskad tränare ${COACH_NAME}` }],
  negativeFactors: [],
  brokenWishes: [],
  appliedWeights: [],
  alternatives: [],
  indirectFactors: [],
  placementSummarySv: "Karin placerades i Grupp A.",
  lockedNoticeSv: undefined,
  unmetWishes: [
    {
      wishId: "COACH:coach-1",
      key: "coachWishSoft",
      bucket: "SOFT",
      wishSv: `Karin Lindqvist vill helst ha tränare ${COACH_NAME}`,
      outcome: "TRADE_OFF",
      primaryReasonSv: `${COACH_NAME} coachar en annan grupp.`,
      hedgeSv: undefined,
      candidateGroupIds: [],
      bestCandidateGroupId: undefined,
      bestCandidateDelta: undefined,
      competingReasons: [],
      prioritySensitivity: undefined,
    },
  ],
};

function mockExplainEndpoint() {
  server.use(
    http.get("/api/plans/plan-1/runs/run-1/explanations/players/participant-1", () =>
      HttpResponse.json(COACH_EXPLAIN_RESPONSE),
    ),
  );
}

function renderExplainDrawer(uiMode: UiMode) {
  return renderWithProviders(
    <ExplainDrawer
      planId="plan-1"
      runId="run-1"
      participantProfileId="participant-1"
      participantName="Karin Lindqvist"
      allGroups={[{ id: "group-1", name: "Grupp A" }]}
      onClose={() => {}}
      onNavigateToParticipant={() => {}}
      onTestMove={() => {}}
    />,
    { uiMode },
  );
}

describe("ExplainDrawer coach factor + COACH unmet wish (Resultat person explain)", () => {
  it("shows the coach's name (positive factor) in ADVANCED", async () => {
    mockExplainEndpoint();
    renderExplainDrawer("ADVANCED");
    expect(await screen.findByText(COACH_NAME, { exact: false })).toBeInTheDocument();
  });

  it("hides the coach's name and drops the COACH unmet wish entirely in SIMPLE", async () => {
    mockExplainEndpoint();
    renderExplainDrawer("SIMPLE");
    await screen.findByTestId("explain-why-headline");
    expect(screen.queryByText(COACH_NAME, { exact: false })).not.toBeInTheDocument();
    expect(screen.queryByTestId("explain-unmet-wish")).not.toBeInTheDocument();
    expect(screen.getByText(sv.results.explain.simple.noUnmetWishes)).toBeInTheDocument();
  });
});
