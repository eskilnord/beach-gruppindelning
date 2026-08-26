import type { Page } from "@playwright/test";
import { UI_MODE_STORAGE_KEY, type UiMode } from "../../src/lib/uiMode/uiMode";

// Matches playwright.config.ts's `use.baseURL`: the Vite dev server origin the app itself runs
// under, which proxies `/api/*` to the fixed-port dev backend (vite.config.ts's server.proxy). Used
// below so the best-effort server-side seed hits the exact same origin/path the real app would.
const APP_ORIGIN = "http://localhost:5173";

// Matches src/lib/platform.ts's fixed browser-dev-mode token (BackendInfo.token) - every real
// request the app makes carries this header (src/api/client.ts).
const DEV_TOKEN = "dev";

/**
 * Best-effort direct `PUT /api/app-settings` (B9), with no `addInitScript` attached - unlike
 * {@link seedUiMode}'s localStorage write, this fires exactly once when awaited, not on every
 * subsequent navigation/reload the page makes. Exported on its own (not just inlined into
 * `seedUiMode`) for specs like ui-mode-toggle.spec.ts that need to reset the shared backend's
 * durable value WITHOUT also re-seeding localStorage on every reload (see that spec's own
 * `beforeEach` doc comment for why it can't use {@link useSimpleMode} directly). Swallowed on
 * failure - the endpoint may still 404 in an older checkout, and the B3 reconcile-once +
 * userChangedThisSession guards (uiModeStore.ts, UiModeSync.tsx) make every spec robust regardless
 * of whether this PUT lands.
 */
export async function resetServerUiMode(page: Page, mode: UiMode): Promise<void> {
  await page.request
    .put(new URL("/api/app-settings", APP_ORIGIN).toString(), {
      headers: { "X-GP-Token": DEV_TOKEN },
      data: { uiMode: mode },
    })
    .catch(() => {});
}

async function seedUiMode(page: Page, mode: UiMode): Promise<void> {
  await page.addInitScript(
    ([key, value]) => window.localStorage.setItem(key, value),
    [UI_MODE_STORAGE_KEY, mode] as const,
  );

  // Best-effort, also persist server-side (B9): once the backend's `/api/app-settings` endpoint
  // merges, UiModeSync's background GET would otherwise reconcile against whatever the backend still
  // has stored (its own SIMPLE default) and could override this seeded localStorage value. Aligning
  // the durable value too keeps that reconcile a no-op.
  await resetServerUiMode(page, mode);
}

/**
 * Seeds the `gp.uiMode` localStorage mirror BEFORE navigation (via `page.addInitScript`, which runs
 * ahead of every page script - including the module-load-time synchronous read in
 * src/lib/uiMode/uiModeStore.ts) so the app boots straight into the requested mode with no flash and
 * no reliance on the backend app-settings endpoint being reachable.
 *
 * `useAdvancedMode` is the e2e counterpart of vitest's ADVANCED test default
 * (src/test/renderWithProviders.tsx) - every pre-v0.6.0 e2e spec was written against "all 9 plan
 * tabs always visible", so each of them calls this in a `test.beforeEach` to keep passing unchanged
 * even though the product default (see src/lib/uiMode/uiMode.ts's DEFAULT_UI_MODE) is now SIMPLE.
 */
export async function useAdvancedMode(page: Page): Promise<void> {
  await seedUiMode(page, "ADVANCED");
}

/** Seeds `gp.uiMode` as SIMPLE - see {@link useAdvancedMode}. NOT used by ui-mode-toggle.spec.ts
 *  (despite that being the obvious use case): that spec's own "simple by default" assertion needs a
 *  genuine fresh-user boot with no `gp.uiMode` localStorage entry at all, and this seed's
 *  `addInitScript` would re-fire on that spec's own `page.reload()` and force-reset SIMPLE on its
 *  reload-persistence leg, defeating the assertion (bug B10) - see that spec's `beforeEach` for the
 *  one-shot `sessionStorage`-marker approach it uses instead, plus {@link resetServerUiMode} for
 *  resetting the shared backend's durable value without touching localStorage. Kept here as the
 *  SIMPLE-seeding counterpart to {@link useAdvancedMode} for any future spec that DOES want a plain
 *  seed-and-never-reload SIMPLE boot. */
export async function useSimpleMode(page: Page): Promise<void> {
  await seedUiMode(page, "SIMPLE");
}
