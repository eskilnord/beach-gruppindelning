import { pluralize } from "../../lib/pluralizeSv";
import { sv } from "../../i18n/sv";

/**
 * v0.6.0 F2 (M-S2): the six-step simple-mode information architecture that replaces the 9-tab bar
 * (PlanLayout.tsx, PlanSimpleStepper.tsx) in SIMPLE mode. Deliberately a much shorter list than the
 * 9 plan tabs - it folds Fält/Tränare/Kapacitet/Planer (still reachable in ADVANCED mode, and via
 * <AdvancedRouteGate> deep links) out of the simple-mode flow entirely, and adds "Prioriteringar" as
 * a new simple-first step whose real panel lands in F3 (PrioritiesPanel.tsx is a placeholder for
 * now - see router.tsx).
 *
 * `labelKey` indexes into `sv.simple.steps` rather than embedding the resolved Swedish string
 * directly, so this module stays a plain, i18n-independent data table - PlanSimpleStepper.tsx (the
 * only consumer) resolves the label via `sv.simple.steps[step.labelKey]`.
 */
export type SimpleStepLabelKey = "deltagare" | "tider" | "prioriteringar" | "optimera" | "resultat" | "exportera";

export interface SimpleStep {
  /** Route segment under `/plans/:planId/<path>` - matches router.tsx. */
  path: string;
  labelKey: SimpleStepLabelKey;
  testId: string;
}

export const SIMPLE_STEPS: SimpleStep[] = [
  { path: "deltagare", labelKey: "deltagare", testId: "plan-simple-step-deltagare" },
  { path: "resurser", labelKey: "tider", testId: "plan-simple-step-tider" },
  { path: "prioriteringar", labelKey: "prioriteringar", testId: "plan-simple-step-prioriteringar" },
  { path: "optimering", labelKey: "optimera", testId: "plan-simple-step-optimera" },
  { path: "resultat", labelKey: "resultat", testId: "plan-simple-step-resultat" },
  // v0.6.0 F2 review fix (FIX 7): testId now follows the same `plan-simple-step-<labelKey>`
  // convention as the other five (it used to be `plan-simple-step-export`, the route *path*, the
  // only one of the six that didn't match its labelKey).
  { path: "export", labelKey: "exportera", testId: "plan-simple-step-exportera" },
];

/** Bare `/plans/:planId` (no sub-route segment yet) - matches router.tsx's index-route redirect
 *  target (`<Navigate to="deltagare" replace />`, fired on the very next render) and PlanLayout.tsx's
 *  own ADVANCED-mode fallback (`TABS.find(...) ?? TABS[0]`): both treat the plan root as "the first
 *  step" for the instant before the redirect actually lands, rather than "no step at all". */
const PLAN_ROOT_PATTERN = /^\/plans\/[^/]+$/;

/**
 * Resolves `pathname` (e.g. `/plans/abc-123/resurser`) to its index in {@link SIMPLE_STEPS}, or -1
 * when the current route isn't one of the six steps at all - e.g. a gated tab (falt/tranare/
 * kapacitet/planer) reached via a deep link while in SIMPLE mode, or the import wizard. Same
 * suffix-match approach as PlanLayout.tsx's own `TABS.find(...)` for the ADVANCED tab bar.
 *
 * v0.6.0 F2 review fix (FIX 5): a trailing slash (e.g. `/plans/abc-123/resurser/`) is stripped before
 * matching - `pathname.endsWith` would otherwise never match any step for such a URL, even though
 * it's the same route. The bare plan root (`/plans/abc-123`, no segment at all) resolves to index 0,
 * matching PlanLayout's own fallback-to-first-tab behavior in ADVANCED mode (see
 * {@link PLAN_ROOT_PATTERN}'s doc comment) rather than the -1 an unrelated/unknown route gets.
 */
