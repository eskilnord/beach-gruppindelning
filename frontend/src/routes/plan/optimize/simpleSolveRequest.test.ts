import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import type { SolveRequestBody } from "../../../api/types";
import { buildSimpleSolveRequest } from "./simpleSolveRequest";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OPTIMIZE_PANEL_SOURCE = readFileSync(path.join(__dirname, "OptimizePanel.tsx"), "utf-8");

/**
 * v0.6.0 F4 review fix (FIX 5, MAJOR): the previous version of this file's "parity guard" was two
 * INDEPENDENTLY HAND-TYPED copies of the exact same literal object - `advancedPanelDefaultRequest`
 * below never actually read OptimizePanel.tsx, so a genuine drift there (someone flips
 * `optimizeCoaches`'s default, say) would silently pass this test forever. This extractor instead
 * reads OptimizePanel.tsx's own SOURCE BYTES and regex-pulls each relevant `useState` initializer
 * straight out of it, so a real change to the advanced panel's defaults fails THIS test loudly - and
 * one assertion per field below means the failure names exactly which field drifted.
 */
function extractUseStateBoolean(varName: string): boolean {
  const pattern = new RegExp(`const \\[${varName},\\s*set\\w+\\]\\s*=\\s*useState\\(\\s*(true|false)\\s*\\)`);
  const match = OPTIMIZE_PANEL_SOURCE.match(pattern);
  if (!match) {
    throw new Error(
      `simpleSolveRequest.test.ts: could not find OptimizePanel.tsx's useState initializer for "${varName}" - ` +
        "this parity guard's regex extraction needs updating to match the current source.",
    );
  }
  return match[1] === "true";
}

describe("OptimizePanel.tsx's own defaults, extracted from its source (parity guard, FIX 5)", () => {
  it("optimizePlayers defaults true", () => {
    expect(extractUseStateBoolean("optimizePlayers")).toBe(true);
  });

  it("optimizeSchedule defaults true", () => {
    expect(extractUseStateBoolean("optimizeSchedule")).toBe(true);
  });

  it("optimizeCoaches defaults true", () => {
    expect(extractUseStateBoolean("optimizeCoaches")).toBe(true);
  });

  it("blockPlayers defaults true", () => {
    expect(extractUseStateBoolean("blockPlayers")).toBe(true);
  });

  it("blockCoaches defaults true", () => {
    expect(extractUseStateBoolean("blockCoaches")).toBe(true);
  });

  it("blockCourts defaults false", () => {
    expect(extractUseStateBoolean("blockCourts")).toBe(false);
  });

  it("conflictsAsWarnings defaults false", () => {
    expect(extractUseStateBoolean("conflictsAsWarnings")).toBe(false);
  });

  it("coldStart defaults false", () => {
    expect(extractUseStateBoolean("coldStart")).toBe(false);
  });

  // Profile/duration semantics: the suggestion-first primary flow (SuggestDurationCard's own
  // [Optimera (N s)] button - the ONE call path buildSimpleSolveRequest actually mirrors, per its own
  // doc comment) always submits profile "CUSTOM" with the suggested duration, regardless of whatever
  // the Avancerat radio group's OWN `profile` state happens to hold at the time.
  it("the suggestion-first primary flow always submits profile: CUSTOM (SuggestDurationCard's onOptimize wiring)", () => {
    expect(OPTIMIZE_PANEL_SOURCE).toContain('handleStart("CUSTOM", durationSeconds)');
  });

  it("buildSimpleSolveRequest's body matches every extracted default, field by field", () => {
    const body = buildSimpleSolveRequest(60);
    expect(body.profile).toBe("CUSTOM");
    expect(body.optimize!.players).toBe(extractUseStateBoolean("optimizePlayers"));
    expect(body.optimize!.schedule).toBe(extractUseStateBoolean("optimizeSchedule"));
    expect(body.optimize!.coaches).toBe(extractUseStateBoolean("optimizeCoaches"));
    expect(body.blocking!.blockPlayers).toBe(extractUseStateBoolean("blockPlayers"));
    expect(body.blocking!.blockCoaches).toBe(extractUseStateBoolean("blockCoaches"));
    expect(body.blocking!.blockCourts).toBe(extractUseStateBoolean("blockCourts"));
    expect(body.blocking!.conflictsAsWarnings).toBe(extractUseStateBoolean("conflictsAsWarnings"));
    expect(body.coldStart).toBe(extractUseStateBoolean("coldStart"));
  });
});

/**
 * Kept per FIX 5 ("keep the existing literal comparison too"): a hand-pinned mirror independent of
 * BOTH buildSimpleSolveRequest's implementation and the source-derived extraction above - all three
 * would have to agree to hide a real divergence.
 */
function advancedPanelDefaultRequest(durationSeconds: number): SolveRequestBody {
  return {
    profile: "CUSTOM",
    durationSeconds,
    optimize: { players: true, schedule: true, coaches: true },
    blocking: { blockPlayers: true, blockCoaches: true, blockCourts: false, conflictsAsWarnings: false },
    coldStart: false,
  };
}

describe("buildSimpleSolveRequest", () => {
  it("matches the advanced panel's default submission body byte-for-byte", () => {
    expect(buildSimpleSolveRequest(60)).toEqual(advancedPanelDefaultRequest(60));
  });

  it("threads durationSeconds through unchanged", () => {
    expect(buildSimpleSolveRequest(123).durationSeconds).toBe(123);
    expect(buildSimpleSolveRequest(10).durationSeconds).toBe(10);
  });

  it("never omits a key present in the advanced default (JSON key-set parity)", () => {
    expect(Object.keys(buildSimpleSolveRequest(60)).sort()).toEqual(
      Object.keys(advancedPanelDefaultRequest(60)).sort(),
    );
  });
});
