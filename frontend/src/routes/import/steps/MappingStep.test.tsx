import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { MappingStep } from "./MappingStep";
import { sv } from "../../../i18n/sv";
import type { ImportColumnMapping, ImportColumns } from "../../../api/import";

const PLAN_ID = "plan-1";
const SESSION_ID = "session-1";

const COLUMNS: ImportColumns = {
  sheet: "Blad1",
  headerRowIndex: 0,
  columns: [
    { columnIndex: 0, headerText: "Förnamn", sampleValues: ["Anna", "Björn"], suggestedTarget: "firstName" },
    { columnIndex: 1, headerText: "Kommentar", sampleValues: ["Allergisk mot jordnötter"], suggestedTarget: "comment" },
    { columnIndex: 2, headerText: "Mystisk kolumn", sampleValues: ["x"], suggestedTarget: null },
  ],
};

function mockColumnsAndFields() {
  server.use(
    http.get(`/api/plans/${PLAN_ID}/import/sessions/${SESSION_ID}/columns`, () => HttpResponse.json(COLUMNS)),
    http.get(`/api/plans/${PLAN_ID}/field-definitions`, () => HttpResponse.json([])),
  );
}

/**
 * Mantine's Combobox keeps every dropdown's option list mounted at all times (`keepMounted: true`
 * by default), toggling only the Popover's open/closed CSS state — so with several target
 * dropdowns on the page (one per column) sharing the same standard-target vocabulary, a plain
 * `getByRole("option", { name, hidden: true })` can match the *same* option label in more than one
 * column's (closed) listbox. Scoping to the clicked select's own listbox via its `aria-controls` id
 * disambiguates regardless of how many other selects are also mounted.
 */
function openListboxFor(select: HTMLElement): HTMLElement {
  const listboxId = select.getAttribute("aria-controls");
  if (!listboxId) {
    throw new Error("Select is missing aria-controls - is it open?");
  }
  const listbox = document.getElementById(listboxId);
  if (!listbox) {
    throw new Error(`No element found for listbox id ${listboxId}`);
  }
  return listbox;
}

