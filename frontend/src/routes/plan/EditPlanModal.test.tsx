import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../../test/server";
import { renderWithProviders } from "../../test/renderWithProviders";
import { sv } from "../../i18n/sv";
import { EditPlanModal } from "./EditPlanModal";
import type { ActivityPlan } from "../../api/types";

const PLAN: ActivityPlan = {
  id: "plan-1",
  seasonPlanId: "season-1",
  name: "Herr",
  category: "beach",
  status: "draft",
  defaultGroupTargetSize: 10,
  defaultGroupMinSize: 8,
  defaultGroupMaxSize: 12,
  defaultLevelMin: 300,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

describe("EditPlanModal", () => {
  it("pre-fills the 'Standardvärden för grupper' inputs from the plan's current defaults", () => {
    renderWithProviders(<EditPlanModal opened plan={PLAN} onClose={() => {}} />);

    expect(screen.getByLabelText(sv.planDefaults.targetLabel)).toHaveValue("10");
    expect(screen.getByLabelText(sv.planDefaults.minLabel)).toHaveValue("8");
    expect(screen.getByLabelText(sv.planDefaults.maxLabel)).toHaveValue("12");
    expect(screen.getByLabelText(sv.planDefaults.levelMinLabel)).toHaveValue("300");
  });

  it("submits changed defaults on save", async () => {
    const user = userEvent.setup();
    let requestBody: unknown;
    server.use(
      http.patch(`/api/plans/${PLAN.id}`, async ({ request }) => {
        requestBody = await request.json();
        return HttpResponse.json({ ...PLAN, defaultLevelMin: 450 });
      }),
    );

    renderWithProviders(<EditPlanModal opened plan={PLAN} onClose={() => {}} />);

    const levelMinInput = screen.getByLabelText(sv.planDefaults.levelMinLabel);
    await user.clear(levelMinInput);
    await user.type(levelMinInput, "450");

    await user.click(screen.getByRole("button", { name: sv.editPlanModal.submit }));

    await waitFor(() => expect(requestBody).toMatchObject({ defaultLevelMin: 450 }));
    expect(requestBody).toMatchObject({
      defaultGroupTargetSize: 10,
      defaultGroupMinSize: 8,
      defaultGroupMaxSize: 12,
    });
  });

  it("rejects a target size set above the max size before submitting", async () => {
    const user = userEvent.setup();
    let requestReceived = false;
    server.use(
      http.patch(`/api/plans/${PLAN.id}`, () => {
        requestReceived = true;
        return HttpResponse.json(PLAN);
      }),
    );

    renderWithProviders(<EditPlanModal opened plan={PLAN} onClose={() => {}} />);

    const targetInput = screen.getByLabelText(sv.planDefaults.targetLabel);
    await user.clear(targetInput);
    await user.type(targetInput, "20"); // above the plan's existing max of 12

    await user.click(screen.getByRole("button", { name: sv.editPlanModal.submit }));

    // Effective triple: min 8, target 20, max 12 - the error renders under every explicitly-set
    // field on the losing side of a violated inequality (target and max here), hence findAllByText.
    expect((await screen.findAllByText(sv.planDefaults.effectiveSizeError(8, 20, 12))).length).toBeGreaterThan(0);
    expect(requestReceived).toBe(false);
  });

  it("clearing an input sends an explicit null so the saved default is cleared (three-state PATCH)", async () => {
    // v0.3.0 review fix (Finding 1): the PATCH body must contain a LITERAL null for the cleared
    // field - undefined would be dropped by JSON.stringify and the backend would keep the old
    // value forever (while the UI showed a success toast). The persisted round-trip (null in DB,
    // null back out on GET) is pinned backend-side by
    // ActivityPlanControllerTest#patchDistinguishesAbsentNullAndValueForDefaults.
    const user = userEvent.setup();
    let requestBody: unknown;
    server.use(
      http.patch(`/api/plans/${PLAN.id}`, async ({ request }) => {
        requestBody = await request.json();
        return HttpResponse.json({ ...PLAN, defaultLevelMin: undefined });
      }),
    );

    renderWithProviders(<EditPlanModal opened plan={PLAN} onClose={() => {}} />);

    await user.clear(screen.getByLabelText(sv.planDefaults.levelMinLabel));
    await user.click(screen.getByRole("button", { name: sv.editPlanModal.submit }));

    await waitFor(() => expect(requestBody).toBeDefined());
    const body = requestBody as Record<string, unknown>;
    expect("defaultLevelMin" in body).toBe(true); // present in the JSON, not dropped
    expect(body.defaultLevelMin).toBeNull(); // and explicitly null = clear
    // Untouched fields still carry their pre-filled values (set, not cleared).
    expect(body).toMatchObject({
      defaultGroupTargetSize: 10,
      defaultGroupMinSize: 8,
      defaultGroupMaxSize: 12,
    });
  });
});
