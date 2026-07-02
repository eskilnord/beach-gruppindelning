import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { ValidateStep } from "./ValidateStep";
import { sv } from "../../../i18n/sv";
import type { ImportRowDecision, ImportValidation } from "../../../api/import";
import type { Person } from "../../../api/types";

const PLAN_ID = "plan-1";
const SESSION_ID = "session-1";

const VALIDATION: ImportValidation = {
  totalRows: 3,
  okCount: 1,
  warnCount: 1,
  skipCount: 1,
  rows: [
    { rowIndex: 0, status: "OK", reasons: [], matchProposals: [] },
    {
      rowIndex: 1,
      status: "WARN",
      reasons: ["Möjlig dubblett baserat på e-post"],
      matchProposals: [{ existingPersonId: "person-1", matchBasis: "EMAIL_EXACT", confidence: 1.0 }],
    },
    { rowIndex: 2, status: "SKIP", reasons: ["Saknat namn"], matchProposals: [] },
  ],
};

const PERSON: Person = {
  id: "person-1",
  firstName: "Erik",
  lastName: "Svensson",
  displayName: "",
  email: "erik.svensson@example.se",
  phone: "",
  externalId: "",
  canBeParticipant: true,
  canBeCoach: false,
  notes: "",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

function mockValidationAndPersons() {
  server.use(
    http.get(`/api/plans/${PLAN_ID}/import/sessions/${SESSION_ID}/validate`, () => HttpResponse.json(VALIDATION)),
    http.get("/api/persons", () => HttpResponse.json([PERSON])),
  );
}

// Mantine's Select dropdown is a floating-ui Popover; in jsdom (no real layout engine) floating-ui's
// `hide` middleware always concludes the reference is clipped and sets the panel to `display: none`
// even while open (see frontend/src/test/setup.ts) — its content (role="option") is still correctly
// in the DOM, so option queries below pass `hidden: true` to look past that cosmetic state.
describe("ValidateStep", () => {
  it("renders each row's status with a distinct color and shows its reasons", async () => {
    mockValidationAndPersons();

    renderWithProviders(
      <ValidateStep planId={PLAN_ID} sessionId={SESSION_ID} onNext={() => {}} onExpired={() => {}} />,
    );

    expect(await screen.findByText(sv.importWizard.validate.summary(1, 1, 1))).toBeInTheDocument();

    const okBadge = screen.getByText(sv.importWizard.validate.status.OK).parentElement as HTMLElement;
    const warnBadge = screen.getByText(sv.importWizard.validate.status.WARN).parentElement as HTMLElement;
    const skipBadge = screen.getByText(sv.importWizard.validate.status.SKIP).parentElement as HTMLElement;

    expect(okBadge.style.getPropertyValue("--badge-bg")).toContain("green");
    expect(warnBadge.style.getPropertyValue("--badge-bg")).toContain("yellow");
    expect(skipBadge.style.getPropertyValue("--badge-bg")).toContain("red");

    // Colors are all distinct from one another.
    const colors = [okBadge, warnBadge, skipBadge].map((badge) => badge.style.getPropertyValue("--badge-bg"));
    expect(new Set(colors).size).toBe(3);

    expect(screen.getByText("Möjlig dubblett baserat på e-post")).toBeInTheDocument();
    expect(screen.getByText("Saknat namn")).toBeInTheDocument();
  });

  it("offers a match-proposal choice for a row with a potential duplicate, and saves the decision", async () => {
    mockValidationAndPersons();

    let receivedDecisions: Record<string, ImportRowDecision> | null = null;
    server.use(
      http.put(`/api/plans/${PLAN_ID}/import/sessions/${SESSION_ID}/decisions`, async ({ request }) => {
        receivedDecisions = (await request.json()) as Record<string, ImportRowDecision>;
        return HttpResponse.json(receivedDecisions);
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(
      <ValidateStep planId={PLAN_ID} sessionId={SESSION_ID} onNext={() => {}} onExpired={() => {}} />,
    );

    const decisionSelect = await screen.findByRole("textbox", { name: "Beslut för rad 1" });
    // Defaults to "Skapa ny" — never silently pre-selecting the matched person (mirrors the
    // backend's own commit-time default, ImportCommitService).
    expect(decisionSelect).toHaveValue(sv.importWizard.validate.decision.createNew);

    await user.click(decisionSelect);
    const matchLabel = sv.importWizard.validate.decision.matchExisting(
      "Erik Svensson",
      sv.importWizard.validate.matchBasis.EMAIL_EXACT,
    );
    await user.click(await screen.findByRole("option", { name: matchLabel, hidden: true }));

    expect(decisionSelect).toHaveValue(matchLabel);

    await waitFor(() => expect(receivedDecisions).toEqual({ "1": { action: "MATCH_EXISTING", personId: "person-1" } }));
  });
});
