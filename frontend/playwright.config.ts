import { defineConfig, devices } from "@playwright/test";
import { mkdtempSync } from "node:fs";
import { tmpdir, homedir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

// package.json has "type": "module", so __dirname isn't available — derive it from import.meta.url.
const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Fresh scratch data dir per test run so e2e never touches a developer's real platform data
// directory (docs/plan.md M2 verification step; CLAUDE.md confidentiality rules).
const gpDataDir = mkdtempSync(path.join(tmpdir(), "gp-e2e-"));
const javaHome = process.env.JAVA_HOME ?? path.join(homedir(), ".jdks/jdk-21.0.11+10/Contents/Home");

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  timeout: 60_000,
  reporter: [["list"]],
  use: {
    // "localhost", not "127.0.0.1": must match the Origin the backend's dev-profile CORS config
    // allowlists (DevCorsConfig), or mutating requests get 403'd by Spring's CORS filter.
    baseURL: "http://localhost:5173",
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: "./mvnw spring-boot:run -Dspring-boot.run.profiles=dev",
      cwd: path.resolve(__dirname, "../backend"),
      url: "http://127.0.0.1:4517/v3/api-docs",
      // /v3/api-docs is the token-exempt (dev-profile-only) readiness probe: /api/health always
      // requires X-GP-Token, and Playwright's own webServer readiness check cannot attach headers.
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
      env: { JAVA_HOME: javaHome, GP_DATA_DIR: gpDataDir },
      stdout: "pipe",
      stderr: "pipe",
    },
    {
      command: "npm run dev",
      cwd: __dirname,
      url: "http://localhost:5173",
      reuseExistingServer: !process.env.CI,
      timeout: 60_000,
      stdout: "pipe",
      stderr: "pipe",
    },
  ],
});
