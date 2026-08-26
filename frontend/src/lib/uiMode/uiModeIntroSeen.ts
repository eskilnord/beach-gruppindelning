const STORAGE_KEY = "gp.uiMode.introSeen";

/**
 * Whether UiModeIntroBanner.tsx's one-time "the app now has a simpler mode" notice has already been
 * shown on this device - mirrors components/tutorial/tutorialSeenStore.ts's `gp.tutorial.seen`
 * pattern exactly, including the fail-safe default: a missing/unreadable localStorage (a
 * locked-down browser profile, a private-browsing quirk) fails "seen" rather than "unseen" - better
 * to never nag a user we can't reliably stop nagging than to show the banner every single reload.
 */
export function hasSeenUiModeIntro(): boolean {
  try {
    return window.localStorage.getItem(STORAGE_KEY) === "1";
  } catch {
    return true;
  }
}

/** Marks the intro as shown. Called the moment UiModeIntroBanner actually renders it (not on
 *  dismiss/"Behåll avancerat läge") - "shown at most once" means shown once, not acted on once. */
export function markUiModeIntroSeen(): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, "1");
  } catch {
    // Best-effort only - see hasSeenUiModeIntro's fail-safe default above.
  }
}

/** Test-only escape hatch (mirrors resetTutorialSeenForTests). */
export function resetUiModeIntroSeenForTests(): void {
  try {
    window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    // ignore
  }
}
