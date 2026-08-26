import type { ExportFormat, ExportLayout } from "../../../api/types";
import type { SaveFileResult } from "../../../lib/platform";

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

/** The notification color+message to show for a {@link SaveFileResult} - v0.6.0 audit batch D (D7):
 *  centralizes the Tauri-saved vs. browser-downloaded vs. user-cancelled wording split so
 *  ExportPanel.tsx's two export buttons and SimpleSaveExportCard.tsx's one don't each reimplement it
 *  slightly differently (and so a cancelled Tauri save dialog - previously silent, `saveFile` just
 *  returned `false` with no notification at all - always gets an explicit "avbröts" note now). */
export interface ExportResultNotification {
  color: "green" | "gray";
  message: string;
}

export interface ExportResultTexts {
  /** Shown for the browser `<a download>` branch (`SaveFileResult.isTauriSave === false`). */
  downloaded: string;
  /** Shown for a successful Tauri save whose written filename isn't available for some reason. */
  savedGeneric: string;
  /** Shown for a successful Tauri save whose written filename IS available. */
  savedWithFilename: (filename: string) => string;
  /** Shown when the admin cancelled the Tauri save dialog (`SaveFileResult.saved === false`). */
  cancelled: string;
}

export function describeExportResult(result: SaveFileResult, texts: ExportResultTexts): ExportResultNotification {
  if (!result.saved) {
    return { color: "gray", message: texts.cancelled };
  }
  if (result.isTauriSave) {
    return { color: "green", message: result.filename ? texts.savedWithFilename(result.filename) : texts.savedGeneric };
  }
  return { color: "green", message: texts.downloaded };
}
