import { ApiError } from "../api/client";

/**
 * v0.6.0 audit-fix A4: raw English/backend error text ("Request failed with status 500", or
 * whatever the backend happened to say) leaking straight into the UI reads as broken to a
 * non-technical admin. Pragmatic rule (deliberately not "only replace the generic fallback"):
 * ALWAYS show the caller-supplied Swedish `fallbackSv` as the primary message, regardless of what
 * `error` actually says - the raw text is still available via {@link technicalErrorDetail} for a
 * small `size="xs" c="dimmed"` "Teknisk information: …" line underneath, for anyone who needs to
 * report the issue.
 *
 * v0.6.0 final pre-release fix round (FIX 7, MINOR, Opus m7): renamed from `userErrorText` -
 * `routes/import/userErrorText.ts` and `routes/plan/resources/errorText.ts` each have their OWN
 * unrelated, still-named `userErrorText` helper with the OPPOSITE behavior (they surface the
 * backend's own Swedish `ApiError` message when present, falling back only when it isn't) - three
 * same-named functions with opposite semantics was a live footgun for whichever one a given call
 * site actually imported. This one's actual behavior - ALWAYS the caller's fallback, never the raw
 * error - is what `fallbackErrorText` says on the label; the other two keep their names/behavior
 * unchanged (they're correctly named for what THEY do).
 */
export function fallbackErrorText(_error: unknown, fallbackSv: string): string {
  return fallbackSv;
}

/**
 * The raw backend/network error text, meant ONLY for a small dimmed "Teknisk information: …" line
 * alongside {@link fallbackErrorText}'s fallback - `undefined` when there's nothing meaningful to
 * show (e.g. a query that hasn't actually failed yet).
 */
export function technicalErrorDetail(error: unknown): string | undefined {
  if (error instanceof ApiError) {
    return error.message;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return undefined;
}
