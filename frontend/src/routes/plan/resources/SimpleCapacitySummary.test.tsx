import { describe, expect, it } from "vitest";
import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { sv } from "../../../i18n/sv";
import { FALLBACK_GROUP_TARGET_SIZE } from "../../../lib/planDefaults";
import { SimpleCapacitySummary } from "./SimpleCapacitySummary";
import type { CapacityResponse } from "../../../api/types";

const PLAN_ID = "plan-1";

function capacityResponse(overrides: Partial<CapacityResponse>): CapacityResponse {
  return {
    participantCount: 0,
    waitlistedCount: 0,
    activeTrainingBlockCount: 2,
    targetGroupSize: undefined,
    maxGroupSize: undefined,
    targetCapacity: undefined,
    maxCapacity: undefined,
    waitlistRisk: "UNKNOWN",
    waitlistMessage: "",
    coachCount: 0,
    groupsRequiringCoachEstimate: 0,
    coachShortageRisk: false,
    coachShortageMessage: "",
    noCoaches: true,
    perTimeSlot: [],
    ...overrides,
  };
}

function mockCapacity(overrides: Partial<CapacityResponse>) {
  server.use(
    http.get(`/api/plans/${PLAN_ID}/capacity`, () => HttpResponse.json(capacityResponse(overrides))),
  );
}

/** v0.6.0 audit-fix B13 ("Gunilla" persona - "the capacity summary is broken whenever target/max
 *  aren't set"): the null-target fallback now computes REAL numbers using the SAME default
 *  (FALLBACK_GROUP_TARGET_SIZE, mirrored from the backend's GroupGenerator) group-generation itself
 *  would use, instead of a dead-end "kan inte beräknas" placeholder. */
describe("SimpleCapacitySummary B13: null-target fallback computes real numbers", () => {
  it("uses the FALLBACK_GROUP_TARGET_SIZE default and shows the 'Med standardgruppstorlek' sentence when target is unset", async () => {
    mockCapacity({ activeTrainingBlockCount: 2, targetGroupSize: undefined, participantCount: 5, waitlistedCount: 0 });
    renderWithProviders(<SimpleCapacitySummary planId={PLAN_ID} />, { uiMode: "SIMPLE" });

    const card = await screen.findByTestId("simple-capacity-summary");
    // 2 active blocks * fallback target 10 = 20 capacity, ceil(5/10)=1 group.
    expect(card).toHaveTextContent(
      sv.simple.capacity.summaryDefault(FALLBACK_GROUP_TARGET_SIZE, 2 * FALLBACK_GROUP_TARGET_SIZE, 1, 5, 0),
    );
  });

  it("uses the plan's real target (no fallback prefix) when it IS set", async () => {
    mockCapacity({ activeTrainingBlockCount: 4, targetGroupSize: 8, participantCount: 20, waitlistedCount: 0 });
    renderWithProviders(<SimpleCapacitySummary planId={PLAN_ID} />, { uiMode: "SIMPLE" });

    const card = await screen.findByTestId("simple-capacity-summary");
    // 4 * 8 = 32 capacity, ceil(20/8)=3 groups.
    expect(card).toHaveTextContent(sv.simple.capacity.summary(32, 3, 20, 0));
    expect(card).not.toHaveTextContent("standardgruppstorlek");
  });

  it('appends the waitlisted caption when waitlistedCount > 0, e.g. "(varav 3 på kölista)"', async () => {
    mockCapacity({ activeTrainingBlockCount: 4, targetGroupSize: 8, participantCount: 20, waitlistedCount: 3 });
    renderWithProviders(<SimpleCapacitySummary planId={PLAN_ID} />, { uiMode: "SIMPLE" });

    expect(await screen.findByText(/varav 3 på kölista/)).toBeInTheDocument();
  });

  it('reads "i upp till Y grupper" (a ceiling), never the old exact-sounding "i Y grupper"', async () => {
    mockCapacity({ activeTrainingBlockCount: 4, targetGroupSize: 8, participantCount: 20, waitlistedCount: 0 });
    renderWithProviders(<SimpleCapacitySummary planId={PLAN_ID} />, { uiMode: "SIMPLE" });

    expect(await screen.findByText(/i upp till 3 grupper/)).toBeInTheDocument();
  });
});

/** v0.6.0 audit-fix B13: a frontend-COMPUTED `registered > capacity` guard, independent of the
 *  backend's `waitlistRisk` enum. */
describe("SimpleCapacitySummary B13: over-capacity warning is frontend-computed", () => {
  it("shows the actionable warning when registered exceeds the computed capacity, even if waitlistRisk says NONE", async () => {
    // 2 active blocks * fallback target 10 = 20 capacity; 25 registered > 20.
    mockCapacity({ activeTrainingBlockCount: 2, targetGroupSize: undefined, participantCount: 25, waitlistRisk: "NONE" });
    renderWithProviders(<SimpleCapacitySummary planId={PLAN_ID} />, { uiMode: "SIMPLE" });

    expect(await screen.findByTestId("simple-capacity-over-capacity-warning")).toHaveTextContent(
      sv.simple.capacity.overCapacityWarning,
    );
  });

  it("does NOT show the warning when registered is within capacity", async () => {
    mockCapacity({ activeTrainingBlockCount: 4, targetGroupSize: 10, participantCount: 30, waitlistRisk: "NONE" });
    renderWithProviders(<SimpleCapacitySummary planId={PLAN_ID} />, { uiMode: "SIMPLE" });

    await screen.findByTestId("simple-capacity-summary");
    expect(screen.queryByTestId("simple-capacity-over-capacity-warning")).not.toBeInTheDocument();
  });
});

/** v0.6.0 audit-fix B13: short-circuits to the SAME zero-courts warning B9 renders per-row, instead
 *  of a nonsense "plats för ungefär 0 deltagare" sentence, when the whole plan has 0 active courts. */
describe("SimpleCapacitySummary B13: zero-courts short-circuit", () => {
  it("renders the shared zero-courts warning instead of a capacity sentence when activeTrainingBlockCount is 0", async () => {
    mockCapacity({ activeTrainingBlockCount: 0, participantCount: 10 });
    renderWithProviders(<SimpleCapacitySummary planId={PLAN_ID} />, { uiMode: "SIMPLE" });

    const card = await screen.findByTestId("simple-capacity-summary");
    expect(card).toHaveTextContent(sv.resources.zeroCourtsWarning);
    expect(card).not.toHaveTextContent("deltagare");
  });
});
