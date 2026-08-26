import { ApiError } from "../../../api/client";
import { sv } from "../../../i18n/sv";

/**
 * v0.6.0 audit-fix B12 ("Gunilla" persona): a Swedish-first error-message helper, scoped locally to
 * this directory (resources/**). `frontend/src/lib/errorText.ts` DID land as a parallel v0.6.0
 * batch, but as `fallbackErrorText(error: unknown, fallbackSv: string): string` - a DIFFERENT
 * name/signature/behavior (v0.6.0 final pre-release fix round, FIX 7: renamed from its original
 * `userErrorText` specifically to stop this local helper's name from colliding with it) - so this
 * one stays local rather than being repointed at it. This is the one place resources/** code decides
 * how to stringify a caught error: an {@link ApiError}'s message is already Swedish (the backend's uniform
 * `{"error": "..."}` shape), so it's used verbatim; anything else (a network failure, a thrown
 * non-ApiError) falls back to a generic Swedish message - the caller's own `fallback` when given
 * (a specific "kunde inte spara X" is more useful than a generic one), else
 * `sv.common.unknownError`.
 */
export function userErrorText(error: unknown, fallback: string = sv.common.unknownError): string {
  return error instanceof ApiError ? error.message : fallback;
}
