import { ApiError } from "../../api/client";
import { sv } from "../../i18n/sv";

/**
 * v0.6.0 audit-fix B5: Swedish-first, user-facing text for an import-wizard fetch/mutation failure —
 * never dumps a raw `error.message` (which could be an English `TypeError` string, or otherwise
 * unreviewed text) straight into the UI.
 *
 * - A backend `ApiError` already carries a normalized Swedish message (the uniform `{"error": "..."}`
 *   shape, backend/docs/m1-notes.md) — that message is trusted and returned as-is, falling back to a
 *   generic Swedish sentence only if it's somehow empty.
 * - Anything else (most commonly a `TypeError` — what `fetch()` itself rejects with when the request
 *   never reaches a server at all, e.g. the desktop backend isn't up) is treated as a NETWORK failure,
 *   distinct from a request the backend actually received and rejected.
 *
 * `frontend/src/lib/errorText.ts` DID land as a parallel v0.6.0 batch, but as
 * `fallbackErrorText(error: unknown, fallbackSv: string): string` - a DIFFERENT name/signature/
 * behavior (v0.6.0 final pre-release fix round, FIX 7: renamed from its original `userErrorText`
 * specifically to stop THIS local helper's name from colliding with it) - so this stays local to
 * `routes/import/` rather than being repointed at it.
 */
export function userErrorText(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message.trim().length > 0 ? error.message : sv.importWizard.genericError;
  }
  if (error instanceof TypeError) {
    return sv.importWizard.networkError;
  }
  return sv.importWizard.genericError;
}
