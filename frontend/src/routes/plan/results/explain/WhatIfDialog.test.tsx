import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "../../../../test/server";
import { renderWithProviders } from "../../../../test/renderWithProviders";
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

function renderDialog(initialTargetGroupId: string | null | undefined) {
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
