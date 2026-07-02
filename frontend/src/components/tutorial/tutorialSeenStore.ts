const STORAGE_KEY = "gp.tutorial.seen";

/**
 * Whether the kom-igång-guiden's one-time auto-offer banner (TutorialBanner.tsx, shown on Startvy)
 * has already been shown on this device. A missing/unreadable localStorage (e.g. a locked-down
 * browser profile, or a private-browsing quirk) fails "seen" rather than "unseen" - better to never
 * nag a user we can't reliably stop nagging than to show the banner every single reload.
 */
export function hasSeenTutorial(): boolean {
  try {
    return window.localStorage.getItem(STORAGE_KEY) === "1";
  } catch {
    return true;
  }
}

/** Marks the auto-offer as shown. Called the moment TutorialBanner renders it (not on dismiss/open)
 *  - "auto-offered ONCE" means shown at most once, regardless of whether the user interacts with it. */
export function markTutorialSeen(): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, "1");
  } catch {
    // Best-effort only - see hasSeenTutorial's fail-safe default above.
  }
}

/** Test-only escape hatch (mirrors resetBackendInfoCacheForTests in api/client.ts). */
export function resetTutorialSeenForTests(): void {
  try {
    window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    // ignore
  }
}
