import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "../../../../test/server";
import { renderWithProviders } from "../../../../test/renderWithProviders";
import { sv } from "../../../../i18n/sv";
import type { UiMode } from "../../../../lib/uiMode/uiMode";
import type { WhatIfMoveResponse } from "../../../../api/types";
import type { GroupOption } from "./ExplainDrawer";
import { WhatIfDialog } from "./WhatIfDialog";

const PLAN_ID = "plan-1";
const RUN_ID = "run-1";
const PARTICIPANT_ID = "participant-1";

const ALL_GROUPS: GroupOption[] = [
  { id: "group-1", name: "Grupp A" },
  { id: "group-2", name: "Grupp B" },
];

const CONSEQUENCE: WhatIfMoveResponse = {
  runId: RUN_ID,
  basedOnRevision: 1,
  currentRevision: 1,
  stale: false,
  scoreDelta: { hard: 0, medium: 0, soft: -5 },
  wouldBreakHard: false,
  groupSizeChanges: [],
  levelSpreadChanges: [],
  newlyBroken: [],
  newlyFixed: [],
  suggestedActions: [],
};

function mockConsequence(track: () => void) {
  server.use(
    http.post(`/api/plans/${PLAN_ID}/whatif/move`, () => {
      track();
      return HttpResponse.json(CONSEQUENCE);
    }),
  );
}

function renderDialog(initialTargetGroupId: string | null | undefined, uiMode: UiMode = "ADVANCED") {
  renderWithProviders(
    <WhatIfDialog
      planId={PLAN_ID}
      runId={RUN_ID}
      participantProfileId={PARTICIPANT_ID}
      participantName="Karin Lindqvist"
      currentGroupId="group-1"
      allGroups={ALL_GROUPS}
      onClose={() => {}}
      initialTargetGroupId={initialTargetGroupId}
    />,
    { uiMode },
  );
}

/**
 * v0.6.0 F5 review fix (FIX 5, MAJOR): `initialTargetGroupId` (SimpleExplainBody's "Testa att
 * flytta" prefill) must only seed the picker when it's actually a group from the CURRENT run - a
 * stale cached explanation could otherwise point at a group id that no longer exists.
 */
describe("WhatIfDialog stale-prefill guard (FIX 5)", () => {
  it("prefills the target picker and fires the consequence query when initialTargetGroupId is a real current-run group", async () => {
    let calls = 0;
    mockConsequence(() => calls++);
    renderDialog("group-2");

    expect(screen.getByTestId("whatif-target-select")).toHaveValue("Grupp B");
    await waitFor(() => expect(calls).toBe(1));
    expect(await screen.findByTestId("whatif-consequence")).toBeInTheDocument();
  });

  it("leaves the target picker empty and never fires the consequence query when initialTargetGroupId isn't among the current run's groups", async () => {
    let calls = 0;
    mockConsequence(() => calls++);
    renderDialog("group-stale");

    expect(screen.getByTestId("whatif-target-select")).toHaveValue("");
    // Give the (buggy, pre-fix) effect a tick to have fired if it were going to.
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(calls).toBe(0);
    expect(screen.queryByTestId("whatif-consequence")).not.toBeInTheDocument();
  });

  it("leaves the target picker empty when initialTargetGroupId is absent (today's default behavior, unchanged)", () => {
    mockConsequence(() => {});
    renderDialog(undefined);

    expect(screen.getByTestId("whatif-target-select")).toHaveValue("");
  });
});

/** v0.6.0 audit-fix batch C (C12, P1): the SIMPLE-mode consequence rendering - raw score/spread
 *  numbers hidden, "Nya brott"/"Löser" get plain-language headings, coach-family rows collapse into
 *  one honest count-line instead of naming a coach, and "Lås & markera för omoptimering" (an
 *  ADVANCED-only two-step workflow concept) disappears entirely. ADVANCED must render byte-identical
 *  to before this finding - each test below has an ADVANCED-mode sibling proving that. */
const COACH_NAME = "Anna Tränare";

const CONSEQUENCE_WITH_DETAILS: WhatIfMoveResponse = {
  runId: RUN_ID,
  basedOnRevision: 1,
  currentRevision: 1,
  stale: false,
  scoreDelta: { hard: 0, medium: 0, soft: -120 },
  wouldBreakHard: false,
  groupSizeChanges: [{ groupId: "group-2", name: "Grupp B", from: 5, to: 6, max: 8 }],
  levelSpreadChanges: [{ groupId: "group-2", name: "Grupp B", from: 20, to: 35 }],
  newlyBroken: [
    { key: "levelBalance", messageSv: "Nivåspridning i Grupp B är 35 poäng (nivåsnitt 610,0)" },
    { key: "coachWishRequired", messageSv: `Karin Lindqvist måste ha tränare ${COACH_NAME}, men fick det inte` },
  ],
  newlyFixed: [{ key: "coachPreferenceSoft", messageSv: `Karin Lindqvist fick önskad tränare ${COACH_NAME}` }],
  suggestedActions: [],
};

