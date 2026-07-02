import { describe, expect, it } from "vitest";
import {
  applyAvailabilityEntries,
  AVAILABILITY_UNKNOWN,
  availabilityDraftsEqual,
  initialAvailabilityDraft,
  toAvailabilityEntries,
  type AvailabilityDraft,
} from "./availability";

describe("initialAvailabilityDraft", () => {
  it("defaults every given time slot id to UNKNOWN (Okänd)", () => {
    expect(initialAvailabilityDraft(["slot-1", "slot-2"])).toEqual({
      "slot-1": AVAILABILITY_UNKNOWN,
      "slot-2": AVAILABILITY_UNKNOWN,
    });
  });

  it("returns an empty draft for no slots", () => {
    expect(initialAvailabilityDraft([])).toEqual({});
  });
});

describe("applyAvailabilityEntries", () => {
  it("overlays server entries onto the UNKNOWN base, leaving unlisted slots UNKNOWN", () => {
    const base = initialAvailabilityDraft(["slot-1", "slot-2", "slot-3"]);
    const draft = applyAvailabilityEntries(base, [
      { timeSlotId: "slot-1", kind: "AVAILABLE" },
      { timeSlotId: "slot-3", kind: "PREFERRED" },
    ]);
    expect(draft).toEqual({
      "slot-1": "AVAILABLE",
      "slot-2": AVAILABILITY_UNKNOWN,
      "slot-3": "PREFERRED",
    });
  });
});

describe("toAvailabilityEntries", () => {
  it("maps AVAILABLE/UNAVAILABLE/PREFERRED rows to the PUT payload", () => {
    const draft: AvailabilityDraft = {
      "slot-1": "AVAILABLE",
      "slot-2": "UNAVAILABLE",
      "slot-3": "PREFERRED",
    };
    expect(toAvailabilityEntries(draft)).toEqual([
      { timeSlotId: "slot-1", kind: "AVAILABLE" },
      { timeSlotId: "slot-2", kind: "UNAVAILABLE" },
      { timeSlotId: "slot-3", kind: "PREFERRED" },
    ]);
  });

  it("omits UNKNOWN (Okänd) rows from the payload entirely - not sent as UNAVAILABLE", () => {
    const draft: AvailabilityDraft = {
      "slot-1": "AVAILABLE",
      "slot-2": AVAILABILITY_UNKNOWN,
    };
    const entries = toAvailabilityEntries(draft);
    expect(entries).toEqual([{ timeSlotId: "slot-1", kind: "AVAILABLE" }]);
    expect(entries.some((entry) => entry.timeSlotId === "slot-2")).toBe(false);
  });

  it("returns an empty array when every row is UNKNOWN", () => {
    const draft = initialAvailabilityDraft(["slot-1", "slot-2"]);
    expect(toAvailabilityEntries(draft)).toEqual([]);
  });
});

describe("availabilityDraftsEqual", () => {
  it("treats two drafts with the same set entries (regardless of key order) as equal", () => {
    const a: AvailabilityDraft = { "slot-1": "AVAILABLE", "slot-2": "PREFERRED" };
    const b: AvailabilityDraft = { "slot-2": "PREFERRED", "slot-1": "AVAILABLE" };
    expect(availabilityDraftsEqual(a, b)).toBe(true);
  });

  it("treats UNKNOWN rows as equivalent to absent rows", () => {
    const a: AvailabilityDraft = { "slot-1": "AVAILABLE", "slot-2": AVAILABILITY_UNKNOWN };
    const b: AvailabilityDraft = { "slot-1": "AVAILABLE" };
    expect(availabilityDraftsEqual(a, b)).toBe(true);
  });

  it("detects a changed kind as not equal", () => {
    const a: AvailabilityDraft = { "slot-1": "AVAILABLE" };
    const b: AvailabilityDraft = { "slot-1": "PREFERRED" };
    expect(availabilityDraftsEqual(a, b)).toBe(false);
  });
});
