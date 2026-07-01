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