export function resolveSimpleStepIndex(pathname: string): number {
  const normalized = pathname.length > 1 && pathname.endsWith("/") ? pathname.slice(0, -1) : pathname;
  const stepIndex = SIMPLE_STEPS.findIndex((step) => normalized.endsWith(`/${step.path}`));
  if (stepIndex !== -1) {
    return stepIndex;
  }
  return PLAN_ROOT_PATTERN.test(normalized) ? 0 : -1;
}

export interface StepCompletion {
  /**
   * Whether the step looks "done". `undefined` (not `false`) when there's no cheap signal to derive
   * it from yet - e.g. Prioriteringar (placeholder route, F3). Guidance only, never a gate:
   * PlanSimpleStepper actually renders this now (v0.6.0 F2 review fix, FIX 1 - it used to be
   * computed and then never read, which let the Mantine-native, position-only checkmark misleadingly
   * mark every step "done" once the admin navigated past it, regardless of this value).
   * `allowNextStepsSelect` still lets the admin jump straight to any step regardless of what's
   * "completed" here.
   */
  completed: boolean | undefined;
  /** Description shown under the step label - either a live-number ("260 deltagare", "3 tider") or,
   *  for the steps with no cheap live signal, a static fallback (sv.simple.stepDescriptions -
   *  FIX 8) so all six steps render a description line and the stepper doesn't have uneven step
   *  heights. `undefined` only while the underlying live-number query is still loading (or errored -
   *  see PlanSimpleStepper.tsx's doc comment on that). */
  description: string | undefined;
}

/** Already-fetched, cheap counts this milestone reuses instead of inventing new backend calls -
 *  see PlanSimpleStepper.tsx for which existing react-query hooks supply each one.
 *  `undefined` means "not loaded yet" (query still pending/erroring), which {@link completionFor}
 *  treats the same as "no signal" rather than as zero. */
export interface StepCompletionInput {
  participantsCount: number | undefined;
  /** Count of configured time SLOTS - drives the Tider step's description text only (e.g.
   *  "3 tider"). See {@link activeCourtsCount} for what actually gates the checkmark. */
  timeSlotsCount: number | undefined;
  /**
   * v0.6.0 audit-fix A8: the number of ACTIVE training-block "banor" across every time slot -
   * derived from the same `useTrainingBlocksForPlan` query ResourcesPanel.tsx already keys on (see
   * PlanSimpleStepper.tsx). A plan can have time slots configured with every court switched off
   * (0 capacity) - that's not "done" no matter how many slots exist, so this - not
   * {@link timeSlotsCount} - is what {@link tiderCompletion} gates the checkmark on.
   */
  activeCourtsCount: number | undefined;
  /** Count of optimization runs ever started - drives the Optimera step's description text only
   *  (e.g. "2 körningar"). See {@link hasUsableResult} for what actually gates the checkmark. */
  optimizationRunsCount: number | undefined;
  /**
   * v0.6.0 final pre-release fix round (FIX 1, MAJOR): whether the plan's run history contains a run
   * with an actual persisted, viewable/exportable result - see `runStatus.ts#hasUsableResult`
   * (FINISHED, or CANCELLED with a parseable resultSummaryJson). Replaces the previous
   * `latestRunFinished` signal (`data[0]?.status === "FINISHED"`, audit-fix A8), which painted a
   * genuinely-usable CANCELLED-with-partial-progress run as "not done" even though
   * OptimizePanelSimple's own cancelled-outcome copy tells the admin their best-so-far groups were
   * saved. `undefined` while `useOptimizationRuns` hasn't resolved yet (same "no signal yet"
   * treatment as every other input here). Both {@link optimeraCompletion} and
   * {@link resultatCompletion} gate on this SAME signal.
   */
  hasUsableResult: boolean | undefined;
  /**
   * v0.6.0 final pre-release fix round (FIX 1, MAJOR): whether the most recent USABLE run (see
   * {@link hasUsableResult}) left every participant placed (`unassignedCount === 0`, from that run's
   * parsed `resultSummaryJson`) - gates Resultat's checkmark IN ADDITION TO `hasUsableResult`, so a
   * green Resultat check never contradicts a plan whose latest solve still shows a non-empty
   * waitlist (the same "no green lie about waitlisted kids" rule OptimizePanelSimple's own outcome
   * card already follows). `undefined` when there's no usable run yet, or its summary couldn't be
   * parsed - never assumed to be `true` in either case.
   */
  resultatAllPlaced: boolean | undefined;
  /** v0.6.0 F3 (M-S3): the loaded `GET /api/plans/{planId}/priority-order` view, reduced to just
   *  the two fields {@link priorityCompletion} needs - `undefined` while that query hasn't resolved
   *  yet (same "no signal" treatment as the other inputs above). See PlanSimpleStepper.tsx for
   *  how this is derived from `usePriorityOrder`'s full response. */
  priorityOrder: PriorityOrderCompletionInput | undefined;
  /** v0.6.0 F6 (M-S6): `useSavedPlans(planId).data?.length` - Saving a version is a real
   *  user action - `count > 0` is honest evidence of "the admin has saved at least once", so this
   *  step reuses the same countCompletion helper as deltagare/tider above, not a static fallback. */
  savedPlansCount: number | undefined;
}

