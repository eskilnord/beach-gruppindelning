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

  // v0.3.0 WI-3 smoke test: Kategori and Status each gained a HelpTip via their `description` slot
  // (their `label` props stay untouched).
  it("renders a HelpTip for the Kategori and Status fields (v0.3.0 WI-3)", () => {
    renderWithProviders(<EditPlanModal opened plan={PLAN} onClose={() => {}} />);

    expect(screen.getByRole("button", { name: sv.help.ariaLabel(sv.common.category) })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: sv.help.ariaLabel(sv.editPlanModal.statusLabel) })).toBeInTheDocument();
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

  // v0.6.0 F2 (M-S2): SIMPLE mode shows name+kategori+target(plainer "Standard gruppstorlek"
  // wording) only - min/max/level-min and the status free-text field are ADVANCED-only.
  it("SIMPLE mode shows only the target size (as 'Standard gruppstorlek'), hides status/min/max/level-min", () => {
    renderWithProviders(<EditPlanModal opened plan={PLAN} onClose={() => {}} />, { uiMode: "SIMPLE" });

    // exact: false on name only - withAsterisk appends a " *" to the accessible label text.
    expect(screen.getByLabelText(sv.common.name, { exact: false })).toBeInTheDocument();
    expect(screen.getByLabelText(sv.common.category)).toBeInTheDocument();
    expect(screen.getByLabelText(sv.editPlanModal.targetLabelSimple)).toHaveValue("10");

    expect(screen.queryByLabelText(sv.editPlanModal.statusLabel, { exact: false })).not.toBeInTheDocument();
    expect(screen.queryByText(sv.planDefaults.heading)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(sv.planDefaults.targetLabel)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(sv.planDefaults.minLabel)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(sv.planDefaults.maxLabel)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(sv.planDefaults.levelMinLabel)).not.toBeInTheDocument();
  });

  it("SIMPLE mode's target field submits to the same defaultGroupTargetSize field", async () => {
    const user = userEvent.setup();
    let requestBody: unknown;
    server.use(
      http.patch(`/api/plans/${PLAN.id}`, async ({ request }) => {
        requestBody = await request.json();
        return HttpResponse.json(PLAN);
      }),
    );

    renderWithProviders(<EditPlanModal opened plan={PLAN} onClose={() => {}} />, { uiMode: "SIMPLE" });

    // 9: within the plan's stored min(8)/max(12) - min/max stay in the form's state even though the
    // ADVANCED-only min/max inputs aren't rendered in SIMPLE (simplePlanDefaultsValidation still
    // checks the effective triple against them - see the target=15 test below for the conflicting
    // case, which this test deliberately used to dodge before the F2 review fix).
    const targetInput = screen.getByLabelText(sv.editPlanModal.targetLabelSimple);
    await user.clear(targetInput);
    await user.type(targetInput, "9");
    await user.click(screen.getByRole("button", { name: sv.editPlanModal.submit }));

    await waitFor(() => expect(requestBody).toMatchObject({ defaultGroupTargetSize: 9 }));
  });

  // v0.6.0 F2 review fix (FIX 2): both reviewers flagged that the full planDefaultsValidation set,
  // spread unchanged into SIMPLE mode's form, can attach an error to the unmounted min/max inputs -
  // the two tests below cover the resulting dead-ends and pin the fix (simplePlanDefaultsValidation,
  // planDefaults.ts).
  it("SIMPLE mode: a target above the stored (hidden) max shows ONE plain-language error on the visible field, not a silent dead-end or an error naming invisible fields", async () => {
    const user = userEvent.setup();
    let requestReceived = false;
    server.use(
      http.patch(`/api/plans/${PLAN.id}`, () => {
        requestReceived = true;
        return HttpResponse.json(PLAN);
      }),
    );

    renderWithProviders(<EditPlanModal opened plan={PLAN} onClose={() => {}} />, { uiMode: "SIMPLE" });

    // 15: above the plan's stored max of 12 - the exact value the pre-fix test above dodged by using
    // 9 instead, because the old shared planDefaultsValidation would have attached
    // effectiveSizeError(8, 15, 12) to this same target input, naming "minsta 8"/"max 12" as if they
    // were separately-editable fields the admin could see (they're both hidden in SIMPLE mode).
    const targetInput = screen.getByLabelText(sv.editPlanModal.targetLabelSimple);
    await user.clear(targetInput);
    await user.type(targetInput, "15");
    await user.click(screen.getByRole("button", { name: sv.editPlanModal.submit }));

    expect(await screen.findByText(sv.planDefaults.simpleTargetRangeError(8, 12))).toBeInTheDocument();
    expect(requestReceived).toBe(false);
  });

  it("SIMPLE mode: clearing the target field submits even when the stored (hidden) min/max would otherwise conflict with the fallback target - no silent dead-end", async () => {
    // Before the fix: planDefaultsValidation's defaultGroupMinSize/defaultGroupMaxSize validators
    // still ran against the effective triple (blank target falls back to 10, which conflicts with a
    // stored min of 20) and attached their error to those unmounted fields - form.onSubmit blocked
    // the submit, but since neither field is rendered in SIMPLE mode nothing ever appeared on
    // screen: a silent dead end. simplePlanDefaultsValidation never validates min/max at all, so this
    // now submits like any other three-state PATCH clear.
    const user = userEvent.setup();
    const conflictingPlan: ActivityPlan = {
      ...PLAN,
      defaultGroupTargetSize: 20,
      defaultGroupMinSize: 20,
      defaultGroupMaxSize: 25,
    };
    let requestBody: unknown;
    server.use(
      http.patch(`/api/plans/${PLAN.id}`, async ({ request }) => {
        requestBody = await request.json();
        return HttpResponse.json(conflictingPlan);
      }),
    );

    renderWithProviders(<EditPlanModal opened plan={conflictingPlan} onClose={() => {}} />, { uiMode: "SIMPLE" });

    await user.clear(screen.getByLabelText(sv.editPlanModal.targetLabelSimple));
    await user.click(screen.getByRole("button", { name: sv.editPlanModal.submit }));

    await waitFor(() => expect(requestBody).toBeDefined());
    const body = requestBody as Record<string, unknown>;
    expect("defaultGroupTargetSize" in body).toBe(true);
    expect(body.defaultGroupTargetSize).toBeNull();
  });
});
