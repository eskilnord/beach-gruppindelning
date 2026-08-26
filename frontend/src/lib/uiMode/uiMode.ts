import { readUiModeFromStorage } from "./uiModeStorage";

/** Global UI mode (v0.6.0 F1 plan). SIMPLE shows the reduced information architecture in later
 *  milestones; this milestone (M-S1) only introduces the plumbing/toggle/route gates - simple mode
 *  still shows all 9 plan tabs except the four gated by <AdvancedRouteGate> (router.tsx). */
export type UiMode = "SIMPLE" | "ADVANCED";

export const UI_MODE_STORAGE_KEY = "gp.uiMode";
export const UI_MODE_QUERY_PARAM = "lage";

/** Product default. Deliberately NOT the vitest test default (ADVANCED - see
 *  src/test/renderWithProviders.tsx) so every existing spec, written against the pre-v0.6.0 "all
 *  tabs always visible" UI, keeps passing unchanged. */
export const DEFAULT_UI_MODE: UiMode = "SIMPLE";

const QUERY_PARAM_TO_MODE: Record<string, UiMode> = {
  enkelt: "SIMPLE",
  avancerat: "ADVANCED",
};

/** Whether `search` carries a recognized `?lage=enkelt|avancerat` override. Used both by
 *  {@link resolveInitialUiMode} and by UiModeSync (components/uimode/UiModeSync.tsx) to decide
 *  whether a session-scoped URL override should block the backend's durable value from overwriting
 *  the local mirror. */
export function hasUiModeQueryOverride(search: string = ""): boolean {
  const queryValue = new URLSearchParams(search).get(UI_MODE_QUERY_PARAM);
  // Object.hasOwn (not `in`, which also matches inherited properties like "toString", "constructor",
  // or "__proto__") — a `?lage=` value equal to one of those must be treated as unrecognized, not as
  // a prototype-pollution-adjacent match against Object.prototype.
  return queryValue !== null && Object.hasOwn(QUERY_PARAM_TO_MODE, queryValue);
}

/**
 * Resolves the UI mode to use for THIS session, following the v0.6.0 F1 precedence: the URL param
 * (`?lage=enkelt|avancerat`, session-only - read here but NEVER written back to localStorage) beats
 * the localStorage mirror (`gp.uiMode`, kept in sync with the backend by UiModeSync) beats the
 * built-in default. An unrecognized `?lage=` value (or no param at all) falls through to
 * localStorage/default rather than throwing or defaulting outright.
 *
 * Deliberately does NOT consult the backend app-settings value synchronously - that would block
 * first paint (a network round-trip before the app can render). UiModeSync reconciles the backend
 * value in the background after mount instead.
 */
export function resolveInitialUiMode(search: string = ""): UiMode {
  const queryValue = new URLSearchParams(search).get(UI_MODE_QUERY_PARAM);
  // Object.hasOwn, not `in` — see hasUiModeQueryOverride's doc comment above.
  if (queryValue !== null && Object.hasOwn(QUERY_PARAM_TO_MODE, queryValue)) {
    return QUERY_PARAM_TO_MODE[queryValue];
  }

  const stored = readUiModeFromStorage();
  if (stored !== null) {
    return stored;
  }

  return DEFAULT_UI_MODE;
}

/**
 * Whether THIS session booted with a recognized `?lage=` URL override, captured once at module
 * load. react-router drops query params on its first client-side navigation, so re-reading
 * `window.location.search` later (e.g. from an effect in UiModeSync) would see the override
 * disappear after the very first in-app navigation, letting the backend's reconcile silently
 * overwrite a session the user explicitly pinned via the URL. Capturing it once, here, at the same
 * moment `resolveInitialUiMode` itself reads the URL, keeps the override active for the whole
 * session regardless of subsequent navigation. `typeof window !== "undefined"` guard: false outside
 * a browser (e.g. any future SSR/test harness that imports this module without a DOM).
 */
export const UI_MODE_QUERY_OVERRIDE_ACTIVE: boolean =
  typeof window !== "undefined" && hasUiModeQueryOverride(window.location.search);
