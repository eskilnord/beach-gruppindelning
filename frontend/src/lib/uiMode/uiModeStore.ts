import { create } from "zustand";
import { resolveInitialUiMode, type UiMode } from "./uiMode";
import { writeUiModeToStorage } from "./uiModeStorage";

interface UiModeStoreState {
  mode: UiMode;
  /** True once `setMode` has been called during THIS session (i.e. the user - or UiModeSync's own
   *  reconcile - actually changed the mode after boot). Used by UiModeSync (B3) to decide the
   *  backend-reconcile precedence: the backend is durable truth for the mirror, but must never
   *  silently override a choice the user actively made this session. Reset only on reload (a fresh
   *  module load), never automatically otherwise - see {@link resetUserChangedThisSessionForTests}
   *  for the test-only escape hatch. */
  userChangedThisSession: boolean;
  /** Monotonically increasing counter, bumped by every `setMode()` call. useUiMode.ts's `setMode`
   *  captures the token returned from a call and checks it in the corresponding PUT's onSuccess/
   *  onError - if a newer `setMode()` has since been called (a rapid second toggle), the token no
   *  longer matches the store's current one and that stale response's cache write / failure
   *  notification is suppressed (B4: PUT race). */
  requestToken: number;
  setMode: (mode: UiMode) => number;
}

/**
 * Global UI-mode store (zustand - already a pinned dependency, CLAUDE.md; same shape as
 * components/tutorial/tutorialStore.ts). Eagerly initialized at module load from
 * `resolveInitialUiMode(window.location.search)` so the very first render already has the right
 * mode - no flash of the wrong information architecture while an async read would still be
 * pending.
 *
 * `setMode` here only updates the store + the localStorage mirror (synchronous, local-only side
 * effects). Persisting to the backend (`PUT /api/app-settings`) and showing a failure notification
 * are useUiMode.ts's job, not this store's - keeps this module dependency-free of react-query/
 * Mantine notifications so it stays trivially usable from anywhere (including outside React, if
 * ever needed).
 */
export const useUiModeStore = create<UiModeStoreState>((set, get) => ({
  // typeof window !== "undefined" guard: this module is eagerly evaluated at import time, before
  // any component mounts - a future SSR/test harness that imports it without a DOM must not throw.
  mode: resolveInitialUiMode(typeof window !== "undefined" ? window.location.search : ""),
  userChangedThisSession: false,
  requestToken: 0,
  setMode: (mode) => {
    writeUiModeToStorage(mode);
    const token = get().requestToken + 1;
    set({ mode, userChangedThisSession: true, requestToken: token });
    return token;
  },
}));

/**
 * Applies the backend's durable value directly, bypassing `setMode` - used ONLY by UiModeSync's
 * one-time reconcile (B3). Deliberately does NOT set `userChangedThisSession`: that flag means "the
 * user (or an explicit user-facing action) changed the mode this session", and a background
 * reconcile pulling in the backend's own value is neither.
 */
export function applyReconciledUiMode(mode: UiMode): void {
  writeUiModeToStorage(mode);
  useUiModeStore.setState({ mode });
}

/** Test-only escape hatch (mirrors resetTutorialSeenForTests / resetBackendInfoCacheForTests):
 *  forces the store's mode directly, bypassing localStorage entirely, and resets
 *  `userChangedThisSession` back to false so a test that called setMode()/toggled the switch never
 *  leaks that flag into the next test. Used by src/test/renderWithProviders.tsx (per-test override)
 *  and src/test/setup.ts (before/afterEach reset to the ADVANCED test default) so specs never depend
 *  on whatever `resolveInitialUiMode()` happened to resolve to at module-load time in jsdom. */
export function setUiModeForTests(mode: UiMode): void {
  useUiModeStore.setState({ mode, userChangedThisSession: false });
}

/** Test-only: resets just `userChangedThisSession` to false without touching `mode` - for specs
 *  that need to simulate "a fresh session that hasn't changed mode yet" after already setting a
 *  mode via setUiModeForTests. */
export function resetUserChangedThisSessionForTests(): void {
  useUiModeStore.setState({ userChangedThisSession: false });
}
