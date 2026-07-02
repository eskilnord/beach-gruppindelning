import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { NewFieldModal } from "./NewFieldModal";
import { sv } from "../../../i18n/sv";

const PLAN_ID = "plan-1";

// Mantine's Combobox keeps every dropdown's option list mounted (`keepMounted: true` by default);
// floating-ui's `hide` middleware always concludes the reference is clipped in jsdom (no real
// layout engine, see frontend/src/test/setup.ts) so the listbox stays `display: none` even while
// "open" - its role="option" content is still in the DOM, hence `hidden: true` below (same pattern
// as MappingStep.test.tsx). Opening is asynchronous (a render pass wires the listbox id onto the
// input), so this opens the select (if not already open) and waits for `aria-controls`.
async function openListbox(user: ReturnType<typeof userEvent.setup>, select: HTMLElement): Promise<HTMLElement> {
  if (!select.getAttribute("aria-controls")) {
    await user.click(select);
  }
  await waitFor(() => expect(select).toHaveAttribute("aria-controls"));
  const listbox = document.getElementById(select.getAttribute("aria-controls")!);
  if (!listbox) {
    throw new Error("Select listbox element not found");
  }
  return listbox;
}

async function selectOption(user: ReturnType<typeof userEvent.setup>, selectName: string, optionName: string) {
  const select = screen.getByRole("textbox", { name: selectName });
  const listbox = await openListbox(user, select);
  await user.click(within(listbox).getByRole("option", { name: optionName, hidden: true }));
}

async function optionTexts(user: ReturnType<typeof userEvent.setup>, selectName: string): Promise<string[]> {
  const select = screen.getByRole("textbox", { name: selectName });
  const listbox = await openListbox(user, select);
  return within(listbox)
    .getAllByRole("option", { hidden: true })
    .map((option) => option.textContent ?? "");
}

describe("NewFieldModal", () => {
  it("disables 'Påverkar optimering' for a field type with no compatible constraint (text)", () => {
    renderWithProviders(<NewFieldModal planId={PLAN_ID} opened onClose={() => {}} />);

    expect(screen.getByRole("switch", { name: sv.fieldBuilder.newFieldModal.affectsOptimizationLabel })).toBeDisabled();
    expect(screen.getByText(sv.fieldBuilder.newFieldModal.noCompatibleConstraint)).toBeInTheDocument();
  });

  it("filters the constraint select to personRelation's compatible families (Samma grupp / Olika grupper only)", async () => {
    const user = userEvent.setup();
    renderWithProviders(<NewFieldModal planId={PLAN_ID} opened onClose={() => {}} />);

    await selectOption(user, sv.fieldBuilder.newFieldModal.typeLabel, sv.fieldTypes.personRelation);
    await user.click(screen.getByRole("switch", { name: sv.fieldBuilder.newFieldModal.affectsOptimizationLabel }));

    const options = await optionTexts(user, sv.fieldBuilder.newFieldModal.constraintLabel);
    expect(options).toEqual([sv.constraintFamilies.SAME_GROUP, sv.constraintFamilies.DIFFERENT_GROUP]);
  });

  it("filters the constraint select to coachRelation's compatible families when the type changes", async () => {
    const user = userEvent.setup();
    renderWithProviders(<NewFieldModal planId={PLAN_ID} opened onClose={() => {}} />);

    await selectOption(user, sv.fieldBuilder.newFieldModal.typeLabel, sv.fieldTypes.coachRelation);
    await user.click(screen.getByRole("switch", { name: sv.fieldBuilder.newFieldModal.affectsOptimizationLabel }));

    const options = await optionTexts(user, sv.fieldBuilder.newFieldModal.constraintLabel);
    expect(options).toEqual([sv.constraintFamilies.COACH_PREFERENCE, sv.constraintFamilies.COACH_FORBIDDEN]);
    expect(options).not.toContain(sv.constraintFamilies.SAME_GROUP);
  });

  it("filters the constraint select to number's compatible families (Nivåbalans / Prioritet)", async () => {
    const user = userEvent.setup();
    renderWithProviders(<NewFieldModal planId={PLAN_ID} opened onClose={() => {}} />);

    await selectOption(user, sv.fieldBuilder.newFieldModal.typeLabel, sv.fieldTypes.number);
    await user.click(screen.getByRole("switch", { name: sv.fieldBuilder.newFieldModal.affectsOptimizationLabel }));

    const options = await optionTexts(user, sv.fieldBuilder.newFieldModal.constraintLabel);
    expect(options).toEqual([sv.constraintFamilies.LEVEL_BALANCE_INPUT, sv.constraintFamilies.PRIORITY]);
  });

  it("only shows the weight input for SOFT, not HARD (personRelation / Vill spela med scenario)", async () => {
    const user = userEvent.setup();
    renderWithProviders(<NewFieldModal planId={PLAN_ID} opened onClose={() => {}} />);

    await selectOption(user, sv.fieldBuilder.newFieldModal.typeLabel, sv.fieldTypes.personRelation);
    await user.click(screen.getByRole("switch", { name: sv.fieldBuilder.newFieldModal.affectsOptimizationLabel }));

    // Defaults to SOFT -> weight visible.
    expect(screen.getByLabelText(sv.fieldBuilder.newFieldModal.weightLabel, { exact: false })).toBeInTheDocument();

    // Switch to HARD -> weight input disappears entirely (no weight for hard constraints).
    await user.click(screen.getByRole("radio", { name: sv.hardOrSoft.HARD }));
    expect(screen.queryByLabelText(sv.fieldBuilder.newFieldModal.weightLabel, { exact: false })).not.toBeInTheDocument();

    // Back to SOFT -> weight input reappears.
    await user.click(screen.getByRole("radio", { name: sv.hardOrSoft.SOFT }));
    expect(screen.getByLabelText(sv.fieldBuilder.newFieldModal.weightLabel, { exact: false })).toBeInTheDocument();
  });

  it("shows a live camelCase key preview derived from the label (Vill spela med -> villSpelaMed)", async () => {
    const user = userEvent.setup();
    renderWithProviders(<NewFieldModal planId={PLAN_ID} opened onClose={() => {}} />);

    await user.type(screen.getByRole("textbox", { name: sv.fieldBuilder.newFieldModal.labelLabel }), "Vill spela med");

    expect(screen.getByText(sv.fieldBuilder.newFieldModal.keyPreview("villSpelaMed"))).toBeInTheDocument();
  });
});
