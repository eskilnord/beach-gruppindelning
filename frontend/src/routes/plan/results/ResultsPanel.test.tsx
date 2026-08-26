import { MantineProvider } from "@mantine/core";
import { spotlight } from "@mantine/spotlight";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes, useNavigate } from "react-router-dom";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { server } from "../../../test/server";
import { setUiModeForTests } from "../../../lib/uiMode/uiModeStore";
import type {
  ActivityPlan,
  AssignmentsView,
  CoachProfile,
  OptimizationRun,
  ParticipantProfile,
  Person,
  PersonExplanationResponse,
  PlanExplanationResponse,
  TrainingGroup,
} from "../../../api/types";
import { sv } from "../../../i18n/sv";
import { ResultsPanel } from "./ResultsPanel";

const PLAN_ID = "plan-1";
const RUN_ID = "run-1";
const PARTICIPANT_ID = "participant-1";

const PLAN: ActivityPlan = {
  id: PLAN_ID,
  seasonPlanId: "season-1",
  name: "Herr",
  status: "draft",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

const GROUP: TrainingGroup = {
  id: "group-1",
  activityPlanId: PLAN_ID,
  name: "Grupp A",
  requiredCoachCount: 0,
  locked: false,
};

const PARTICIPANT: ParticipantProfile = {
  id: PARTICIPANT_ID,
  personId: "person-1",
  activityPlanId: PLAN_ID,
  manualReviewFlag: false,
  waitlisted: false,
  reviewedDone: false,
};

const PERSON: Person = {
  id: "person-1",
  firstName: "Karin",
  lastName: "Lindqvist",
  displayName: "Karin Lindqvist",
  canBeParticipant: true,
  canBeCoach: false,
};

const ASSIGNMENTS: AssignmentsView = {
  players: [{ id: "pa-1", participantProfileId: PARTICIPANT_ID, groupId: GROUP.id, locked: false, source: "solver" }],
  coaches: [],
};

const RUN: OptimizationRun = {
  id: RUN_ID,
  activityPlanId: PLAN_ID,
  status: "FINISHED",
  startedAt: "2026-01-01T10:00:00Z",
  resultSummaryJson: JSON.stringify({ hard: 0, medium: 0, soft: -10, feasible: true, unassignedCount: 0 }),
};

const PLAN_EXPLANATION: PlanExplanationResponse = {
  runId: RUN_ID,
  basedOnRevision: 1,
  currentRevision: 1,
  stale: false,
  score: { hard: 0, medium: 0, soft: -10 },
  feasible: true,
  constraintSummaries: [],
  hardViolations: [],
  waitlist: [],
  problematicGroups: [],
  manualReview: [],
};

const PERSON_EXPLANATION: PersonExplanationResponse = {
  runId: RUN_ID,
  basedOnRevision: 1,
  currentRevision: 1,
  stale: false,
  participantProfileId: PARTICIPANT_ID,
  name: "Karin Lindqvist",
  selectedGroup: { groupId: GROUP.id, name: GROUP.name, size: 1, timeLabelSv: undefined },
  positiveFactors: [],
  negativeFactors: [],
  brokenWishes: [],
  appliedWeights: [],
  alternatives: [],
  indirectFactors: [],
  placementSummarySv: "Karin placerades i Grupp A.",
  lockedNoticeSv: undefined,
  unmetWishes: [],
};

function mockEndpoints() {
  server.use(
    http.get(`/api/plans/${PLAN_ID}`, () => HttpResponse.json(PLAN)),
    http.get(`/api/plans/${PLAN_ID}/groups`, () => HttpResponse.json([GROUP])),
    http.get(`/api/plans/${PLAN_ID}/assignments`, () => HttpResponse.json(ASSIGNMENTS)),
    http.get(`/api/plans/${PLAN_ID}/participants`, () => HttpResponse.json([PARTICIPANT])),
    http.get("/api/persons", () => HttpResponse.json([PERSON])),
    http.get(`/api/plans/${PLAN_ID}/coaches`, () => HttpResponse.json<CoachProfile[]>([])),
    http.get(`/api/plans/${PLAN_ID}/training-blocks`, () => HttpResponse.json([])),
    http.get(`/api/plans/${PLAN_ID}/field-definitions`, () => HttpResponse.json([])),
    http.get(`/api/plans/${PLAN_ID}/runs`, () => HttpResponse.json([RUN])),
    http.get(`/api/plans/${PLAN_ID}/runs/${RUN_ID}/explanations/plan`, () => HttpResponse.json(PLAN_EXPLANATION)),
    http.get(`/api/plans/${PLAN_ID}/runs/${RUN_ID}/suggestions`, () =>
      HttpResponse.json({ runId: RUN_ID, basedOnRevision: 1, currentRevision: 1, stale: false, omittedCount: 0, suggestions: [] }),
    ),
    http.get(`/api/plans/${PLAN_ID}/runs/${RUN_ID}/explanations/players/${PARTICIPANT_ID}`, () =>
      HttpResponse.json(PERSON_EXPLANATION),
    ),
  );
}

/** ResultsPanel reads `planId` via `useParams` (not a prop) and `?forklara=`/`?highlight=` via
 *  `useSearchParams` - mirrors uiModeCoachHiding.test.tsx's own `renderAtRoute` helper (its own doc
 *  comment explains why renderWithProviders' route-table-less MemoryRouter isn't enough here). */
function renderAtRoute(initialEntry: string) {
  setUiModeForTests("SIMPLE");
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[initialEntry]}>
          <Routes>
            <Route path="/plans/:planId/resultat" element={<ResultsPanel />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

/** v0.6.0 F5 review fix (FIX 4, MAJOR): needs to trigger a SECOND `?forklara=` navigation from
 *  inside the test (mirroring PlayerSearchSpotlight's own `navigate(...)` call) - a plain
 *  `renderAtRoute` re-render at a literal URL string can't do that on its own since MemoryRouter
 *  only takes its `initialEntries` once, so this mounts a sibling button wired to the SAME router's
 *  `useNavigate`. */
function renderAtRouteWithNav(initialEntry: string) {
  setUiModeForTests("SIMPLE");
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  function NavButton({ to }: { to: string }) {
    const navigate = useNavigate();
    return (
      <button type="button" onClick={() => navigate(to)}>
        navigate-again
      </button>
    );
  }
  return render(
    <MantineProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[initialEntry]}>
          <Routes>
            <Route
              path="/plans/:planId/resultat"
              element={
                <>
                  <NavButton to={`/plans/${PLAN_ID}/resultat?forklara=${PARTICIPANT_ID}`} />
                  <ResultsPanel />
                </>
              }
            />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe("ResultsPanel SIMPLE mode - ?forklara= opens the explain drawer and strips the param", () => {
  it("opens the drawer for the participant named in ?forklara= and removes it from the URL", async () => {
    mockEndpoints();
    renderAtRoute(`/plans/${PLAN_ID}/resultat?forklara=${PARTICIPANT_ID}`);

    const drawer = await screen.findByTestId("explain-drawer");
    await waitFor(() => expect(within(drawer).queryByTestId("explain-why-headline")).toBeInTheDocument());
    expect(within(drawer).getByTestId("explain-why-headline")).toHaveTextContent("Karin Lindqvist tränar i Grupp A");

    // The param is stripped (replace, not push) so a later back-navigation doesn't reopen it -
    // asserted indirectly via the drawer staying open (its state now lives in React, not the URL)
    // and not reopening from a fresh render at the same (now-stripped) URL.
    await waitFor(() => {
      const search = new URLSearchParams(window.location.search);
      expect(search.has("forklara")).toBe(false);
    });
  });

  it("does nothing when ?forklara= is absent - drawer stays closed", async () => {
    mockEndpoints();
    renderAtRoute(`/plans/${PLAN_ID}/resultat`);

    await screen.findByRole("heading", { name: sv.results.heading });
    // Mantine's Drawer root stays mounted (closed/transitioned-out) regardless - its BODY only
    // renders once `opened` is true (see ExplainDrawer.tsx's `{opened && (...)}` guard), so the
    // absence of that body content is what actually proves the drawer never opened.
    expect(screen.queryByTestId("explain-why-headline")).not.toBeInTheDocument();
  });

  // v0.6.0 F5 review fix (FIX 4, MAJOR): the "already handled this id" latch used to never reset,
  // so a REPEAT search for the SAME participant (PlayerSearchSpotlight navigates with `?forklara=`
  // again) silently did nothing - not even the second navigation's own `forklara` param got stripped.
  it("reopens the drawer for a repeat ?forklara= search of the SAME participant, after it was closed", async () => {
    mockEndpoints();
    renderAtRouteWithNav(`/plans/${PLAN_ID}/resultat?forklara=${PARTICIPANT_ID}`);

    const drawer = await screen.findByTestId("explain-drawer");
    await waitFor(() => expect(within(drawer).queryByTestId("explain-why-headline")).toBeInTheDocument());

    // Close the drawer (Mantine's Drawer/Modal closes on Escape by default) and confirm it's gone.
    // Mantine's own escape-key listener reads `event.target.getAttribute` (its own
    // use-modal.mjs), so the event needs a real Element target, not `document` itself.
    fireEvent.keyDown(document.body, { key: "Escape", code: "Escape" });
    await waitFor(() => expect(screen.queryByTestId("explain-why-headline")).not.toBeInTheDocument());

    // The param was already stripped from the first search - re-navigating to the exact same
    // `?forklara=<id>` URL simulates a second Ctrl/Cmd+F search hitting the same participant.
    await userEvent.setup().click(screen.getByRole("button", { name: "navigate-again" }));

    const reopenedDrawer = await screen.findByTestId("explain-drawer");
    await waitFor(() => expect(within(reopenedDrawer).queryByTestId("explain-why-headline")).toBeInTheDocument());
    expect(within(reopenedDrawer).getByTestId("explain-why-headline")).toHaveTextContent("Karin Lindqvist tränar i Grupp A");
  });

  // v0.6.0 F5 review fix (FIX 4, MAJOR): a `?forklara=` id that doesn't resolve to a real participant
  // (stale/copied link, or the participant was since removed) must never open a blank-titled drawer.
  it("strips ?forklara= but never opens the drawer when the id doesn't resolve to a real participant", async () => {
    mockEndpoints();
    renderAtRoute(`/plans/${PLAN_ID}/resultat?forklara=participant-does-not-exist`);

    await screen.findByRole("heading", { name: sv.results.heading });
    await waitFor(() => {
      const search = new URLSearchParams(window.location.search);
      expect(search.has("forklara")).toBe(false);
    });
    expect(screen.queryByTestId("explain-why-headline")).not.toBeInTheDocument();
  });
});

describe("ResultsPanel SIMPLE mode - misplaced-hint search button", () => {
  it("opens the player-search spotlight when clicked", async () => {
    mockEndpoints();
    const openSpy = vi.spyOn(spotlight, "open").mockImplementation(() => {});
    renderAtRoute(`/plans/${PLAN_ID}/resultat`);

    const hint = await screen.findByTestId("results-misplaced-hint");
    await userEvent.setup().click(within(hint).getByRole("button", { name: sv.results.misplacedHint.searchButton }));

    expect(openSpy).toHaveBeenCalledTimes(1);
    openSpy.mockRestore();
  });
});

// v0.6.0 F6 (M-S6) loose-ends fix, regression net: the v0.2.0 coach-less "note" (RunResultSummary
// .note, e.g. "Inga tränare registrerade — grupperna optimerades utan tränartilldelning") is a coach
// string - it must never render in SIMPLE mode (same rule as GroupCard's coach chip/rows,
// ImprovementSuggestions' COACH_ rows, ResultsSummary's coachCoverage - uiModeCoachHiding.test.tsx's
// own doc comment), but WAS unguarded here before this fix.
const NOTE_RUN: OptimizationRun = {
  ...RUN,
  resultSummaryJson: JSON.stringify({
    hard: 0,
    medium: 0,
    soft: -10,
    feasible: true,
    unassignedCount: 0,
    note: "Inga tränare registrerade — grupperna optimerades utan tränartilldelning",
  }),
};

function mockEndpointsWithNote() {
  mockEndpoints();
  server.use(http.get(`/api/plans/${PLAN_ID}/runs`, () => HttpResponse.json([NOTE_RUN])));
}

describe("ResultsPanel coach-less note (RunResultSummary.note) - SIMPLE/ADVANCED gating", () => {
  it("SIMPLE mode: never renders the coach-less note", async () => {
    mockEndpointsWithNote();
    renderAtRoute(`/plans/${PLAN_ID}/resultat`);

    await screen.findByRole("heading", { name: sv.results.heading });
    expect(screen.queryByTestId("results-note")).not.toBeInTheDocument();
    // v0.6.0 F6 review fix (FIX 2, MAJOR): widened from /tränare/i to /tränar/i - the reviewer
    // verified no non-coach /tränar/i string can render on this screen once FIX 1 lands ("{name}
    // tränar i" is spotlight-only; "Tränar själv" is the drawer), so the narrower /tränare/i regex
    // was leaving a gap (e.g. "tränar" without a trailing "e" would have slipped through unnoticed).
    expect(screen.queryByText(/tränar/i)).not.toBeInTheDocument();
  });

  it("ADVANCED mode: renders the coach-less note unchanged", async () => {
    mockEndpointsWithNote();
    setUiModeForTests("ADVANCED");
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    render(
      <MantineProvider>
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={[`/plans/${PLAN_ID}/resultat`]}>
            <Routes>
              <Route path="/plans/:planId/resultat" element={<ResultsPanel />} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>
      </MantineProvider>,
    );

    expect(await screen.findByTestId("results-note")).toHaveTextContent(
      "Inga tränare registrerade — grupperna optimerades utan tränartilldelning",
    );
  });
});

// v0.6.0 F6 review fix (FIX 4, MAJOR): the coach-less note's SIMPLE gate must only hide notes that
// actually mention a coach - a generic diagnostic note (e.g. the backend's "avbruten innan lösaren
// hann starta", written when a run is cancelled before the solver even starts) has nothing to do with
// coaches and must stay visible in SIMPLE too. See OptimizationRunService.java for both known
// RunResultSummary.note values this checkout can produce.
const GENERIC_NOTE_RUN: OptimizationRun = {
  ...RUN,
  resultSummaryJson: JSON.stringify({
    hard: 0,
    medium: 0,
    soft: -10,
    feasible: true,
    unassignedCount: 0,
    note: "avbruten innan lösaren hann starta",
  }),
};

describe("ResultsPanel generic (non-coach) note - stays visible in SIMPLE", () => {
  it("SIMPLE mode: still renders a non-coach note", async () => {
    mockEndpoints();
    server.use(http.get(`/api/plans/${PLAN_ID}/runs`, () => HttpResponse.json([GENERIC_NOTE_RUN])));
    renderAtRoute(`/plans/${PLAN_ID}/resultat`);

    await screen.findByRole("heading", { name: sv.results.heading });
    expect(await screen.findByTestId("results-note")).toHaveTextContent("avbruten innan lösaren hann starta");
  });
});
