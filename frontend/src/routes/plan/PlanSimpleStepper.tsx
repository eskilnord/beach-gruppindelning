import type { CSSProperties, ReactNode } from "react";
import { Stepper, type MantineColor } from "@mantine/core";
import { IconCheck } from "@tabler/icons-react";
import { useLocation, useNavigate } from "react-router-dom";
import { useParticipants } from "../../api/participants";
import { usePriorityOrder } from "../../api/priorityOrder";
import { useTrainingBlocksForPlan } from "../../api/trainingBlocks";
import { useOptimizationRuns } from "../../api/runs";
import { useSavedPlans } from "../../api/savedPlans";
import { sv } from "../../i18n/sv";
import { completionFor, resolveSimpleStepIndex, SIMPLE_STEPS } from "./planSimpleSteps";

interface PlanSimpleStepperProps {
  planId: string;
}

/**
 * v0.6.0 F2 (M-S2): the SIMPLE-mode replacement for PlanLayout's 9-tab bar - six steps
 * (planSimpleSteps.ts's SIMPLE_STEPS), each showing a live-numbers description ("260 deltagare")
 * where a cheap signal exists, or a static fallback otherwise (planSimpleSteps.ts's completionFor -
 * v0.6.0 F2 review fix, FIX 8). Derived from a handful of already-existing, cheap GETs - the same
 * endpoints ParticipantsPanel/ResourcesPanel/OptimizePanel already call elsewhere; nothing new is
 * invented here. React Query keys these queries identically to those other call sites - e.g.
 * `useTrainingBlocksForPlan(planId)` here is the exact same cache entry ResourcesPanel.tsx keeps
 * warm, not a second one - so mounting this component adds no extra network round-trip beyond
 * whatever isn't already cached (identical keys share ONE cache entry; they don't each get their
 * own).
 *
 * v0.6.0 audit-fix A8: switched from `useTimeSlots` to `useTrainingBlocksForPlan` - the Tider step's
 * checkmark needs the ACTIVE COURT count (real training capacity), not just how many time slots are
 * configured (see planSimpleSteps.ts's `tiderCompletion`), and the grouped training-blocks view is
 * the cheapest source that carries both numbers at once. `latestRunFinished` (derived from
 * `useOptimizationRuns`'s already-most-recent-first list, `data[0]`) now also drives BOTH Optimera's
 * and Resultat's checkmarks - see planSimpleSteps.ts's doc comments on why "a run exists" isn't the
 * same as "the run finished".
 *
 * `allowNextStepsSelect` (Mantine default: true, set explicitly for clarity) - an admin may jump
 * straight to any step regardless of what's "completed"; the checkmarks are guidance, never a gate.
 *
 * Wrapped in a `<nav>` landmark (v0.6.0 F2 review fix, FIX 4) with `aria-current="step"` on the
 * active `Stepper.Step`, since Mantine's Stepper renders a plain, unlabelled row of buttons with no
 * navigation semantics of its own.
 */
