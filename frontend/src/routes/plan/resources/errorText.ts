import { ApiError } from "../../../api/client";
import { sv } from "../../../i18n/sv";

/**
 * v0.6.0 audit-fix B12 ("Gunilla" persona): a Swedish-first error-message helper, scoped locally to
 * this directory (resources/**). A parallel v0.6.0 batch may add a shared
 * `frontend/src/lib/errorText.ts` with the same `userErrorText(error: unknown): string` name/
 * signature later - at that point every call site here can be repointed at the shared one and this
 * file deleted. Until then, this is the one place resources/** code decides how to stringify a
 * caught error: an {@link ApiError}'s message is already Swedish (the backend's uniform
 * `{"error": "..."}` shape), so it's used verbatim; anything else (a network failure, a thrown
 * non-ApiError) falls back to a generic Swedish message - the caller's own `fallback` when given
 * (a specific "kunde inte spara X" is more useful than a generic one), else
 * `sv.common.unknownError`.
 */
export function userErrorText(error: unknown, fallback: string = sv.common.unknownError): string {
  return error instanceof ApiError ? error.message : fallback;
}
