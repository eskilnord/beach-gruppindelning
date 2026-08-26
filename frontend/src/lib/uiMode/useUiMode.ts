import { useCallback } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { notifications } from "@mantine/notifications";
import { appSettingsKey, useSetUiMode } from "../../api/appSettings";
import { sv } from "../../i18n/sv";
import { useUiModeStore } from "./uiModeStore";
import type { UiMode } from "./uiMode";

export interface UseUiModeResult {
  mode: UiMode;
  isSimple: boolean;
  isAdvanced: boolean;
  /** Optimistic: the store + localStorage mirror update synchronously, then `PUT /api/app-settings`
   *  fires in the background. On failure the local value is kept (not rolled back) and a yellow
   *  warning notification is shown - see this module's doc comment. */
  setMode: (mode: UiMode) => void;
}

/**
 * The app-wide hook for reading and changing the global UI mode. `setMode` is optimistic: the
 * zustand store (uiModeStore.ts) and its localStorage mirror update immediately - so the UI reacts
 * with no perceived latency and the choice survives a reload even offline - while the durable
 * `PUT /api/app-settings` persist happens in the background. If that PUT fails, the local value is
 * deliberately KEPT (the mode still applies on this computer, only server-side persistence failed)
 * and a yellow Mantine notification (sv.uiMode.saveFailedNotice) is shown instead of blocking the
 * toggle or reverting it.
 *
 * B4 (PUT race): a rapid double-toggle can have two PUTs in flight at once, and they can resolve out
 * of order. `storeSetMode` returns a token that's bumped on every call
 * (uiModeStore.ts's `requestToken`); the onSuccess/onError below only act if that token is still the
 * store's current one - an older, out-of-order response is silently dropped instead of writing stale
 * data into the query cache or popping a failure notification for a choice the user has since moved
 * past.
 *
 * Deliberately does NOT run the `GET /api/app-settings` query itself - see
 * src/components/uimode/UiModeSync.tsx's doc comment for why that query lives only there. The
 * mutation used here is inert (fires no request) until `setMode` is actually called.
 */
export function useUiMode(): UseUiModeResult {
  const mode = useUiModeStore((state) => state.mode);
  const storeSetMode = useUiModeStore((state) => state.setMode);
  const { mutate } = useSetUiMode();
  const queryClient = useQueryClient();

  const setMode = useCallback(
    (next: UiMode) => {
      const token = storeSetMode(next);
      const isStale = () => useUiModeStore.getState().requestToken !== token;

      mutate(next, {
        onSuccess: (data) => {
          if (isStale()) {
            return;
          }
          queryClient.setQueryData(appSettingsKey, data);
        },
        onError: () => {
          if (isStale()) {
            return;
          }
          notifications.show({
            color: "yellow",
            title: sv.uiMode.saveFailedNoticeTitle,
            message: sv.uiMode.saveFailedNotice,
          });
        },
      });
    },
    [storeSetMode, mutate, queryClient],
  );

  return { mode, isSimple: mode === "SIMPLE", isAdvanced: mode === "ADVANCED", setMode };
}

/** Narrow selector for components that only care whether the app is in SIMPLE mode (e.g.
 *  AdvancedOnly/SimpleOnly) - avoids re-rendering on unrelated store changes since it subscribes to
 *  just the derived boolean. */
export function useIsSimpleMode(): boolean {
  return useUiModeStore((state) => state.mode === "SIMPLE");
}
