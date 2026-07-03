import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../../test/server";
import { renderWithProviders } from "../../test/renderWithProviders";
import { sv } from "../../i18n/sv";
import { CreatePlanModal } from "./CreatePlanModal";

const SEASON_ID = "season-1";

describe("CreatePlanModal", () => {
  it("renders the 'Standardvärden för grupper' section with all four optional inputs", () => {
    renderWithProviders(<CreatePlanModal opened seasonId={SEASON_ID} onClose={() => {}} onCreated={() => {}} />);

    expect(screen.getByText(sv.planDefaults.heading)).toBeInTheDocument();
    expect(screen.getByLabelText(sv.planDefaults.targetLabel)).toBeInTheDocument();
    expect(screen.getByLabelText(sv.planDefaults.minLabel)).toBeInTheDocument();
    expect(screen.getByLabelText(sv.planDefaults.maxLabel)).toBeInTheDocument();
    expect(screen.getByLabelText(sv.planDefaults.levelMinLabel)).toBeInTheDocument();
  });

  it("submits the four defaults alongside name/category when all are filled in", async () => {
    const user = userEvent.setup();
    let requestBody: unknown;
    server.use(
      http.post(`/api/seasons/${SEASON_ID}/plans`, async ({ request }) => {
        requestBody = await request.json();
        return HttpResponse.json({
          id: "plan-1",
          seasonPlanId: SEASON_ID,
          name: "Herr",
          status: "draft",
          createdAt: "2026-01-01T00:00:00Z",
          updatedAt: "2026-01-01T00:00:00Z",
        }, { status: 201 });
      }),
    );

    let createdId: string | undefined;
    renderWithProviders(
      <CreatePlanModal opened seasonId={SEASON_ID} onClose={() => {}} onCreated={(id) => (createdId = id)} />,
    );

    await user.type(screen.getByLabelText(sv.createPlanModal.nameLabel, { exact: false }), "Herr");
    await user.type(screen.getByLabelText(sv.planDefaults.targetLabel), "10");
    await user.type(screen.getByLabelText(sv.planDefaults.minLabel), "8");
    await user.type(screen.getByLabelText(sv.planDefaults.maxLabel), "12");
    await user.type(screen.getByLabelText(sv.planDefaults.levelMinLabel), "300");

    await user.click(screen.getByRole("button", { name: sv.createPlanModal.submit }));

    await waitFor(() => expect(createdId).toBe("plan-1"));
    expect(requestBody).toMatchObject({
      name: "Herr",
      defaultGroupTargetSize: 10,
      defaultGroupMinSize: 8,
      defaultGroupMaxSize: 12,
      defaultLevelMin: 300,
    });
  });

  it("rejects a minimum size set above the target size before submitting", async () => {
    const user = userEvent.setup();
    let requestReceived = false;
    server.use(
      http.post(`/api/seasons/${SEASON_ID}/plans`, () => {
        requestReceived = true;
        return HttpResponse.json({}, { status: 201 });
      }),
    );

    renderWithProviders(<CreatePlanModal opened seasonId={SEASON_ID} onClose={() => {}} onCreated={() => {}} />);

    await user.type(screen.getByLabelText(sv.createPlanModal.nameLabel, { exact: false }), "Herr");
    await user.type(screen.getByLabelText(sv.planDefaults.targetLabel), "5");
    await user.type(screen.getByLabelText(sv.planDefaults.minLabel), "9");

    await user.click(screen.getByRole("button", { name: sv.createPlanModal.submit }));

    // Effective triple: min 9, target 5, max 7 (derived from target). The error renders under
    // every explicitly-set field participating in the conflict, hence findAllByText.
    expect((await screen.findAllByText(sv.planDefaults.effectiveSizeError(9, 5, 7))).length).toBeGreaterThan(0);
    expect(requestReceived).toBe(false);
  });

  it("rejects min=20 alone against the effective fallback target 10 / max 12 (Finding 2)", async () => {
    const user = userEvent.setup();
    let requestReceived = false;
    server.use(
      http.post(`/api/seasons/${SEASON_ID}/plans`, () => {
        requestReceived = true;
        return HttpResponse.json({}, { status: 201 });
      }),
    );

    renderWithProviders(<CreatePlanModal opened seasonId={SEASON_ID} onClose={() => {}} onCreated={() => {}} />);

    await user.type(screen.getByLabelText(sv.createPlanModal.nameLabel, { exact: false }), "Herr");
    await user.type(screen.getByLabelText(sv.planDefaults.minLabel), "20");

    await user.click(screen.getByRole("button", { name: sv.createPlanModal.submit }));

    expect((await screen.findAllByText(sv.planDefaults.effectiveSizeError(20, 10, 12))).length).toBeGreaterThan(0);
    expect(requestReceived).toBe(false);
  });

  it("rejects min above max with target blank via the effective fallback target (Finding 3)", async () => {
    const user = userEvent.setup();
    let requestReceived = false;
    server.use(
      http.post(`/api/seasons/${SEASON_ID}/plans`, () => {
        requestReceived = true;
        return HttpResponse.json({}, { status: 201 });
      }),
    );

    renderWithProviders(<CreatePlanModal opened seasonId={SEASON_ID} onClose={() => {}} onCreated={() => {}} />);

    await user.type(screen.getByLabelText(sv.createPlanModal.nameLabel, { exact: false }), "Herr");
    await user.type(screen.getByLabelText(sv.planDefaults.minLabel), "8");
    await user.type(screen.getByLabelText(sv.planDefaults.maxLabel), "5");

    await user.click(screen.getByRole("button", { name: sv.createPlanModal.submit }));

    // Effective triple: min 8, target 10 (fallback), max 5.
    expect((await screen.findAllByText(sv.planDefaults.effectiveSizeError(8, 10, 5))).length).toBeGreaterThan(0);
    expect(requestReceived).toBe(false);
  });

  // v0.3.0 WI-3 smoke test: the Kategori field gained a HelpTip via its `description` slot (its
  // `label` stays exactly "Kategori" - untested by e2e, but kept untouched for consistency anyway).
  it("renders a HelpTip for the Kategori field (v0.3.0 WI-3)", () => {
    renderWithProviders(<CreatePlanModal opened seasonId={SEASON_ID} onClose={() => {}} onCreated={() => {}} />);

    expect(screen.getByRole("button", { name: sv.help.ariaLabel(sv.createPlanModal.categoryLabel) })).toBeInTheDocument();
  });

  it("rejects a min-nivå outside the 0-1000 range before submitting", async () => {
    const user = userEvent.setup();
    let requestReceived = false;
    server.use(
      http.post(`/api/seasons/${SEASON_ID}/plans`, () => {
        requestReceived = true;
        return HttpResponse.json({}, { status: 201 });
      }),
    );

    renderWithProviders(<CreatePlanModal opened seasonId={SEASON_ID} onClose={() => {}} onCreated={() => {}} />);

    await user.type(screen.getByLabelText(sv.createPlanModal.nameLabel, { exact: false }), "Herr");
    await user.type(screen.getByLabelText(sv.planDefaults.levelMinLabel), "1500");

    await user.click(screen.getByRole("button", { name: sv.createPlanModal.submit }));

    expect(await screen.findByText(sv.planDefaults.levelMinRangeError)).toBeInTheDocument();
    expect(requestReceived).toBe(false);
  });
});
