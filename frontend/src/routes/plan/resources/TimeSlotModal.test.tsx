import { describe, expect, it } from "vitest";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "../../../test/server";
import { renderWithProviders } from "../../../test/renderWithProviders";
import { sv } from "../../../i18n/sv";
import { TimeSlotModal } from "./TimeSlotModal";
import type { TimeSlot } from "../../../api/types";

const PLAN_ID = "plan-1";

const EXISTING_SLOT: TimeSlot = {
  id: "ts-1",
  activityPlanId: PLAN_ID,
  dayOfWeek: "THURSDAY",
  startTime: "18:00",
  endTime: "19:30",
  durationMinutes: 90,
  label: "Torsdag 18.00–19.30",
};

// Mantine's Combobox keeps its option list mounted (`keepMounted: true` by default); floating-ui's
// `hide` middleware always concludes the reference is clipped in jsdom (no real layout engine), so
// the listbox stays `display: none` even while "open" - its role="option" content is still in the
// DOM, hence `hidden: true` below (same pattern as NewFieldModal.test.tsx/MappingStep.test.tsx).
async function fillDay(day: string) {
  const user = userEvent.setup();
  const select = screen.getByRole("textbox", { name: sv.resources.slotModal.dayLabel });
  await user.click(select);
  await waitFor(() => expect(select).toHaveAttribute("aria-controls"));
  const listbox = document.getElementById(select.getAttribute("aria-controls")!);
  if (!listbox) {
    throw new Error("Select listbox element not found");
  }
  await user.click(within(listbox).getByRole("option", { name: day, hidden: true }));
}

/** v0.6.0 audit-fix B12 ("Gunilla" persona): "Label" was raw English jargon - now names the field
 *  and marks it optional. */
describe("TimeSlotModal B12: Etikett field label", () => {
  it('renders the Label TextInput with the "Etikett (valfritt)" accessible name', () => {
    renderWithProviders(<TimeSlotModal planId={PLAN_ID} opened slot={null} onClose={() => {}} />);
    expect(screen.getByLabelText("Etikett (valfritt)")).toBeInTheDocument();
  });
});

describe("TimeSlotModal B12: Antal banor is CREATE-only", () => {
  it("renders the Antal banor NumberInput when creating a new slot", () => {
    renderWithProviders(<TimeSlotModal planId={PLAN_ID} opened slot={null} onClose={() => {}} />);
    expect(screen.getByLabelText(sv.resources.slotModal.courtsLabel)).toBeInTheDocument();
  });

  it("does NOT render the Antal banor NumberInput when editing an existing slot", () => {
    renderWithProviders(<TimeSlotModal planId={PLAN_ID} opened slot={EXISTING_SLOT} onClose={() => {}} />);
    expect(screen.queryByLabelText(sv.resources.slotModal.courtsLabel)).not.toBeInTheDocument();
  });
});

describe("TimeSlotModal B12: client-side validation", () => {
  it("shows Swedish field errors for empty required fields on submit", async () => {
    renderWithProviders(<TimeSlotModal planId={PLAN_ID} opened slot={null} onClose={() => {}} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: sv.resources.slotModal.submit }));

    expect(await screen.findByText(sv.resources.slotModal.dayRequired)).toBeInTheDocument();
    expect(screen.getByText(sv.resources.slotModal.startTimeRequired)).toBeInTheDocument();
    expect(screen.getByText(sv.resources.slotModal.endTimeRequired)).toBeInTheDocument();
  });

  it("shows a Swedish error when start time is not before end time", async () => {
    renderWithProviders(<TimeSlotModal planId={PLAN_ID} opened slot={null} onClose={() => {}} />);
    const user = userEvent.setup();

    await fillDay(sv.days.THURSDAY);
    fireEvent.change(screen.getByLabelText(sv.resources.slotModal.startTimeLabel, { exact: false }), { target: { value: "19:00" } });
    fireEvent.change(screen.getByLabelText(sv.resources.slotModal.endTimeLabel, { exact: false }), { target: { value: "18:00" } });

    await user.click(screen.getByRole("button", { name: sv.resources.slotModal.submit }));

    expect(await screen.findByText(sv.resources.slotModal.startBeforeEndError)).toBeInTheDocument();
  });
});

