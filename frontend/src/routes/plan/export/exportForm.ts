import type { ExportFormat, ExportLayout } from "../../../api/types";

/**
 * Pure decision logic for the Export tab's form (spec §20), split out of ExportPanel.tsx for direct
 * unit testing (same pattern as optimize/scoreFormat.ts, capacity/riskBanner.ts).
 *
 * `layout=grouped` (kansliets arbetsblad) only makes sense for xlsx - a csv has no sheets/blocks to
 * group into, and the backend 400s the combination (`ExportService#export`: "layout=grouped only
 * supports format=xlsx - use layout=flat for csv"). Rather than let the user submit and hit that 400,
 * the UI disables the "Grupperad" radio option whenever format=csv is selected.
 */
export function isGroupedLayoutDisabled(format: ExportFormat): boolean {
  return format === "csv";
}

/** If the user is on "Grupperad" and then switches format to csv, the previously-valid layout
 *  becomes invalid - silently falls back to "Platt tabell" rather than leaving the form in a state
 *  that would 400 on submit. A no-op in every other case. */
export function normalizeLayoutForFormat(format: ExportFormat, layout: ExportLayout): ExportLayout {
  return isGroupedLayoutDisabled(format) && layout === "grouped" ? "flat" : layout;
}

/** "Inkludera kommentarer i export" (spec §20.3) defaults OFF; the sensitive-data warning alert only
 *  needs to show once the user has actively opted in. */
export function showCommentsWarning(includeComments: boolean): boolean {
  return includeComments;
}
