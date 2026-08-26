import { describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { FileStep } from "./FileStep";
import { sv } from "../../../i18n/sv";

const PLAN_ID = "plan-1";

/** No drag-and-drop simulation here (jsdom's DataTransfer is awkward to construct) - handleFile is
 *  reached the same way for a drop or a FileButton pick, so tests below use the hidden native
 *  `<input type="file">` FileButton renders, same pattern as other step tests' file inputs. */
function fileInput(): HTMLInputElement {
  return document.querySelector('input[type="file"]') as HTMLInputElement;
}

async function dropFile(file: File) {
  const input = fileInput();
  Object.defineProperty(input, "files", { value: [file], configurable: true });
  input.dispatchEvent(new Event("change", { bubbles: true }));
  // Let React's onChange handler (async handleFile) run.
  await Promise.resolve();
}

describe("FileStep", () => {
  it("shows a persistent (non-toast) message naming the file for a rejected .xls upload", async () => {
    renderWithProviders(<FileStep planId={PLAN_ID} onUploaded={() => {}} />);

    await dropFile(new File(["data"], "Anmalningar.xls", { type: "application/vnd.ms-excel" }));

    expect(await screen.findByText(sv.importWizard.file.legacyXlsTitle("Anmalningar.xls"))).toBeInTheDocument();
    expect(screen.getByText(sv.importWizard.file.legacyXlsMessage)).toBeInTheDocument();
  });

  it("shows the network-failure text (not a generic/parse message) when the upload request never reaches the backend", async () => {
    server.use(
      http.post(`/api/plans/${PLAN_ID}/import/sessions`, () => HttpResponse.error()),
    );

    renderWithProviders(<FileStep planId={PLAN_ID} onUploaded={() => {}} />);

    await dropFile(new File(["a,b\n1,2"], "roster.csv", { type: "text/csv" }));

    expect(await screen.findByText(sv.importWizard.networkError)).toBeInTheDocument();
  });

  it("shows the backend's own Swedish message when the server rejects a parseable-but-invalid file", async () => {
    const backendMessage = "Filen saknar rader att importera";
    server.use(
      http.post(`/api/plans/${PLAN_ID}/import/sessions`, () =>
        HttpResponse.json({ error: backendMessage }, { status: 400 }),
      ),
    );

    renderWithProviders(<FileStep planId={PLAN_ID} onUploaded={() => {}} />);

    await dropFile(new File(["a,b\n1,2"], "roster.csv", { type: "text/csv" }));

    expect(await screen.findByText(backendMessage)).toBeInTheDocument();
  });

  it("calls onUploaded with the created session on success", async () => {
    const created = {
      sessionId: "session-1",
      sheets: [{ name: "Blad1", rowCount: 2, suggestedTemplateId: null, suggestedTemplateName: null }],
      analysis: { readyToCommit: true } as unknown,
    };
    server.use(
      http.post(`/api/plans/${PLAN_ID}/import/sessions`, () => HttpResponse.json(created)),
    );

    const onUploaded = vi.fn();
    renderWithProviders(<FileStep planId={PLAN_ID} onUploaded={onUploaded} />);

    await dropFile(new File(["a,b\n1,2"], "roster.csv", { type: "text/csv" }));

    await waitFor(() => expect(onUploaded).toHaveBeenCalledWith("session-1", created.analysis));
  });
});
