import { useEffect, useRef } from "react";
import { useIsMutating } from "@tanstack/react-query";
import { SET_UI_MODE_MUTATION_KEY, useAppSettings } from "../../api/appSettings";
import { UI_MODE_QUERY_OVERRIDE_ACTIVE } from "../../lib/uiMode/uiMode";
import { applyReconciledUiMode, useUiModeStore } from "../../lib/uiMode/uiModeStore";

/**
 * The ONLY place in the app that runs the `GET /api/app-settings` query. This is a deliberate
 * v0.6.0 F1 rule, not an oversight: that query must NOT live inside useUiMode.ts, because the MSW
 * test server (src/test/server.ts) runs with `onUnhandledRequest: "error"` - if the query fired
 * from a hook used all over the app, every existing panel/component spec that renders anything
 * consuming useUiMode would suddenly need a new /api/app-settings handler just to keep passing.
 * Mounting it in exactly one place, once, in AppShellLayout (above every route), keeps that blast
 * radius at zero.
 *
 * Reconciliation policy (B3): the backend is durable truth for the local mirror, but must never
 * silently override (i) a session-scoped `?lage=` URL override, or (ii) a mode the user has actively
 * changed this session (uiModeStore.ts's `userChangedThisSession`) - both cases mean the user's own
 * intent for THIS session outranks whatever the backend happens to still say. This effect also only
 * ever reconciles ONCE per mount (the `reconciledRef` guard) - AppShellLayout mounts this component
 * once for the whole app session, so "once per mount" is "once per session" in practice; a later GET
 * refetch (e.g. window refocus) must not re-trigger a second silent overwrite. And it waits out any
 * set-mode PUT that's currently in flight (`useIsMutating`) rather than racing it, retrying once that
 * PUT settles. If the backend is unreachable (or the endpoint doesn't exist yet in this checkout),
 * `data` simply never arrives and the local value is silently kept - this component never renders
 * any loading/error UI of its own.
 */
export function UiModeSync() {
  const { data } = useAppSettings();
  const isSettingMode = useIsMutating({ mutationKey: SET_UI_MODE_MUTATION_KEY }) > 0;
  const reconciledRef = useRef(false);

  useEffect(() => {
    if (reconciledRef.current || !data) {
      return;
    }
    if (UI_MODE_QUERY_OVERRIDE_ACTIVE || useUiModeStore.getState().userChangedThisSession) {
      reconciledRef.current = true;
      return;
    }
    if (isSettingMode) {
      // A set-mode PUT is in flight - wait for it to settle (re-runs this effect once
      // `isSettingMode` flips back to false) rather than racing it.
      return;
    }
    reconciledRef.current = true;
    if (data.uiMode !== useUiModeStore.getState().mode) {
      applyReconciledUiMode(data.uiMode);
    }
  }, [data, isSettingMode]);

  return null;
}
