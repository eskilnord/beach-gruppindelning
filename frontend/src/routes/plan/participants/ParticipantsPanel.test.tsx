/**
 * v0.6.0 audit-fix batch B (Gunilla persona audit): covers B15 (the "Anonymisera kommentarer"/"Räkna
 * om nivåer" toolbar buttons must be ADVANCED-only - a sweep test, same absent-in-SIMPLE/present-in-
 * ADVANCED style as the sibling routes/plan/uiModeCoachHiding.test.tsx sweep this file doesn't
 * touch), B16 (the SIMPLE step-framing heading/body and the "K av N klarmarkerade" summary segment),
 * and B18.1 (the grid's per-row suggestion-count badge falls back to a plain dot, never a number, in
 * SIMPLE mode - the plan-level suggestions-count endpoint has no per-kind breakdown to filter by).
 */
import type { ReactElement } from "react";
import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { setUiModeForTests } from "../../../lib/uiMode/uiModeStore";
import type { UiMode } from "../../../lib/uiMode/uiMode";
import { sv } from "../../../i18n/sv";
import { ParticipantsPanel } from "./ParticipantsPanel";

// ParticipantsPanel reads `planId` via useParams (not a prop) - mirrors uiModeCoachHiding.test.tsx's
// own renderAtRoute helper for the same reason.
function renderAtRoute(uiMode: UiMode) {
  setUiModeForTests(uiMode);
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={["/plans/plan-1/deltagare"]}>
          <Routes>
            <Route path="/plans/:planId/deltagare" element={<ParticipantsPanel /> as ReactElement} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </MantineProvider>,
  );
}

function mockEndpoints(suggestionCount = 0) {
  server.use(
    http.get("/api/plans/plan-1/participants", () =>
      HttpResponse.json([
        {
          id: "participant-1",
          personId: "person-1",
          activityPlanId: "plan-1",
          manualReviewFlag: false,
          waitlisted: false,
          reviewedDone: true,
          estimatedLevel: 500,
          manualLevelScore: null,
          importedComment: "Vill gärna spela med Anna.",
        },
        {
          id: "participant-2",
          personId: "person-2",
          activityPlanId: "plan-1",
          manualReviewFlag: false,
          waitlisted: false,
          reviewedDone: false,
          estimatedLevel: null,
          manualLevelScore: null,
        },
      ]),
    ),
    http.get("/api/persons", () =>
      HttpResponse.json([
        { id: "person-1", firstName: "Karin", lastName: "Lindqvist", displayName: "Karin Lindqvist", canBeParticipant: true, canBeCoach: false },
        { id: "person-2", firstName: "Oskar", lastName: "Bergman", displayName: "Oskar Bergman", canBeParticipant: true, canBeCoach: false },
      ]),
    ),
    http.get("/api/plans/plan-1/comment-suggestions", () =>
      HttpResponse.json(
        suggestionCount > 0
          ? [{ participantId: "participant-1", suggestionCount }]
          : [],
      ),
    ),
  );
}

describe("ParticipantsPanel toolbar (B15)", () => {
  it("shows 'Räkna om nivåer' and 'Anonymisera kommentarer' in ADVANCED", async () => {
    mockEndpoints();
    renderAtRoute("ADVANCED");
    expect(await screen.findByRole("button", { name: sv.participants.recomputeLevelsButton })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: sv.participants.anonymizeButton })).toBeInTheDocument();
  });

  it("hides 'Räkna om nivåer' and 'Anonymisera kommentarer' entirely in SIMPLE", async () => {
    mockEndpoints();
    renderAtRoute("SIMPLE");
    await screen.findByText("Karin Lindqvist");
    expect(screen.queryByRole("button", { name: sv.participants.recomputeLevelsButton })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: sv.participants.anonymizeButton })).not.toBeInTheDocument();
    expect(screen.queryByText(sv.participants.recomputeLevelsButton)).not.toBeInTheDocument();
    expect(screen.queryByText(sv.participants.anonymizeButton)).not.toBeInTheDocument();
  });
});

describe("ParticipantsPanel SIMPLE step framing (B16)", () => {
  it("shows the step heading/body and the reviewed-count segment in SIMPLE", async () => {
    mockEndpoints();
    renderAtRoute("SIMPLE");
    await screen.findByText("Karin Lindqvist");
    expect(screen.getByText(sv.simple.participants.stepHeading)).toBeInTheDocument();
    expect(screen.getByText(sv.simple.participants.stepBody)).toBeInTheDocument();
    // 1 of 2 participants (participant-1) is reviewedDone: true.
    expect(screen.getByText(sv.simple.participants.summary.reviewed(1, 2))).toBeInTheDocument();
  });

  it("never shows the step heading in ADVANCED", async () => {
    mockEndpoints();
    renderAtRoute("ADVANCED");
    await screen.findByText("Karin Lindqvist");
    expect(screen.queryByText(sv.simple.participants.stepHeading)).not.toBeInTheDocument();
    expect(screen.queryByTestId("simple-participants-step-heading")).not.toBeInTheDocument();
  });
});

describe("ParticipantsPanel comment-suggestion badge (B18.1)", () => {
  it("shows the numeric suggestion-count badge in ADVANCED", async () => {
    mockEndpoints(3);
    renderAtRoute("ADVANCED");
    expect(await screen.findByText("3")).toBeInTheDocument();
  });

  it("never shows a numeric count in SIMPLE - falls back to the plain dot badge instead", async () => {
    mockEndpoints(3);
    renderAtRoute("SIMPLE");
    await screen.findByText("Karin Lindqvist");
    expect(screen.queryByText("3")).not.toBeInTheDocument();
    // The dot-badge's own "Kommentar" label text also matches the grid's column header text
    // (same string, both rendered) - Mantine's dot-variant Badge sets data-variant="dot" (the
    // numeric badge's own variant is "filled"), so this is the unambiguous proof the dot fallback
    // rendered instead of a numeric badge.
    expect(document.querySelector('[data-variant="dot"]')).toBeInTheDocument();
    expect(document.querySelector('[data-variant="filled"]')).not.toBeInTheDocument();
  });
});
