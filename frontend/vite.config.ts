import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// Dev workflow (docs/design/01-architecture.md §6, CLAUDE.md dev commands): Vite serves the SPA on
// 5173 and proxies /api to the fixed-port dev backend (Boot profile "dev", port 4517, dev token
// "dev"). This lets the frontend run fully in a plain browser without the Tauri shell.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Bind explicitly to the "localhost" hostname (not the 127.0.0.1 literal): the backend's
    // dev-profile CORS config (DevCorsConfig, backend/src/main/java/.../config) only allowlists
    // the Origin "http://localhost:5173", so accessing the dev server via 127.0.0.1 would send a
    // mismatching Origin header and get every mutating request 403'd by Spring's CORS filter.
    host: "localhost",
    proxy: {
      "/api": {
        target: "http://127.0.0.1:4517",
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    css: true,
    exclude: ["**/node_modules/**", "**/e2e/**", "**/dist/**"],
  },
});
