import type { SolveRequestBody } from "../../../api/types";

/**
 * v0.6.0 F4 (M-S4): the SIMPLE-mode "Skapa grupper" button (OptimizePanelSimple.tsx) submits EXACTLY
 * the request the ADVANCED panel's suggestion-first primary flow (SuggestDurationCard's
 * [Optimera (N s)] button, wired to `handleStart("CUSTOM", durationSeconds)` in OptimizePanel.tsx)
 * would submit against a freshly-opened OptimizePanel - i.e. its default `useState` values,
 * untouched by the admin. Verified against OptimizePanel.tsx's own initializers:
 *   - `optimizePlayers`/`optimizeSchedule`/`optimizeCoaches` all default `true`
 *   - `blockPlayers`/`blockCoaches` default `true`, `blockCourts`/`conflictsAsWarnings` default `false`
 *   - `coldStart` defaults `false`
 * and its `handleStart` body, which always sends `profile: "CUSTOM"` for this flow.
 *
 * Deliberately a pure, hand-pinned mirror rather than an import from OptimizePanel.tsx: that file
 * must stay byte-identical (F4 brief) and doesn't export its defaults. simpleSolveRequest.test.ts is
 * the parity guard - it independently re-derives the same literal shape and asserts deep-equality,
 * so a future change to OptimizePanel's defaults that isn't mirrored here fails loudly instead of
 * silently producing byte-different simple/advanced default requests.
 */
export function buildSimpleSolveRequest(durationSeconds: number): SolveRequestBody {
  return {
    profile: "CUSTOM",
    durationSeconds,
    optimize: { players: true, schedule: true, coaches: true },
    blocking: { blockPlayers: true, blockCoaches: true, blockCourts: false, conflictsAsWarnings: false },
    coldStart: false,
  };
}
