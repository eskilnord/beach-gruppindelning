/**
 * The ONLY file in the frontend allowed to touch Tauri APIs (CLAUDE.md, docs/design/01
 * -architecture.md §6). Every other module — including src/api/client.ts — must go through the
 * helpers exported here, so the SPA keeps working unmodified in a plain browser tab.
 *
 * In the desktop shell, `window.__TAURI__` is injected by Tauri's webview preload script before any
 * of our code runs, so its presence is a reliable, synchronous way to detect the shell without
 * importing @tauri-apps/api eagerly (that import only happens once we already know we're in Tauri).
 */

export interface BackendInfo {
  /** Empty string in browser dev mode: same-origin requests flow through the Vite proxy to the
   *  fixed-port dev backend (see vite.config.ts). In Tauri this is the real
   *  `http://127.0.0.1:<randomPort>` the shell spawned the backend on. */
  base_url: string;
  /** Value of the X-GP-Token header. Fixed "dev" token in browser dev mode (matches the backend's
   *  `dev` Spring profile fallback, see backend GpTokenResolver); a random per-session token in
   *  Tauri, provided by the shell after the spawn/handshake protocol completes. */
  token: string;
}

declare global {
  interface Window {
    __TAURI__?: unknown;
  }
}

/** True when running inside the Tauri desktop shell's webview, false in an ordinary browser tab. */
export function isTauri(): boolean {
  return typeof window !== "undefined" && "__TAURI__" in window;
}

/**
 * Resolves how the frontend should talk to the backend. Never throws in browser mode; in Tauri it
 * awaits the shell's `get_backend_info` command (populated once the spawn/handshake protocol in
 * desktop/src-tauri/src/backend.rs completes).
 */
export async function getBackendInfo(): Promise<BackendInfo> {
  if (isTauri()) {
    const { invoke } = await import("@tauri-apps/api/core");
    return invoke<BackendInfo>("get_backend_info");
  }
  return { base_url: "", token: "dev" };
}

/**
 * Recovers from a crashed backend without restarting the whole desktop app: in Tauri this invokes
 * the shell's `retry_backend` command (desktop/src-tauri/src/backend.rs), which kills the (likely
 * already-dead) child process, respawns it, redoes the handshake, and returns a fresh BackendInfo
 * (new port + token) — or throws if the respawn itself fails. In browser dev mode there's no child
 * process to restart, so this just resolves with the same fixed dev info as {@link getBackendInfo}.
 */
export async function restartBackend(): Promise<BackendInfo> {
  if (isTauri()) {
    const { invoke } = await import("@tauri-apps/api/core");
    return invoke<BackendInfo>("retry_backend");
  }
  return { base_url: "", token: "dev" };
}

/**
 * v0.6.0 audit batch D (D7): {@link saveFile}'s outcome, richer than a plain boolean so callers can
 * show truthful, mode-aware feedback instead of one "laddades ner" string that's only actually true
 * in the browser branch:
 *  - `saved`: false only when the admin cancelled the Tauri save dialog (the browser branch always
 *    "succeeds" from the page's point of view once the download is triggered - there is no
 *    equivalent cancellation signal there).
 *  - `isTauriSave`: which branch actually ran, so a caller can pick "Filen sparades." (Tauri, a real
 *    disk write via the native dialog) vs. "Exporten laddades ner." (browser, an `<a download>`)
 *    without re-deriving it from {@link isTauri} itself.
 *  - `filename`: the name actually used, when known - the chosen path's basename in Tauri, or the
 *    suggested filename in the browser (the browser always honors it for `<a download>`). Omitted
 *    only when `saved` is false.
 */
export interface SaveFileResult {
  saved: boolean;
  isTauriSave: boolean;
  filename?: string;
}

/**
 * Saves a downloaded file (M8 export, spec §20/§21.3) to disk, browser-vs-Tauri per CLAUDE.md's dev
 * commands note ("export = byte download, Tauri APIs isolated in platform.ts with browser
 * fallbacks"): in the browser, a temporary `<a download>` + object URL (no filesystem access
 * available from a plain tab); in Tauri, the native "Spara som"-dialog (`@tauri-apps/plugin-dialog`'s
 * `save`) followed by a direct byte write (`@tauri-apps/plugin-fs`'s `writeFile`) - both dynamically
 * imported, same pattern as `getBackendInfo`'s `@tauri-apps/api/core` import, so the browser bundle
 * never needs the desktop-only plugins. See {@link SaveFileResult}'s own doc comment for what the
 * return value tells callers - `{saved: false}` when the admin cancelled the Tauri save dialog.
 *
 * NOTE for the desktop shell: `@tauri-apps/plugin-dialog`/`@tauri-apps/plugin-fs` must also be
 * registered Rust-side (desktop/src-tauri/Cargo.toml dependencies + capabilities/default.json
 * permissions) before this Tauri branch works at runtime - out of scope for this milestone's
 * frontend-only half (see docs/plan.md M8), tracked as a desktop-side follow-up.
 */
export async function saveFile(blob: Blob, suggestedFilename: string): Promise<SaveFileResult> {
  if (isTauri()) {
    const { save } = await import("@tauri-apps/plugin-dialog");
    const path = await save({ defaultPath: suggestedFilename });
    if (!path) {
      return { saved: false, isTauriSave: true };
    }
    const { writeFile } = await import("@tauri-apps/plugin-fs");
    await writeFile(path, new Uint8Array(await blob.arrayBuffer()));
    const filename = path.split(/[\\/]/).pop() || suggestedFilename;
    return { saved: true, isTauriSave: true, filename };
  }

  const url = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = suggestedFilename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  } finally {
    URL.revokeObjectURL(url);
  }
  return { saved: true, isTauriSave: false, filename: suggestedFilename };
}