describe("TimeSlotModal B12: Antal banor on create fires a second setCourts call", () => {
  it("PUTs the courts count after a successful create when Antal banor was filled in", async () => {
    let courtsBody: unknown = null;
    server.use(
      http.post(`/api/plans/${PLAN_ID}/time-slots`, () =>
        HttpResponse.json({ ...EXISTING_SLOT, id: "ts-new" }, { status: 201 }),
      ),
      http.put(`/api/plans/${PLAN_ID}/time-slots/ts-new/courts`, async ({ request }) => {
        courtsBody = await request.json();
        return HttpResponse.json([]);
      }),
    );
    const onClose = () => {};
    renderWithProviders(<TimeSlotModal planId={PLAN_ID} opened slot={null} onClose={onClose} />);
    const user = userEvent.setup();

    await fillDay(sv.days.THURSDAY);
    fireEvent.change(screen.getByLabelText(sv.resources.slotModal.startTimeLabel, { exact: false }), { target: { value: "18:00" } });
    fireEvent.change(screen.getByLabelText(sv.resources.slotModal.endTimeLabel, { exact: false }), { target: { value: "19:00" } });
    await user.type(screen.getByLabelText(sv.resources.slotModal.courtsLabel), "3");

    await user.click(screen.getByRole("button", { name: sv.resources.slotModal.submit }));

    await waitFor(() => expect(courtsBody).toEqual({ count: 3 }));
  });

  it("does not call setCourts when Antal banor was left blank", async () => {
    let courtsCalled = false;
    server.use(
      http.post(`/api/plans/${PLAN_ID}/time-slots`, () =>
        HttpResponse.json({ ...EXISTING_SLOT, id: "ts-new-2" }, { status: 201 }),
      ),
      http.put(`/api/plans/${PLAN_ID}/time-slots/ts-new-2/courts`, () => {
        courtsCalled = true;
        return HttpResponse.json([]);
      }),
    );
    renderWithProviders(<TimeSlotModal planId={PLAN_ID} opened slot={null} onClose={() => {}} />);
    const user = userEvent.setup();

    await fillDay(sv.days.FRIDAY);
    fireEvent.change(screen.getByLabelText(sv.resources.slotModal.startTimeLabel, { exact: false }), { target: { value: "18:00" } });
    fireEvent.change(screen.getByLabelText(sv.resources.slotModal.endTimeLabel, { exact: false }), { target: { value: "19:00" } });

    await user.click(screen.getByRole("button", { name: sv.resources.slotModal.submit }));

    // Give any (incorrect) fire-and-forget request a tick to have shown up.
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(courtsCalled).toBe(false);
  });
});

/** v0.6.0 audit-fix B12: ApiError messages (already Swedish, backend's uniform error shape) surface
 *  via the local userErrorText helper (errorText.ts). */
describe("TimeSlotModal B12: Swedish-first error surfacing", () => {
  it("shows the backend's Swedish ApiError message on a failed create", async () => {
    server.use(
      http.post(`/api/plans/${PLAN_ID}/time-slots`, () =>
        HttpResponse.json({ error: "En tid med samma dag och tid finns redan." }, { status: 409 }),
      ),
    );
    renderWithProviders(<TimeSlotModal planId={PLAN_ID} opened slot={null} onClose={() => {}} />);
    const user = userEvent.setup();

    await fillDay(sv.days.THURSDAY);
    fireEvent.change(screen.getByLabelText(sv.resources.slotModal.startTimeLabel, { exact: false }), { target: { value: "18:00" } });
    fireEvent.change(screen.getByLabelText(sv.resources.slotModal.endTimeLabel, { exact: false }), { target: { value: "19:00" } });

    await user.click(screen.getByRole("button", { name: sv.resources.slotModal.submit }));

    expect(await screen.findByText("En tid med samma dag och tid finns redan.")).toBeInTheDocument();
  });
});
