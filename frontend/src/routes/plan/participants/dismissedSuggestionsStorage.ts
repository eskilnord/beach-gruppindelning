const KEY_PREFIX = "gp.dismissedSuggestions.";

function storageKey(planId: string): string {
  return `${KEY_PREFIX}${planId}`;
}

/**
 * Stable per-suggestion identifier for dismissal persistence (B18.3, v0.6.0 audit-fix batch B) - a
 * suggestion's own `fingerprint` isn't documented as globally unique across participants, so this
 * composes it with the participant id to be safe against a cross-participant collision silently
 * dismissing the wrong suggestion.
 */
export function suggestionDismissalId(participantId: string, fingerprint: string): string {
  return `${participantId}:${fingerprint}`;
}

/**
 * Fail-safe localStorage read for one plan's dismissed "Tolkningsförslag" ids - mirrors
 * lib/uiMode/uiModeStorage.ts's pattern: a locked-down browser profile or a private-browsing quirk
 * must never crash the app or throw over reading/writing this. Returns an empty set on a missing key
 * or an unreadable/corrupt value.
 */
export function readDismissedSuggestions(planId: string): Set<string> {
  try {
    const raw = window.localStorage.getItem(storageKey(planId));
    if (!raw) {
      return new Set();
    }
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      return new Set();
    }
    return new Set(parsed.filter((value): value is string => typeof value === "string"));
  } catch {
    return new Set();
  }
}

/** Best-effort write - see {@link readDismissedSuggestions}'s fail-safe rationale above. */
export function addDismissedSuggestion(planId: string, dismissalId: string): void {
  try {
    const current = readDismissedSuggestions(planId);
    current.add(dismissalId);
    window.localStorage.setItem(storageKey(planId), JSON.stringify(Array.from(current)));
  } catch {
    // ignore - the in-memory Set in CommentSuggestionList still hides it for this session.
  }
}
