import type { SavedPlanStatus } from "../../../api/types";

/**
 * Mirrors backend/.../savedplan/SavedPlanLifecycle.LEGAL_TRANSITIONS exactly (spec §14.2/§14.3), so
 * the Sparade planer table only ever offers status buttons the backend will accept - anything not
 * listed here 409s server-side (`SavedPlanLifecycleTest` enumerates the same illegal set). `archived`
 * is a terminal sink reachable from every non-draft status; `draft` itself is currently unreachable
 * from this frontend (POST always creates "saved" directly, see `api/savedPlans.ts`) but is kept here
 * for completeness/robustness against a future draft-producing endpoint.
 */
const LEGAL_TRANSITIONS: Record<SavedPlanStatus, SavedPlanStatus[]> = {
  draft: ["saved"],
  saved: ["locked", "archived"],
  locked: ["published", "archived"],
  published: ["archived"],
  archived: [],
};

/** Every legal next status for the given current status - empty for `archived` (terminal) or an
 *  unrecognized/unknown status string. */
export function legalNextStatuses(status: string): SavedPlanStatus[] {
  return LEGAL_TRANSITIONS[status as SavedPlanStatus] ?? [];
}

/** Only draft/saved plans are deletable (spec §14, backend `SavedPlanLifecycle.DELETABLE_STATUSES`) -
 *  locked/published/archived plans must be archived instead (409 on DELETE otherwise). */
export function canDeleteSavedPlan(status: string): boolean {
  return status === "draft" || status === "saved";
}

export interface StatusAction {
  targetStatus: SavedPlanStatus;
  color: string;
}

/** Mantine badge/button color per target status - used both for the status action buttons and the
 *  current-status badge, so a plan's badge color always matches the color of the button that would
 *  have produced it. */
const STATUS_COLORS: Record<SavedPlanStatus, string> = {
  draft: "gray",
  saved: "teal",
  locked: "blue",
  published: "green",
  archived: "gray",
};

export function statusColor(status: string): string {
  return STATUS_COLORS[status as SavedPlanStatus] ?? "gray";
}

/** The ordered list of status actions to render as buttons for a saved plan currently in `status` -
 *  one entry per legal next status (spec: "render only legal next steps"). */
export function statusActions(status: string): StatusAction[] {
  return legalNextStatuses(status).map((targetStatus) => ({ targetStatus, color: STATUS_COLORS[targetStatus] }));
}
