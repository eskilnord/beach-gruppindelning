import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse, delay } from "msw";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { SheetStep } from "./SheetStep";
import { cacheImportSheets } from "../importSessionStorage";
import type { ImportAnalysis, ImportPreview, ImportSheetSummary } from "../../../api/import";

const PLAN_ID = "plan-1";
const SESSION_ID = "session-1";

const SHEETS: ImportSheetSummary[] = [
  { name: "Blad1", rowCount: 5, suggestedTemplateId: null, suggestedTemplateName: null },
  { name: "Blad2", rowCount: 5, suggestedTemplateId: null, suggestedTemplateName: null },
];

const ANALYSIS: ImportAnalysis = {
  readyToCommit: false,
  selectedSheet: "Blad2",
  headerRowIndex: 0,
  sheetReason: "Innehåller flest rader",
  sheetConfidence: 0.6,
  usedTemplate: false,
  templateId: null,
  templateName: null,
  columns: [],
  mappedCount: 0,
  ignoredCount: 0,
  playerRowCount: 0,
  warnRowCount: 0,
  skipRowCount: 0,
  warnings: [],
};

function previewFor(sheet: string): ImportPreview {
  return { sheet, headerRowIndex: 0, rowCount: 1, rows: [["a", "b"]] };
}

function mockPreviews() {
  server.use(
    http.get(`/api/plans/${PLAN_ID}/import/sessions/${SESSION_ID}/preview`, ({ request }) => {
      const sheet = new URL(request.url).searchParams.get("sheet") ?? "";
      return HttpResponse.json(previewFor(sheet));
    }),
  );
}

describe("SheetStep", () => {
  it("selects the analyzed sheet, not sheets[0], even when analysis resolves AFTER the first render (A2 pre-selection race)", async () => {
    cacheImportSheets(SESSION_ID, SHEETS);
    mockPreviews();
    server.use(
      http.get(`/api/plans/${PLAN_ID}/import/sessions/${SESSION_ID}/analysis`, async () => {
        // Simulate the analysis query still being in flight on first render.
        await delay(20);
        return HttpResponse.json(ANALYSIS);
      }),
    );

    renderWithProviders(
      <SheetStep planId={PLAN_ID} sessionId={SESSION_ID} onNext={() => {}} onBack={() => {}} onExpired={() => {}} />,
    );

    // Before analysis resolves, nothing is pre-selected yet (no sheets[0] fallback jump).
    expect(screen.queryByRole("tab", { selected: true })).not.toBeInTheDocument();

    await waitFor(() => expect(screen.getByRole("tab", { name: "Blad2" })).toHaveAttribute("aria-selected", "true"));
    expect(screen.getByRole("tab", { name: "Blad1" })).toHaveAttribute("aria-selected", "false");
  });
});
