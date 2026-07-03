import { describe, expect, it, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { CustomFieldEditor } from "./CustomFieldEditor";
import { sv } from "../../../i18n/sv";
import type { FieldValueView, TimeSlot } from "../../../api/types";

// Mirrors the seeded 'cannotTimes' standard field (V2 migration): timeRelation, HARD.
const CANNOT_TIMES_FIELD_VALUE: FieldValueView = {
  fieldDefinitionId: "field-cannotTimes",
  key: "cannotTimes",
  label: "Kan inte tider",
  fieldType: "timeRelation",
};

const SLOT_MON: TimeSlot = {
  id: "slot-mon",
  activityPlanId: "plan-1",
  dayOfWeek: "MONDAY",
  startTime: "18:00",
  endTime: "19:30",
  durationMinutes: 90,
  label: "Måndag 18.00–19.30",
};
const SLOT_TUE: TimeSlot = {
  id: "slot-tue",
  activityPlanId: "plan-1",
  dayOfWeek: "TUESDAY",
  startTime: "18:00",
  endTime: "19:30",
  durationMinutes: 90,
  label: "Tisdag 18.00–19.30",
};

function renderEditor(value: unknown, onChange: (value: unknown) => void, timeSlots: TimeSlot[] = [SLOT_MON, SLOT_TUE]) {
  renderWithProviders(
    <CustomFieldEditor
      fieldValue={CANNOT_TIMES_FIELD_VALUE}
      definition={undefined}
      value={value}
      onChange={onChange}
      participants={[]}
      timeSlots={timeSlots}
      selfId="participant-1"
    />,
  );
}

// Mirrors NewFieldModal.test.tsx's openListbox helper: Mantine's Combobox keeps the option list
// mounted (`keepMounted: true` by default) but `display: none` in jsdom (no real layout engine, see
// frontend/src/test/setup.ts), so options must be queried with `hidden: true`.
async function openListbox(user: ReturnType<typeof userEvent.setup>, input: HTMLElement): Promise<HTMLElement> {
  if (!input.getAttribute("aria-controls")) {
    await user.click(input);
  }
  await waitFor(() => expect(input).toHaveAttribute("aria-controls"));
  const listbox = document.getElementById(input.getAttribute("aria-controls")!);
  if (!listbox) {
    throw new Error("MultiSelect listbox not found");
  }
  return listbox;
}

/** The selected-value pills container - scoping queries to it (rather than `screen`) avoids matching
 *  the SAME label text rendered a second time by the (always-mounted, per Combobox's `keepMounted`)
 *  dropdown option list. */
function pillsList(): HTMLElement {
  const el = document.querySelector(".mantine-MultiSelect-pillsList");
  if (!el) {
    throw new Error("MultiSelect pills list not found");
  }
  return el as HTMLElement;
}

describe("CustomFieldEditor - timeRelation", () => {
  it("renders slot labels, not raw time_slot ids, for stored values", () => {
    renderEditor(["slot-mon"], () => {});

    expect(within(pillsList()).getByText("Måndag 18.00–19.30")).toBeInTheDocument();
    expect(within(pillsList()).queryByText("slot-mon")).not.toBeInTheDocument();
  });

  it("filters out ids that are not valid slot ids in this plan and shows a dimmed note", () => {
    // "legacy-free-text-value" stands in for a pre-M6a free-text value or a shape-incompatible
    // import leftover (background: ImportCommitService used to write the raw cell text here).
    renderEditor(["slot-mon", "legacy-free-text-value"], () => {});

    expect(within(pillsList()).getByText("Måndag 18.00–19.30")).toBeInTheDocument();
    expect(within(pillsList()).queryByText("legacy-free-text-value")).not.toBeInTheDocument();
    expect(screen.getByText(sv.participants.drawer.timeRelationInvalidValuesNote)).toBeInTheDocument();
  });

  it("shows no invalid-values note when every stored value is a valid slot id", () => {
    renderEditor(["slot-mon"], () => {});

    expect(screen.queryByText(sv.participants.drawer.timeRelationInvalidValuesNote)).not.toBeInTheDocument();
  });

  it("emits an array of slot ids when the user picks an option", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderEditor(null, onChange);

    const input = screen.getByRole("textbox", { name: "Kan inte tider" });
    const listbox = await openListbox(user, input);
    await user.click(within(listbox).getByRole("option", { name: "Tisdag 18.00–19.30", hidden: true }));

    expect(onChange).toHaveBeenCalledWith(["slot-tue"]);
  });

  it("emits null once the last selected slot is removed", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderEditor(["slot-mon"], onChange);

    const input = screen.getByRole("textbox", { name: "Kan inte tider" });
    await user.click(input);
    await user.keyboard("{Backspace}"); // MultiSelect removes the last pill on Backspace over an empty search.

    expect(onChange).toHaveBeenCalledWith(null);
  });
});
