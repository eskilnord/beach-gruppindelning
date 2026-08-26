import { describe, expect, it } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import { Notifications } from "@mantine/notifications";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { sv } from "../../../i18n/sv";
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
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

function mockPlan() {
  server.use(http.get(`/api/plans/${PLAN_ID}`, () => HttpResponse.json({ id: PLAN_ID, name: PLAN_NAME })));
}

describe("SimpleSaveExportCard", () => {
  it("prefills the save name with '<plan name> <YYYY-MM-DD>'", async () => {
    mockPlan();
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

  it("shows a green 'Sparad ✓ <tidpunkt>' inline alert on a successful save", async () => {
    mockPlan();
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

  it("preserves the 'kör en optimering först' empty hint and disables export until a run exists", async () => {
    mockPlan();
    server.use(http.get(`/api/plans/${PLAN_ID}/runs`, () => HttpResponse.json([])));

    renderCard();

    expect(await screen.findByTestId("export-empty-hint")).toHaveTextContent(sv.export.emptyNoRun);
    expect(screen.getByTestId("simple-export-button")).toBeDisabled();
  });

  it("shows the 'Fler format och exportval finns i avancerat läge' hint", async () => {
    mockPlan();
    server.use(http.get(`/api/plans/${PLAN_ID}/runs`, () => HttpResponse.json([])));

    renderCard();

    expect(await screen.findByText(sv.simple.saveExport.advancedHint)).toBeInTheDocument();
  });

  // Pinned request-body test (F6 hard requirement): comments can NEVER leak through the simple
  // export path, regardless of the (nonexistent, in this UI) comments checkbox - the request is
  // hardcoded to format=xlsx&layout=grouped&includeComments=false.
  it("exports with the pinned request body {format:'xlsx', layout:'grouped', includeComments:false}", async () => {
    mockPlan();
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
});
