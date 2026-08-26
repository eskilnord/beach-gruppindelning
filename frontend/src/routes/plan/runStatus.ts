import type { OptimizationRun } from "../../api/types";
import { parseResultSummary } from "./optimize/runSummary";

/**
 * v0.6.0 final pre-release fix round (FIX 1, MAJOR): the ONE shared "does this run actually have a
 * viewable/exportable result?" predicate. Before this module existed, four different call sites each
 * answered that question their own (inconsistent) way:
 *  - PlanSimpleStepper.tsx gated Optimera/Resultat's checkmarks on `data[0]?.status === "FINISHED"`
 *    only - a CANCELLED run that still persisted a best-so-far result never counted.
 *  - OptimizePanelSimple.tsx's own outcome card DOES present a CANCELLED run as having viewable
 *    groups (`sv.simple.optimize.cancelledAlert` + `outcomeShowViewGroups`) whenever it has a
 *    parseable summary - the opposite of the stepper's stricter check.
 *  - ResultsPanel.tsx keyed its empty state purely on `groups.length === 0`, ignoring run status
 *    entirely (groups can outlive/predate the run that produced them).
 *  - Both export gates (ExportPanel.tsx, SimpleSaveExportCard.tsx) used `runs.length > 0` - which
 *    accepts ANY run, including one still SOLVING or one that FAILED outright.
 *
 * The discriminator a run "has a usable, persisted result" is:
 *  - status `FINISHED` (the ordinary success path - `OptimizationRunService#finishRun` always
 *    persists a `resultSummaryJson` for these), OR
 *  - status `CANCELLED` WITH a parseable `resultSummaryJson` - `finishRun` also persists one for a
 *    cancel-with-partial-progress (a cancel after the solver found at least one improving solution),
 *    which is exactly the case OptimizePanelSimple's own `cancelledAlert` copy already tells the
 *    admin about ("dina bästa hittills sparade grupper" - best-so-far was saved). A CANCELLED run
 *    with NO parseable summary (cancelled before the solver ever produced a first solution) has
 *    nothing to show and does NOT count.
 *  - Anything else (`FAILED`, `SOLVING_ACTIVE`, `SOLVING_SCHEDULED`, `NOT_SOLVING`, or a CANCELLED
 *    run with an unparseable/absent summary) has no usable result.
 *
 * Reuses `parseResultSummary` (the same parser every other consumer of `resultSummaryJson` already
 * goes through) rather than re-deciding what "parseable" means here.
 */
export function runHasUsableResult(run: OptimizationRun): boolean {
  if (run.status === "FINISHED") {
    return true;
  }
  if (run.status === "CANCELLED") {
    return parseResultSummary(run) !== null;
  }
  return false;
}

/** Whether the plan's run history contains AT LEAST ONE run with a usable, persisted result (see
 *  {@link runHasUsableResult}) - the shared predicate every "is there anything to view/export yet?"
 *  gate now uses instead of `runs.length > 0`. */
export function hasUsableResult(runs: OptimizationRun[] | undefined): boolean {
  return (runs ?? []).some(runHasUsableResult);
}

/** The most recent run with a usable result, or `undefined` if none exists. `runs` is already
 *  most-recent-first (`useOptimizationRuns`'s own doc comment - the backend's own ordering), so this
 *  is simply the first match walking from the front, NOT necessarily `runs[0]` itself (the very
 *  latest run overall may be a still-SOLVING or FAILED one that a later, usable run predates). */
export function latestUsableRun(runs: OptimizationRun[] | undefined): OptimizationRun | undefined {
  return (runs ?? []).find(runHasUsableResult);
}
