import type { AvailabilityEntry, AvailabilityKind } from "../../../api/types";

/** Sentinel draft value for "no opinion yet" - a time slot with no row in coach_time_slot (spec
 *  §13.1: only "tillgängliga tider"/"otillgängliga tider" are asked for, so anything not listed is
 *  simply not yet known, not UNAVAILABLE). Kept as a plain string (not null/undefined) so the
 *  Tränarvy availability matrix's SegmentedControl - which needs a plain string `value` - can bind
 *  directly to the draft record without a null-to-string translation layer at the UI edge. */
export const AVAILABILITY_UNKNOWN = "UNKNOWN" as const;

export type AvailabilityDraftValue = AvailabilityKind | typeof AVAILABILITY_UNKNOWN;

/** One entry per plan time slot, keyed by timeSlotId. */
export type AvailabilityDraft = Record<string, AvailabilityDraftValue>;

/** Every plan time slot defaults to "Okänd" until either the server or the user says otherwise. */
export function initialAvailabilityDraft(timeSlotIds: string[]): AvailabilityDraft {
  const draft: AvailabilityDraft = {};
  for (const id of timeSlotIds) {
    draft[id] = AVAILABILITY_UNKNOWN;
  }
  return draft;
}

/** Overlays the server's stored entries (GET .../availability) onto a base draft (normally
 *  `initialAvailabilityDraft`'s output) - any slot with a stored row gets that row's kind, every
 *  other slot stays "Okänd". Entries for a timeSlotId not in `timeSlotIds` are still honored (a
 *  slot could in principle have been deleted after the availability was recorded); they're simply
 *  not editable in a matrix that no longer lists that slot. */
export function applyAvailabilityEntries(base: AvailabilityDraft, entries: AvailabilityEntry[]): AvailabilityDraft {
  const draft: AvailabilityDraft = { ...base };
  for (const entry of entries) {
    if (entry.timeSlotId) {
      draft[entry.timeSlotId] = entry.kind as AvailabilityKind;
    }
  }
  return draft;
}

/**
 * Maps a draft to the PUT request body (spec §13.1 tri-state matrix -> CoachController's full-replace
 * PUT): rows still at "Okänd" are omitted entirely, since a PUT with no row for a slot is exactly
 * what leaves it neutral/unknown server-side (as opposed to sending it as, say, UNAVAILABLE).
 */
export function toAvailabilityEntries(draft: AvailabilityDraft): AvailabilityEntry[] {
  return Object.entries(draft)
    .filter((entry): entry is [string, AvailabilityKind] => entry[1] !== AVAILABILITY_UNKNOWN)
    .map(([timeSlotId, kind]) => ({ timeSlotId, kind }));
}

/** Stable-sorted comparison (independent of key insertion order) - used to detect whether the draft
 *  has actually changed from what the server last returned, to enable/disable the Spara button. */
export function availabilityDraftsEqual(a: AvailabilityDraft, b: AvailabilityDraft): boolean {
  const normalize = (draft: AvailabilityDraft) =>
    JSON.stringify(
      Object.entries(draft)
        .filter(([, kind]) => kind !== AVAILABILITY_UNKNOWN)
        .sort(([left], [right]) => left.localeCompare(right)),
    );
  return normalize(a) === normalize(b);
}
