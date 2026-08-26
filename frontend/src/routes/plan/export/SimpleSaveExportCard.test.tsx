import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import { Notifications } from "@mantine/notifications";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { sv } from "../../../i18n/sv";
import * as platform from "../../../lib/platform";
import { SimpleSaveExportCard } from "./SimpleSaveExportCard";

const PLAN_ID = "plan-1";
const PLAN_NAME = "Herr A";

/** SimpleSaveExportCard reads planId via useParams - needs a matched route, same pattern as
 *  ExportPanel.test.tsx. */
function renderCard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  return render(
    <MantineProvider>
      <Notifications />
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[`/plans/${PLAN_ID}/export`]}>
          <Routes>
            <Route path="/plans/:planId/export" element={<SimpleSaveExportCard />} />
            <Route path="/plans/:planId/optimering" element={<div data-testid="optimering-route" />} />
          </Routes>
          <LocationDisplay />
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

function LocationDisplay() {
  const location = useLocation();
  return <div data-testid="current-path">{location.pathname}</div>;
}

function mockPlan() {
  server.use(http.get(`/api/plans/${PLAN_ID}`, () => HttpResponse.json({ id: PLAN_ID, name: PLAN_NAME })));
}

function mockNoSavedPlans() {
  server.use(http.get(`/api/plans/${PLAN_ID}/saved-plans`, () => HttpResponse.json([])));
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("SimpleSaveExportCard", () => {
  it("prefills the save name with '<plan name> <YYYY-MM-DD>'", async () => {
    mockPlan();
    mockNoSavedPlans();
    server.use(http.get(`/api/plans/${PLAN_ID}/runs`, () => HttpResponse.json([])));

    renderCard();

    // v0.6.0 F6 review fix (FIX 6, MINOR): was `new Date().toISOString().slice(0, 10)` - toISOString
    // is UTC, while the implementation (SimpleSaveExportCard.tsx's todayDateStamp) builds the stamp
    // from LOCAL date parts. Those disagree near local midnight in any timezone ahead of UTC, making
    // the old assertion flaky depending on the CI/dev machine's timezone and time of day. Building the
    // expected value from local parts too matches the implementation exactly.
    const now = new Date();
    const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
    const input = await screen.findByTestId("simple-save-name-input");
    await waitFor(() => expect(input).toHaveValue(`${PLAN_NAME} ${today}`));
  });

  // v0.6.0 audit batch D (D6): the card now explains what "Spara plan" even does before the name
  // field, instead of just showing a bare label.
  it("shows the 'sparas automatiskt / spara en kopia' explanation above the name field", async () => {
    mockPlan();
    mockNoSavedPlans();
    server.use(http.get(`/api/plans/${PLAN_ID}/runs`, () => HttpResponse.json([])));

    renderCard();

    expect(await screen.findByText(sv.simple.saveExport.intro)).toBeInTheDocument();
  });

  it("shows a green 'Sparad ✓ <tidpunkt>' inline alert on a successful save", async () => {
    mockPlan();
    mockNoSavedPlans();
    server.use(
      http.get(`/api/plans/${PLAN_ID}/runs`, () => HttpResponse.json([])),
      http.post(`/api/plans/${PLAN_ID}/saved-plans`, () =>
        HttpResponse.json({
          id: "sp-1",
          activityPlanId: PLAN_ID,
          name: "Herr A 2026-01-01",
          status: "saved",
          createdAt: "2026-01-01T10:00:00Z",
          updatedAt: "2026-01-01T10:00:00Z",
          snapshot: {},
        }),
      ),
    );

    const user = userEvent.setup();
    renderCard();
    await screen.findByTestId("simple-save-name-input");

    await user.click(screen.getByTestId("simple-save-button"));

    const success = await screen.findByTestId("simple-save-success");
    expect(success).toHaveTextContent("Sparad ✓");
  });

  it("shows the ApiError message in a red inline alert on a failed save", async () => {
    mockPlan();
    mockNoSavedPlans();
    // v0.6.0 F6 review fix (FIX 6, MINOR): the 409 status and "Namnet är redan använt" message here
    // are synthetic - a plausible-looking failure this test made up to exercise the generic ApiError
    // rendering path (handleSave's onError falls back to `error.message`), not an assertion about
    // what the real `POST .../saved-plans` backend contract actually returns for a name conflict.
    server.use(
      http.get(`/api/plans/${PLAN_ID}/runs`, () => HttpResponse.json([])),
      http.post(`/api/plans/${PLAN_ID}/saved-plans`, () =>
        HttpResponse.json({ error: "Namnet är redan använt" }, { status: 409 }),
      ),
    );

    const user = userEvent.setup();
    renderCard();
    await screen.findByTestId("simple-save-name-input");

    await user.click(screen.getByTestId("simple-save-button"));

    const error = await screen.findByTestId("simple-save-error");
    expect(error).toHaveTextContent("Namnet är redan använt");
  });

  // v0.6.0 audit batch D (D6): double-save guard - saving under a name that already matches an
  // existing saved version asks for confirmation instead of silently creating a second copy.
  it("confirms before saving under a name that duplicates an existing saved version", async () => {
    mockPlan();
    server.use(
      http.get(`/api/plans/${PLAN_ID}/runs`, () => HttpResponse.json([])),
      http.get(`/api/plans/${PLAN_ID}/saved-plans`, () =>
        HttpResponse.json([
          { id: "sp-1", activityPlanId: PLAN_ID, name: `${PLAN_NAME} dup`, status: "saved", createdAt: "2026-01-01T10:00:00Z" },
        ]),
      ),
      http.post(`/api/plans/${PLAN_ID}/saved-plans`, () =>
        HttpResponse.json({
          id: "sp-2",
          activityPlanId: PLAN_ID,
          name: `${PLAN_NAME} dup`,
          status: "saved",
          createdAt: "2026-01-02T10:00:00Z",
          updatedAt: "2026-01-02T10:00:00Z",
          snapshot: {},
        }),
      ),
    );

    const user = userEvent.setup();
    renderCard();
    const nameInput = await screen.findByTestId("simple-save-name-input");
    // Wait for the async prefill (todayDateStamp effect) to land before typing over it - otherwise it
    // can fire mid-keystroke and clobber a partially-typed value.
    await waitFor(() => expect((nameInput as HTMLInputElement).value.length).toBeGreaterThan(0));
    await user.clear(nameInput);
    await user.type(nameInput, `${PLAN_NAME} dup`);

    await user.click(screen.getByTestId("simple-save-button"));

    const dialog = await screen.findByRole("dialog", { name: sv.simple.saveExport.duplicateNameConfirm.title });
    expect(dialog).toHaveTextContent(sv.simple.saveExport.duplicateNameConfirm.message);
    // Not saved yet - only after the confirm click below.
    expect(screen.queryByTestId("simple-save-success")).not.toBeInTheDocument();

    await user.click(within(dialog).getByTestId("simple-save-duplicate-confirm"));

    expect(await screen.findByTestId("simple-save-success")).toBeInTheDocument();
  });

  // v0.6.0 audit batch D (D6): the compact, read-only list of this plan's saved versions, newest
  // first (useSavedPlans itself returns oldest-first - its own doc comment).
  it("renders the saved-versions list newest first", async () => {
    mockPlan();
    server.use(
      http.get(`/api/plans/${PLAN_ID}/runs`, () => HttpResponse.json([])),
      http.get(`/api/plans/${PLAN_ID}/saved-plans`, () =>
        HttpResponse.json([
          { id: "sp-1", activityPlanId: PLAN_ID, name: "Första", status: "saved", createdAt: "2026-01-01T10:00:00Z" },
          { id: "sp-2", activityPlanId: PLAN_ID, name: "Andra", status: "saved", createdAt: "2026-01-02T10:00:00Z" },
        ]),
      ),
    );

    renderCard();

    const list = await screen.findByTestId("simple-saved-versions-list");
    const items = within(list).getAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent("Andra");
    expect(items[1]).toHaveTextContent("Första");
  });

  it("shows the empty saved-versions message when the plan has no saved versions yet", async () => {
    mockPlan();
    mockNoSavedPlans();
    server.use(http.get(`/api/plans/${PLAN_ID}/runs`, () => HttpResponse.json([])));

    renderCard();

    expect(await screen.findByTestId("simple-saved-versions-empty")).toHaveTextContent(
      sv.simple.saveExport.savedVersionsEmpty,
    );
  });

  // v0.6.0 audit batch D (D8): replaces the borrowed advanced "kör en optimering först" copy with
  // SIMPLE's own step-based wording plus a working "Gå till Optimera" button.
  it("shows the SIMPLE-worded no-run gate with a working 'Gå till Optimera' button, and disables export", async () => {
    mockPlan();
    mockNoSavedPlans();
    server.use(http.get(`/api/plans/${PLAN_ID}/runs`, () => HttpResponse.json([])));

    renderCard();

    const hint = await screen.findByTestId("export-empty-hint");
    expect(hint).toHaveTextContent(sv.simple.saveExport.noRun.message);
    expect(screen.getByTestId("simple-export-button")).toBeDisabled();

    const user = userEvent.setup();
    await user.click(within(hint).getByRole("button", { name: sv.simple.saveExport.noRun.button }));
    expect(await screen.findByTestId("optimering-route")).toBeInTheDocument();
  });

  // v0.6.0 audit batch D (D8): a FAILED runs query is not evidence that zero runs exist - it must
  // render its own distinct error+retry state, never the "no run yet" claim.
  it("distinguishes a FAILED runs query from zero runs", async () => {
    mockPlan();
    mockNoSavedPlans();
    server.use(http.get(`/api/plans/${PLAN_ID}/runs`, () => HttpResponse.json({ error: "boom" }, { status: 500 })));

    renderCard();

    const errorAlert = await screen.findByTestId("export-runs-error");
    expect(errorAlert).toHaveTextContent(sv.simple.saveExport.loadRunsFailed);
    expect(screen.queryByTestId("export-empty-hint")).not.toBeInTheDocument();
    expect(screen.getByTestId("simple-export-button")).toBeDisabled();
  });

  it("shows the 'Fler format finns i avancerat läge' hint last", async () => {
    mockPlan();
    mockNoSavedPlans();
    server.use(http.get(`/api/plans/${PLAN_ID}/runs`, () => HttpResponse.json([])));

    renderCard();

    expect(await screen.findByText(sv.simple.saveExport.advancedHint)).toBeInTheDocument();
  });

  // v0.6.0 audit batch D (D7): the export explanation line under the button.
  it("shows the export explanation under the export button", async () => {
    mockPlan();
    mockNoSavedPlans();
    server.use(http.get(`/api/plans/${PLAN_ID}/runs`, () => HttpResponse.json([])));

    renderCard();

    expect(await screen.findByText(sv.simple.saveExport.exportExplanation)).toBeInTheDocument();
  });

  // Pinned request-body test (F6 hard requirement): comments can NEVER leak through the simple
  // export path, regardless of the (nonexistent, in this UI) comments checkbox - the request is
  // hardcoded to format=xlsx&layout=grouped&includeComments=false.
  it("exports with the pinned request body {format:'xlsx', layout:'grouped', includeComments:false}", async () => {
    mockPlan();
    mockNoSavedPlans();
    server.use(http.get(`/api/plans/${PLAN_ID}/runs`, () => HttpResponse.json([{ id: "run-1" }])));

    let capturedUrl: URL | null = null;
    server.use(
      http.get(`/api/plans/${PLAN_ID}/export`, ({ request }) => {
        capturedUrl = new URL(request.url);
        return new HttpResponse("fake xlsx binary content", {
          headers: {
            "Content-Disposition": `attachment; filename="${PLAN_NAME}_export.xlsx"`,
            "Content-Type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          },
        });
      }),
    );

    const user = userEvent.setup();
    renderCard();

    const exportButton = await screen.findByTestId("simple-export-button");
    await waitFor(() => expect(exportButton).toBeEnabled());
    await user.click(exportButton);

    await screen.findByText(sv.export.exportSuccess);

    expect(capturedUrl).not.toBeNull();
    const params = capturedUrl!.searchParams;
    expect(params.get("format")).toBe("xlsx");
    expect(params.get("layout")).toBe("grouped");
    expect(params.get("includeComments")).toBe("false");
  });

  // v0.6.0 audit batch D (D7): a cancelled Tauri save dialog (saveFile resolving `{saved: false}`)
  // used to be completely silent - now surfaced as a subtle inline note under the export button.
  it("shows a subtle 'Exporten avbröts.' note when the save dialog is cancelled", async () => {
    mockPlan();
    mockNoSavedPlans();
    server.use(
      http.get(`/api/plans/${PLAN_ID}/runs`, () => HttpResponse.json([{ id: "run-1" }])),
      http.get(`/api/plans/${PLAN_ID}/export`, () =>
        new HttpResponse("fake xlsx binary content", {
          headers: {
            "Content-Disposition": `attachment; filename="${PLAN_NAME}_export.xlsx"`,
            "Content-Type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          },
        }),
      ),
    );
    vi.spyOn(platform, "saveFile").mockResolvedValueOnce({ saved: false, isTauriSave: true });

    const user = userEvent.setup();
    renderCard();

    const exportButton = await screen.findByTestId("simple-export-button");
    await waitFor(() => expect(exportButton).toBeEnabled());
    await user.click(exportButton);

    expect(await screen.findByTestId("simple-export-cancelled-note")).toHaveTextContent(sv.export.exportCancelled);
  });
});
