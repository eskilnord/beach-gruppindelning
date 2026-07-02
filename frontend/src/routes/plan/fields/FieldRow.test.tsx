import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { Table } from "@mantine/core";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { FieldRow } from "./FieldRow";
import { sv } from "../../../i18n/sv";
import type { FieldDefinition, UpdateFieldDefinitionRequest } from "../../../api/types";

const PLAN_ID = "plan-1";

// Mirrors the V2-seeded 'priority' standard field: the one MEDIUM-classified row in the field
// builder (reserved for the M6a unassignedPlayer waitlist constraint, ADR-006).
const MEDIUM_PRIORITY_FIELD: FieldDefinition = {
  id: "field-priority",
  activityPlanId: undefined,
  key: "priority",
  label: "Prioritet",
  fieldType: "number",
  isStandard: true,
  storageKind: "CUSTOM",
  affectsOptimization: true,
  constraintType: "PRIORITY",
  hardOrSoft: "MEDIUM",
  weight: 100,
  direction: "HIGHER_BETTER",
  explanationText: "Prioritet vid platsbrist.",
  sortOrder: 17,
};

const SOFT_STANDARD_FIELD: FieldDefinition = {
  id: "field-playwith",
  activityPlanId: undefined,
  key: "playWith",
  label: "Vill spela med",
  fieldType: "personRelation",
  isStandard: true,
  storageKind: "CUSTOM",
  affectsOptimization: true,
  constraintType: "SAME_GROUP",
  hardOrSoft: "SOFT",
  weight: 80,
  explanationText: "",
  sortOrder: 9,
};

function renderRow(field: FieldDefinition) {
  return renderWithProviders(
    <Table>
      <Table.Tbody>
        <FieldRow field={field} planId={PLAN_ID} />
      </Table.Tbody>
    </Table>,
  );
}

describe("FieldRow - MEDIUM-reserved field (seeded 'priority')", () => {
  it("locks the affects-optimization switch and shows a MEDIUM badge instead of the HARD/SOFT control", () => {
    renderRow(MEDIUM_PRIORITY_FIELD);

    expect(screen.getByRole("switch", { name: "Påverkar optimering för Prioritet" })).toBeDisabled();
    expect(screen.getByText(sv.hardOrSoft.MEDIUM)).toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: sv.hardOrSoft.HARD })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: sv.hardOrSoft.SOFT })).not.toBeInTheDocument();
  });

  it("keeps weight and explanationText editable and PATCHes the edited weight on blur", async () => {
    let receivedBody: UpdateFieldDefinitionRequest | null = null;
    server.use(
      http.patch(`/api/field-definitions/${MEDIUM_PRIORITY_FIELD.id}`, async ({ request }) => {
        receivedBody = (await request.json()) as UpdateFieldDefinitionRequest;
        return HttpResponse.json({ ...MEDIUM_PRIORITY_FIELD, ...receivedBody });
      }),
    );

    const user = userEvent.setup();
    renderRow(MEDIUM_PRIORITY_FIELD);

    const weightInput = screen.getByRole("textbox", { name: "Vikt för Prioritet" });
    expect(weightInput).toBeEnabled();
    expect(weightInput).toHaveValue("100");
    expect(screen.getByRole("textbox", { name: "Förklaringstext för Prioritet" })).toBeEnabled();

    await user.clear(weightInput);
    await user.type(weightInput, "120");
    await user.tab();

    await waitFor(() => expect(receivedBody).toEqual({ weight: 120 }));
  });

  it("keeps the constraint select editable (backend allows constraintType changes on MEDIUM fields)", () => {
    renderRow(MEDIUM_PRIORITY_FIELD);

    expect(screen.getByRole("textbox", { name: "Constraint för Prioritet" })).toBeEnabled();
  });
});

describe("FieldRow - ordinary SOFT standard field", () => {
  it("shows the HARD/SOFT control, an enabled optimization switch, and no delete button", () => {
    renderRow(SOFT_STANDARD_FIELD);

    expect(screen.getByRole("switch", { name: "Påverkar optimering för Vill spela med" })).toBeEnabled();
    expect(screen.getByRole("radio", { name: sv.hardOrSoft.SOFT })).toBeChecked();
    expect(screen.getByRole("textbox", { name: "Vikt för Vill spela med" })).toHaveValue("80");
    expect(screen.queryByRole("button", { name: sv.fieldBuilder.deleteButton })).not.toBeInTheDocument();
  });
});
