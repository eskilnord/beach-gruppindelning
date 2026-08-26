import { UI_MODE_STORAGE_KEY, type UiMode } from "./uiMode";

function isUiMode(value: string | null): value is UiMode {
  return value === "SIMPLE" || value === "ADVANCED";
}

/**
 * Fail-safe localStorage mirror for the resolved UI mode - mirrors the
 * components/tutorial/tutorialSeenStore.ts pattern: a locked-down browser profile or a
 * private-browsing quirk must never crash the app or throw over reading/writing this. Returns
 * `null` (not a default) on a missing key or an unreadable/corrupt value, so callers (
 * {@link resolveInitialUiMode} in ./uiMode.ts) can apply their own fallback.
 */
export function readUiModeFromStorage(): UiMode | null {
  try {
    const value = window.localStorage.getItem(UI_MODE_STORAGE_KEY);
    return isUiMode(value) ? value : null;
  } catch {
    return null;
  }
}

/** Best-effort write - see {@link readUiModeFromStorage}'s fail-safe rationale above. */
export function writeUiModeToStorage(mode: UiMode): void {
  try {
    window.localStorage.setItem(UI_MODE_STORAGE_KEY, mode);
  } catch {
    // ignore - the in-memory store (uiModeStore.ts) still has the value for this session.
  }
}