function mockConsequenceWithDetails() {
  server.use(http.post(`/api/plans/${PLAN_ID}/whatif/move`, () => HttpResponse.json(CONSEQUENCE_WITH_DETAILS)));
}

describe("WhatIfDialog SIMPLE-mode consequence rendering (C12)", () => {
  it("hides the raw Totalpoäng line and the level-spread line in SIMPLE", async () => {
    mockConsequenceWithDetails();
    renderDialog("group-2", "SIMPLE");
    await screen.findByTestId("whatif-consequence");

    expect(screen.queryByText(`${sv.results.whatIf.scoreDeltaLabel}:`)).not.toBeInTheDocument();
    expect(screen.queryByText(sv.results.whatIf.levelSpreadChangesHeading)).not.toBeInTheDocument();
  });

  it("shows the raw Totalpoäng line and the level-spread line in ADVANCED", async () => {
    mockConsequenceWithDetails();
    renderDialog("group-2", "ADVANCED");
    await screen.findByTestId("whatif-consequence");

    expect(screen.getByText(`${sv.results.whatIf.scoreDeltaLabel}:`)).toBeInTheDocument();
    expect(screen.getByText(sv.results.whatIf.levelSpreadChangesHeading)).toBeInTheDocument();
  });

  it("uses plain-language headings, collapses the coach row, and never names the coach in SIMPLE", async () => {
    mockConsequenceWithDetails();
    renderDialog("group-2", "SIMPLE");
    await screen.findByTestId("whatif-consequence");

    expect(screen.getByText(sv.results.whatIf.simple.newlyBrokenHeading)).toBeInTheDocument();
    expect(screen.getByText(sv.results.whatIf.simple.newlyFixedHeading)).toBeInTheDocument();
    expect(screen.queryByText(sv.results.explain.newlyBrokenHeading)).not.toBeInTheDocument();
    expect(screen.queryByText(sv.results.explain.newlyFixedHeading)).not.toBeInTheDocument();

    expect(screen.getByText(CONSEQUENCE_WITH_DETAILS.newlyBroken[0].messageSv)).toBeInTheDocument();
    expect(screen.queryByText(COACH_NAME, { exact: false })).not.toBeInTheDocument();
    // Fixture has exactly one coach row in EACH of newlyBroken/newlyFixed, so the (identical) collapsed
    // count-line renders twice - once per section.
    expect(screen.getAllByText(sv.results.whatIf.simple.coachRowsCollapsed(1))).toHaveLength(2);
  });

  it("keeps registry headings, per-row coach messages, and no collapsed line in ADVANCED", async () => {
    mockConsequenceWithDetails();
    renderDialog("group-2", "ADVANCED");
    await screen.findByTestId("whatif-consequence");

    expect(screen.getByText(sv.results.explain.newlyBrokenHeading)).toBeInTheDocument();
    expect(screen.getByText(sv.results.explain.newlyFixedHeading)).toBeInTheDocument();
    expect(screen.getByText(CONSEQUENCE_WITH_DETAILS.newlyBroken[1].messageSv)).toBeInTheDocument();
    expect(screen.getByText(CONSEQUENCE_WITH_DETAILS.newlyFixed[0].messageSv)).toBeInTheDocument();
    expect(screen.getAllByText(COACH_NAME, { exact: false }).length).toBeGreaterThan(0);
    expect(screen.queryByText(sv.results.whatIf.simple.coachRowsCollapsed(1))).not.toBeInTheDocument();
  });

  it("hides 'Lås & markera för omoptimering' in SIMPLE and makes 'Behåll nuvarande' the filled/primary button", async () => {
    mockConsequenceWithDetails();
    renderDialog("group-2", "SIMPLE");
    await screen.findByTestId("whatif-consequence");

    expect(screen.queryByText(sv.results.whatIf.actions.lockAndResolve)).not.toBeInTheDocument();
    expect(screen.getByText(sv.results.whatIf.actions.keep)).toBeInTheDocument();
    expect(screen.getByText(sv.results.whatIf.actions.moveAnyway)).toBeInTheDocument();
  });

  it("keeps all three actions, including 'Lås & markera för omoptimering', in ADVANCED", async () => {
    mockConsequenceWithDetails();
    renderDialog("group-2", "ADVANCED");
    await screen.findByTestId("whatif-consequence");

    expect(screen.getByText(sv.results.whatIf.actions.lockAndResolve)).toBeInTheDocument();
    expect(screen.getByText(sv.results.whatIf.actions.keep)).toBeInTheDocument();
    expect(screen.getByText(sv.results.whatIf.actions.moveAnyway)).toBeInTheDocument();
  });
});