export function PlanSimpleStepper({ planId }: PlanSimpleStepperProps) {
  const navigate = useNavigate();
  const location = useLocation();

  const participants = useParticipants(planId);
  const trainingBlocks = useTrainingBlocksForPlan(planId);
  const runs = useOptimizationRuns(planId);
  const priorityOrder = usePriorityOrder(planId);
  // v0.6.0 F6 (M-S6): restored (F2 review fix FIX 3's own TODO) now that SimpleSaveExportCard
  // actually renders on the export route - same already-warm cache entry SimpleSaveExportCard and
  // SavedPlansPanel both key on, not a second query.
  const savedPlans = useSavedPlans(planId);

  const timeSlotsCount = trainingBlocks.data?.length;
  const activeCourtsCount = trainingBlocks.data?.reduce(
    (sum, entry) => sum + entry.blocks.filter((block) => block.active).length,
    0,
  );
  // v0.6.0 audit-fix A8: `runs` is most-recent-first (useOptimizationRuns's own doc comment), so
  // `data[0]` is the latest run. `data` resolved but empty (`[]`) correctly yields `false` here
  // (loaded, nothing has finished) - distinct from `undefined` (still loading/erroring).
  const latestRunFinished = runs.data ? runs.data[0]?.status === "FINISHED" : undefined;

  // v0.6.0 F2 review fix (FIX 9): a failed query renders exactly like a still-loading one here
  // (`.data` stays undefined either way, so completionFor sees the same "no signal") - by design.
  // This is a soft guidance signal shown alongside a Stepper the admin can already click through
  // freely; it isn't worth a distinct error state.
  const completions = completionFor({
    participantsCount: participants.data?.length,
    timeSlotsCount,
    activeCourtsCount,
    optimizationRunsCount: runs.data?.length,
    latestRunFinished,
    // v0.6.0 F3 (M-S3): reduced to just what completionFor needs - the top-ranked priority's
    // backend-supplied labelSv (rank 1 in the `priorities` array, which is index-aligned with
    // `order`) plus customWeightsActive. `order[0]` (not a `.find(rank === 1)`) since that's the
    // exact same "current top priority" PrioritiesPanel.tsx itself renders first.
    priorityOrder: priorityOrder.data
      ? {
          customWeightsActive: priorityOrder.data.customWeightsActive,
          topPriorityLabelSv:
            priorityOrder.data.priorities.find((row) => row.key === priorityOrder.data!.order[0])?.labelSv ?? "",
          // v0.6.0 F3 review fix (FIX 4): drives priorityCompletion's checkmark gate - see
          // planSimpleSteps.ts's doc comment.
          updatedAt: priorityOrder.data.updatedAt,
        }
      : undefined,
    savedPlansCount: savedPlans.data?.length,
  });

  const active = resolveSimpleStepIndex(location.pathname);

  return (
    <nav aria-label={sv.simple.stepperNavLabel}>
      <Stepper
        active={active}
        onStepClick={(index) => navigate(`/plans/${planId}/${SIMPLE_STEPS[index].path}`)}
        allowNextStepsSelect
        size="sm"
        iconSize={32}
        data-testid="plan-simple-stepper"
      >
        {SIMPLE_STEPS.map((step, index) => {
          const visual = stepVisual(completions[index].completed, index, active);
          return (
            <Stepper.Step
              key={step.path}
              label={sv.simple.steps[step.labelKey]}
              description={completions[index].description}
              data-testid={step.testId}
              aria-current={index === active ? "step" : undefined}
              icon={visual.icon}
              progressIcon={visual.progressIcon}
              completedIcon={visual.completedIcon}
              color={visual.color}
              styles={visual.iconStyle ? { stepIcon: visual.iconStyle } : undefined}
            />
          );
        })}
      </Stepper>
    </nav>
  );
}

interface StepVisual {
  icon: ReactNode;
  progressIcon: ReactNode;
  completedIcon: ReactNode;
  color: MantineColor | undefined;
  /** v0.6.0 audit-fix A8: override for the "visited but not confirmed done" case - see this
   *  function's own doc comment below. */
  iconStyle: CSSProperties | undefined;
}

/**
 * Per-step icon/color override (v0.6.0 F2 review fix, FIX 1 - `StepCompletion.completed` used to be
 * computed by completionFor and then never read anywhere, dead code: Mantine's Stepper derives its
 * own `state` ('stepInactive' | 'stepProgress' | 'stepCompleted') purely from `active > index`, so
 * jumping straight to the last step checkmarked every earlier one regardless of whether it actually
 * had any data).
 *
 * Mantine has no prop to change WHEN a step counts as "stepCompleted" - only WHICH icon renders once
 * it decides that (`icon`/`progressIcon` for the non-completed states, `completedIcon` for
 * 'stepCompleted' - see @mantine/core's StepperStep.mjs: the completed slot is wrapped in a
 * `<Transition mounted={state === "stepCompleted"}>`, driven purely by position). So instead of
 * fighting that, this makes the RENDERED icon track `completed` rather than raw position, on the
 * chosen convention "check = data says done; number = not done":
 *  - `completed === true`           -> a checkmark, in every state (even a step Mantine's own
 *                                       position-only logic hasn't marked "passed" yet).
 *  - `completed !== true` (`false`
 *    or `undefined` - no confirmed
 *    signal either way)             -> the step's own number, even once Mantine's position-only state
 *                                       would otherwise force a checkmark ('stepCompleted').
 *
 * v0.6.0 audit-fix A8 (walkthrough finding: a visited-but-incomplete step's near-black filled circle
 * read as an accusation, not neutral guidance): that "position says done, data doesn't" case now
 * ALSO gets an outline treatment via `iconStyle` (transparent fill, gray border/text) instead of
 * Mantine's default solid `stepCompleted` fill - a demoted step now reads as a calm, neutral outline
 * rather than a differently-numbered badge still painted in the same solid "completed" style.
 */
function stepVisual(completed: boolean | undefined, index: number, active: number): StepVisual {
  const check = <IconCheck size={16} />;
  const dataConfirmsDone = completed === true;
  const positionSaysDone = active > index;
  const visitedIncomplete = positionSaysDone && !dataConfirmsDone;
  return {
    icon: dataConfirmsDone ? check : undefined,
    progressIcon: dataConfirmsDone ? check : undefined,
    completedIcon: dataConfirmsDone ? check : index + 1,
    color: visitedIncomplete ? "gray" : undefined,
    iconStyle: visitedIncomplete
      ? {
          backgroundColor: "transparent",
          borderColor: "var(--mantine-color-gray-5)",
          color: "var(--mantine-color-gray-6)",
        }
      : undefined,
  };
}
