import { ApiError } from "../api/client";

/**
 * v0.6.0 audit-fix A4: raw English/backend error text ("Request failed with status 500", or
 * whatever the backend happened to say) leaking straight into the UI reads as broken to a
 * non-technical admin. Pragmatic rule (deliberately not "only replace the generic fallback"):
 * ALWAYS show the caller-supplied Swedish `fallbackSv` as the primary message, regardless of what
 * `error` actually says - the raw text is still available via {@link technicalErrorDetail} for a
 * small `size="xs" c="dimmed"` "Teknisk information: …" line underneath, for anyone who needs to
 * report the issue.
 */
export function userErrorText(_error: unknown, fallbackSv: string): string {
  return fallbackSv;
}

/**
 * The raw backend/network error text, meant ONLY for a small dimmed "Teknisk information: …" line
 * alongside {@link userErrorText}'s fallback - `undefined` when there's nothing meaningful to show
 * (e.g. a query that hasn't actually failed yet).
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
