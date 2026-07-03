import type { OptimizationRun, RunResultSummary } from "../../../api/types";

/**
 * Parses `OptimizationRun.resultSummaryJson` (written by the backend's `OptimizationRunService
 * #finishRun` for both real solves and the GREEDY baseline - see runs.ts javadoc for why the status
 * endpoint alone can't supply this once a run has finished). Defensive against a missing/malformed
 * blob (a run that failed before `finishRun` ran has `resultSummaryJson == null`).
 */
export function parseResultSummary(run: OptimizationRun | undefined): RunResultSummary | null {
  if (!run?.resultSummaryJson) {
    return null;
  }
  try {
    const parsed = JSON.parse(run.resultSummaryJson) as Partial<RunResultSummary>;
    return {
      hard: parsed.hard ?? 0,
      medium: parsed.medium ?? 0,
      soft: parsed.soft ?? 0,
      feasible: parsed.feasible ?? false,
      unassignedCount: parsed.unassignedCount ?? 0,
      // v0.2.0 (COACH-OPTIONAL SOLVING): present only when the solved plan had zero coaches
      // (OptimizationRunService.NOTE_NO_COACHES) - rendered verbatim (the backend owns the wording).
      note: typeof parsed.note === "string" ? parsed.note : null,
      // WI-C ("re-run doesn't feel like it re-runs" user feedback v0.4 #4): present (and true) only
      // when this run changed nothing versus the plan's state right before it started.
      unchangedFromPrevious: parsed.unchangedFromPrevious === true,
    };
  } catch {
    return null;
  }
}

/** Whole seconds elapsed, from `startedAt`/`finishedAt` ISO timestamps (falls back to `durationMs`
 *  when both timestamps aren't available - shouldn't happen for a FINISHED/CANCELLED/FAILED run,
 *  but startup-recovery-failed rows may lack `durationMs` too, hence the final `null`). */
export function runDurationSeconds(run: OptimizationRun): number | null {
  if (typeof run.durationMs === "number") {
    return Math.round(run.durationMs / 1000);
  }
  if (run.startedAt && run.finishedAt) {
    const ms = new Date(run.finishedAt).getTime() - new Date(run.startedAt).getTime();
    return Number.isFinite(ms) ? Math.round(ms / 1000) : null;
  }
  return null;
}