// Mantine's Select dropdown is a floating-ui Popover; in jsdom (no real layout engine) floating-ui's
// `hide` middleware always concludes the reference is clipped and sets the panel to `display: none`
// even while open (see frontend/src/test/setup.ts) — its content (role="option") is still correctly
// in the DOM, so option queries below pass `hidden: true` to look past that cosmetic state.
describe("MappingStep", () => {
  it("pre-fills each column's target dropdown from the backend suggestion and marks the comment column as sensitive", async () => {
    mockColumnsAndFields();

    renderWithProviders(
      <MappingStep planId={PLAN_ID} sessionId={SESSION_ID} onNext={() => {}} onExpired={() => {}} />,
    );

    const firstNameSelect = await screen.findByRole("textbox", { name: "Mappning för kolumn Förnamn" });
    expect(firstNameSelect).toHaveValue(sv.importWizard.mapping.targets.firstName);

    const commentSelect = screen.getByRole("textbox", { name: "Mappning för kolumn Kommentar" });
    expect(commentSelect).toHaveValue(sv.importWizard.mapping.targets.comment);
    expect(screen.getByText(sv.importWizard.mapping.sensitiveBadge)).toBeInTheDocument();

    // No suggestion at all defaults to "Ignorera".
    const unknownSelect = screen.getByRole("textbox", { name: "Mappning för kolumn Mystisk kolumn" });
    expect(unknownSelect).toHaveValue(sv.importWizard.mapping.ignoreOption);
  });

  it("lets the user change a column's target and sends the updated mapping on 'Nästa'", async () => {
    mockColumnsAndFields();

    let receivedMappings: ImportColumnMapping[] | null = null;
    server.use(
      http.put(`/api/plans/${PLAN_ID}/import/sessions/${SESSION_ID}/mapping`, async ({ request }) => {
        const body = (await request.json()) as { sheet: string; mappings: ImportColumnMapping[] };
        receivedMappings = body.mappings;
        return HttpResponse.json(body);
      }),
    );

    const user = userEvent.setup();
    let nextCalled = false;
    renderWithProviders(
      <MappingStep
        planId={PLAN_ID}
        sessionId={SESSION_ID}
        onNext={() => {
          nextCalled = true;
        }}
        onExpired={() => {}}
      />,
    );

    const unknownSelect = await screen.findByRole("textbox", { name: "Mappning för kolumn Mystisk kolumn" });
    await user.click(unknownSelect);
    const listbox = openListboxFor(unknownSelect);
    await user.click(within(listbox).getByRole("option", { name: sv.importWizard.mapping.targets.email, hidden: true }));

    expect(unknownSelect).toHaveValue(sv.importWizard.mapping.targets.email);

    await user.click(screen.getByRole("button", { name: sv.importWizard.mapping.nextButton }));

    await waitFor(() => expect(nextCalled).toBe(true));
    expect(receivedMappings).toEqual([
      { columnIndex: 0, target: "firstName" },
      { columnIndex: 1, target: "comment" },
      { columnIndex: 2, target: "email" },
    ]);
  });

  it("creating a field via 'Skapa nytt fält…' auto-maps the originating column to the new customField target", async () => {
    // Stateful field-definitions mock: empty until the POST creates the field, so the
    // useCreateFieldDefinition onSuccess invalidation refetches a list containing it - exactly
    // what makes the new customField:<key> option (and thus the Select's display value) exist.
    const createdFields: object[] = [];
    server.use(
      http.get(`/api/plans/${PLAN_ID}/import/sessions/${SESSION_ID}/columns`, () => HttpResponse.json(COLUMNS)),
      http.get(`/api/plans/${PLAN_ID}/field-definitions`, () => HttpResponse.json(createdFields)),
      http.post(`/api/plans/${PLAN_ID}/field-definitions`, async ({ request }) => {
        const body = (await request.json()) as { key: string; label: string; fieldType: string };
        const field = {
          id: "field-1",
          activityPlanId: PLAN_ID,
          key: body.key,
          label: body.label,
          fieldType: body.fieldType,
          isStandard: false,
          storageKind: "CUSTOM",
          affectsOptimization: false,
          constraintType: "NONE",
        };
        createdFields.push(field);
        return HttpResponse.json(field, { status: 201 });
      }),
    );

    const user = userEvent.setup();
    renderWithProviders(
      <MappingStep planId={PLAN_ID} sessionId={SESSION_ID} onNext={() => {}} onExpired={() => {}} />,
    );

    const unknownSelect = await screen.findByRole("textbox", { name: "Mappning för kolumn Mystisk kolumn" });
    await user.click(unknownSelect);
    const listbox = openListboxFor(unknownSelect);
    await user.click(
      within(listbox).getByRole("option", { name: sv.importWizard.mapping.createFieldOption, hidden: true }),
    );

    await user.type(
      await screen.findByRole("textbox", { name: sv.importWizard.newFieldModal.nameLabel }),
      "Vill spela med",
    );
    await user.click(screen.getByRole("button", { name: sv.importWizard.newFieldModal.submit }));

    // The originating column is now mapped to the created field (Select displays its label).
    await waitFor(() => expect(unknownSelect).toHaveValue("Vill spela med"));

    // And "Nästa" sends customField:<generated key> for that column.
    let receivedMappings: ImportColumnMapping[] | null = null;
    server.use(
      http.put(`/api/plans/${PLAN_ID}/import/sessions/${SESSION_ID}/mapping`, async ({ request }) => {
        const body = (await request.json()) as { sheet: string; mappings: ImportColumnMapping[] };
        receivedMappings = body.mappings;
        return HttpResponse.json(body);
      }),
    );
    await user.click(screen.getByRole("button", { name: sv.importWizard.mapping.nextButton }));
    await waitFor(() =>
      expect(receivedMappings).toEqual([
        { columnIndex: 0, target: "firstName" },
        { columnIndex: 1, target: "comment" },
        { columnIndex: 2, target: "customField:villSpelaMed" },
      ]),
    );
  });
});