/** See {@link StepCompletionInput.priorityOrder}'s doc comment. */
export interface PriorityOrderCompletionInput {
  customWeightsActive: boolean;
  /** The current rank-1 priority's backend-supplied `labelSv` (e.g. "Träna tillsammans") - rendered
   *  verbatim in the step description, same "no client-side wording" rule the real Prioriteringar
   *  screen (PrioritiesPanel.tsx) follows. */
  topPriorityLabelSv: string;
  /** v0.6.0 F3 review fix (FIX 4, MAJOR): `PriorityOrderView.updatedAt` - `null` until the order has
   *  ever been explicitly saved (api/priorityOrder.ts's doc comment). Drives {@link priorityCompletion}
   *  below: gates the checkmark on an ACTUAL save having happened, not merely on the query having
   *  resolved (every plan's GET resolves immediately with a seeded default order, even one nobody has
   *  ever looked at). */
  updatedAt: string | null;
}

function countCompletion(count: number | undefined, singular: string, plural: string): StepCompletion {
  if (count === undefined) {
    return { completed: undefined, description: undefined };
  }
  return { completed: count > 0, description: pluralize(count, singular, plural) };
}

/**
 * v0.6.0 audit-fix A8: completion now gates on {@link StepCompletionInput.activeCourtsCount} (real
 * capacity), not the raw slot count - a plan can have every court switched off on every slot, which
 * looks "configured" by slot count alone but has zero actual training capacity. The DESCRIPTION
 * still shows the slot count (`timeSlotsCount`) - "3 tider" - since that's still the honest, useful
 * live number for this step; only the checkmark's meaning changed.
 */
function tiderCompletion(timeSlotsCount: number | undefined, activeCourtsCount: number | undefined): StepCompletion {
  if (timeSlotsCount === undefined) {
    return { completed: undefined, description: undefined };
  }
  return {
    completed: activeCourtsCount === undefined ? undefined : activeCourtsCount > 0,
    description: pluralize(timeSlotsCount, "tid", "tider"),
  };
}

/**
 * v0.6.0 final pre-release fix round (FIX 1, MAJOR): completion now gates on
 * {@link StepCompletionInput.hasUsableResult} - a run that's still solving, failed outright, or was
 * cancelled with nothing to show isn't "done" just because it exists (replaces audit-fix A8's
 * stricter `latestRunFinished`, which also painted a usable CANCELLED-with-partial-progress run as
 * incomplete - see runStatus.ts). The DESCRIPTION still shows the total run count ("2 körningar") -
 * that's still honest, useful information; only the checkmark's meaning changed.
 */
function optimeraCompletion(
  optimizationRunsCount: number | undefined,
  hasUsableResult: boolean | undefined,
): StepCompletion {
  if (optimizationRunsCount === undefined) {
    return { completed: undefined, description: undefined };
  }
  return {
    completed: hasUsableResult === true,
    description: pluralize(optimizationRunsCount, "körning", "körningar"),
  };
}

