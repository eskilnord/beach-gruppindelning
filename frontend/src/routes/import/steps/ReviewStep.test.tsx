import { describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { ReviewStep } from "./ReviewStep";
import { sv } from "../../../i18n/sv";
import type { ImportAnalysis, ImportCommitResult } from "../../../api/import";

const PLAN_ID = "plan-1";
const SESSION_ID = "session-1";

const READY_ANALYSIS: ImportAnalysis = {
  readyToCommit: true,
  selectedSheet: "Blad1",
  headerRowIndex: 0,
  sheetReason: "Enda bladet i filen",
  sheetConfidence: 1,
  usedTemplate: false,
  templateId: null,
  templateName: null,
  columns: [
    {
      columnIndex: 0,
      headerText: "Förnamn",
      target: "firstName",
      reason: "Matchar kolumnnamnet",
      confidence: 1,
      synthetic: false,
    },
  ],
  mappedCount: 1,
  ignoredCount: 0,
  playerRowCount: 3,
  warnRowCount: 0,
  skipRowCount: 0,
  warnings: [],
};

const COMMIT_RESULT: ImportCommitResult = {
  imported: 3,
  skipped: 0,
  warnings: [],
  importRunId: "run-1",
  savedTemplateId: null,
};

function mockAnalysis(analysis: ImportAnalysis) {
  server.use(
    http.get(`/api/plans/${PLAN_ID}/import/sessions/${SESSION_ID}/analysis`, () => HttpResponse.json(analysis)),
  );
}

describe("ReviewStep", () => {
  it("renders the one-click review card with the sheet testid once analysis is ready", async () => {
    mockAnalysis(READY_ANALYSIS);

    renderWithProviders(
      <ReviewStep planId={PLAN_ID} sessionId={SESSION_ID} onAdjust={() => {}} onRestart={() => {}} onExpired={() => {}} />,
    );

    expect(await screen.findByRole("heading", { name: sv.importWizard.review.heading, level: 4 })).toBeInTheDocument();
    const sheetLabel = await screen.findByTestId("import-review-sheet");
    expect(sheetLabel).toHaveTextContent("Blad1");
    expect(screen.getByRole("button", { name: sv.importWizard.review.importButton })).toBeInTheDocument();
  });

  it("shows SessionExpiredPanel when the analysis fetch 404s", async () => {
    server.use(
      http.get(`/api/plans/${PLAN_ID}/import/sessions/${SESSION_ID}/analysis`, () =>
        HttpResponse.json({ error: "not found" }, { status: 404 }),
      ),
    );

    const onExpired = vi.fn();
    renderWithProviders(
      <ReviewStep planId={PLAN_ID} sessionId={SESSION_ID} onAdjust={() => {}} onRestart={() => {}} onExpired={onExpired} />,
    );

    expect(await screen.findByText(sv.importWizard.sessionExpired.title)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: sv.importWizard.sessionExpired.restartButton }));
    expect(onExpired).toHaveBeenCalledTimes(1);
  });

  it("shows an error notification when the commit fails", async () => {
    mockAnalysis(READY_ANALYSIS);
    server.use(
      http.post(`/api/plans/${PLAN_ID}/import/sessions/${SESSION_ID}/commit`, () =>
        HttpResponse.json({ error: sv.importWizard.commit.commitFailed }, { status: 500 }),
      ),
    );

    const user = userEvent.setup();
    renderWithProviders(
      <ReviewStep planId={PLAN_ID} sessionId={SESSION_ID} onAdjust={() => {}} onRestart={() => {}} onExpired={() => {}} />,
    );

    await user.click(await screen.findByRole("button", { name: sv.importWizard.review.importButton }));

    expect(await screen.findByText(sv.importWizard.commit.commitFailed)).toBeInTheDocument();
  });

  it("shows the commit result screen (via the shared ImportResultView) once commit succeeds", async () => {
    mockAnalysis(READY_ANALYSIS);
    server.use(
      http.post(`/api/plans/${PLAN_ID}/import/sessions/${SESSION_ID}/commit`, () =>
        HttpResponse.json(COMMIT_RESULT),
      ),
    );

    const user = userEvent.setup();
    renderWithProviders(
      <ReviewStep planId={PLAN_ID} sessionId={SESSION_ID} onAdjust={() => {}} onRestart={() => {}} onExpired={() => {}} />,
    );

    await user.click(await screen.findByRole("button", { name: sv.importWizard.review.importButton }));

    expect(await screen.findByText(sv.importWizard.commit.resultSummary(3, 0))).toBeInTheDocument();
  });

  it("redirects via onAdjust instead of rendering the one-click card when analysis is not ready (A7 gate bypass fix)", async () => {
    mockAnalysis({ ...READY_ANALYSIS, readyToCommit: false });

    const onAdjust = vi.fn();
    renderWithProviders(
      <ReviewStep planId={PLAN_ID} sessionId={SESSION_ID} onAdjust={onAdjust} onRestart={() => {}} onExpired={() => {}} />,
    );

    await waitFor(() => expect(onAdjust).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole("heading", { name: sv.importWizard.review.heading, level: 4 })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: sv.importWizard.review.importButton })).not.toBeInTheDocument();
  });
});
