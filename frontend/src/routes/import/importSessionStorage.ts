import type { ImportSheetSummary } from "../../api/import";

/**
 * Best-effort sessionStorage cache of a session's sheet list (name/rowCount/suggested-template),
 * so the "Välj blad" step survives an accidental page reload. The backend has no endpoint to
 * re-list a session's sheets after creation (`POST .../sessions` is the only place that shape is
 * returned — see backend/docs/m3-notes.md), so this is a client-side convenience cache, not the
 * source of truth: every other wizard step (mapping/validate/decisions/commit) re-fetches fresh
 * from the backend by sessionId alone, per the milestone brief's "client stores only sessionId +
 * current step" rule. If this cache is missing (storage cleared, different tab), the Sheet step
 * shows a "börja om" prompt rather than guessing at sheet names.
 */
function storageKey(sessionId: string): string {
  return `gp-import-sheets-${sessionId}`;
}

export function cacheImportSheets(sessionId: string, sheets: ImportSheetSummary[]): void {
  try {
    sessionStorage.setItem(storageKey(sessionId), JSON.stringify(sheets));
  } catch {
    // Best-effort cache only — a full/unavailable sessionStorage just means no refresh-safety here.
  }
}

export function readCachedImportSheets(sessionId: string): ImportSheetSummary[] {
  try {
    const raw = sessionStorage.getItem(storageKey(sessionId));
    if (!raw) {
      return [];
    }
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as ImportSheetSummary[]) : [];
  } catch {
    return [];
  }
}