/**
 * v0.6.0 audit-fix A8: Resultat used to be permanently un-checked ("no cheap distinct-from-Optimera
 * signal without an extra backend call") - the audit called out that a permanently grey "5" reads as
 * an unexplained accusation to a non-technical admin. It CAN now check: it reuses the same
 * `hasUsableResult` signal Optimera does (no extra backend call - Optimera's own
 * `useOptimizationRuns` query already carries the `resultSummaryJson` this reads too).
 *
 * v0.6.0 final pre-release fix round (FIX 1, MAJOR, Opus m5 - "a green check must not contradict the
 * yellow waitlist outcome"): ADDITIONALLY requires {@link StepCompletionInput.resultatAllPlaced} -
 * `hasUsableResult` alone would happily checkmark Resultat for a run that left participants on the
 * waitlist, directly contradicting OptimizePanelSimple's own yellow "N deltagare kunde inte
 * placeras" outcome for that exact same run. The description stays the static fallback
 * (sv.simple.stepDescriptions.resultat) - there's no cheap distinct live number to show here, only
 * the checkmark's gate changed.
 */
function resultatCompletion(hasUsableResult: boolean | undefined, resultatAllPlaced: boolean | undefined): StepCompletion {
  const completed = hasUsableResult === undefined ? undefined : hasUsableResult && resultatAllPlaced === true;
  return { completed, description: sv.simple.stepDescriptions.resultat };
}

/**
 * v0.6.0 F3 (M-S3), review fix FIX 4 (MAJOR): unlike the live-COUNT steps above (which are
 * "not completed" until a count is actually positive), every plan is seeded with a default
 * priority order the moment the query resolves - so "the GET resolved" is never by itself evidence
 * the admin has actually engaged with this screen. `completed` therefore gates on
 * `updatedAt !== null` (api/priorityOrder.ts's doc comment: `null` until the order has ever been
 * explicitly PUT, either via a reorder or the "Återställ till prioriteringsordning" reset flow) -
 * the same bar this milestone's other completion signals use ("has the admin actually done
 * something here", not "did a query resolve").
 *
 * The DESCRIPTION is deliberately unconditional on that gate: it always shows the live current
 * state (top priority, or "Anpassade vikter" once advanced-mode weight edits have moved the plan
 * off the order-driven ladder - see PriorityOrderView.customWeightsActive's own doc comment) the
 * moment the query has resolved, regardless of whether a save has ever happened - an un-saved
 * default order is still real, current information worth showing, even without a checkmark next to
 * it.
 */
function priorityCompletion(input: PriorityOrderCompletionInput | undefined): StepCompletion {
  if (input === undefined) {
    return { completed: undefined, description: sv.simple.stepDescriptions.prioriteringar };
  }
  return {
    completed: input.updatedAt !== null,
    description: input.customWeightsActive
      ? sv.simple.stepDescriptions.prioritiesCustomWeights
      : sv.simple.stepDescriptions.prioritiesTopPriority(input.topPriorityLabelSv),
  };
}

/**
 * Pure derivation of each step's `{completed, description}` from already-available data - no
 * fetching happens here (see {@link StepCompletionInput}'s doc comment). Returned array is aligned
 * index-for-index with {@link SIMPLE_STEPS}.
 */
export function completionFor(input: StepCompletionInput): StepCompletion[] {
  return [
    countCompletion(input.participantsCount, "deltagare", "deltagare"),
    tiderCompletion(input.timeSlotsCount, input.activeCourtsCount),
    priorityCompletion(input.priorityOrder),
    optimeraCompletion(input.optimizationRunsCount, input.hasUsableResult),
    resultatCompletion(input.hasUsableResult, input.resultatAllPlaced),
    countCompletion(input.savedPlansCount, "sparad plan", "sparade planer"),
  ];
}
