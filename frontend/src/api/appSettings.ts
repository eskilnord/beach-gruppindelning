import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "./client";
import type { AppSettings } from "./types";
import type { UiMode } from "../lib/uiMode/uiMode";

export const appSettingsKey = ["app-settings"] as const;

/** Shared `mutationKey` for the `PUT /api/app-settings` mutation (useSetUiMode below) - lets
 *  UiModeSync detect "a set-mode PUT is currently in flight" via `useIsMutating({ mutationKey:
 *  SET_UI_MODE_MUTATION_KEY })` without importing useUiMode.ts (see that hook's own "GET lives only
 *  in UiModeSync" doc comment for why the two must stay decoupled). */
export const SET_UI_MODE_MUTATION_KEY = ["app-settings", "set-ui-mode"] as const;

/**
 * `GET /api/app-settings`. Deliberately used ONLY by
 * src/components/uimode/UiModeSync.tsx - never inside src/lib/uiMode/useUiMode.ts - so no other
 * panel or spec accidentally triggers this request: the MSW test server
 * (src/test/server.ts) runs with `onUnhandledRequest: "error"`, so an existing panel spec that
 * doesn't register a handler for this endpoint would otherwise start failing.
 */
export function useAppSettings() {
  return useQuery({
    queryKey: appSettingsKey,
    queryFn: () => api.get<AppSettings>("/api/app-settings"),
  });
}

/** `PUT /api/app-settings`. Used by useUiMode.ts's optimistic `setMode` - the store/localStorage
 *  update already happened by the time this fires; a failure here is reported via a notification,
 *  never by rolling back the local value (see useUiMode.ts's doc comment).
 *
 *  Deliberately has NO `onSuccess`/`onError` here: a rapid double-toggle can fire two overlapping
 *  PUTs, and this hook has no way to tell an in-order response from a stale, out-of-order one. That
 *  token-based staleness check (B4) needs the request token captured at call time, so the cache
 *  write and failure notification both live in useUiMode.ts's `setMode` (passed to `mutate(...)`'s
 *  own per-call options) instead of here. */
export function useSetUiMode() {
  return useMutation({
    mutationKey: SET_UI_MODE_MUTATION_KEY,
    mutationFn: (uiMode: UiMode) => api.put<AppSettings>("/api/app-settings", { uiMode }),
  });
}
