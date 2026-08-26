import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { Alert, Anchor, Button, Card, Group, List, Loader, Modal, Progress, Stack, Text, Title, Tooltip } from "@mantine/core";
import { IconAlertCircle, IconCircleCheck } from "@tabler/icons-react";
import { notifications } from "@mantine/notifications";
import { ApiError } from "../../../api/client";
import { useGenerateGroups, useGroups, useGroupSyncStatus } from "../../../api/groups";
import { useParticipants } from "../../../api/participants";
import { usePriorityOrderStatus } from "../../../api/priorityOrder";
import { runsKey, useOptimizationRuns } from "../../../api/runs";
import {
  invalidateResultQueries,
  isSolveRunning,
  useCancelSolve,
  useLiveSolution,
  useSolveStatus,
  useStartSolve,
  useSuggestDuration,
} from "../../../api/solve";
import { useTrainingBlocksForPlan } from "../../../api/trainingBlocks";
import { sv } from "../../../i18n/sv";
import { formatDateTime } from "../../../lib/formatDateTime";
import { LiveSolveView } from "./LiveSolveView";
import { parseResultSummary } from "./runSummary";
import { buildSimpleSolveRequest } from "./simpleSolveRequest";

interface ReadinessItemProps {
  ready: boolean;
  loading: boolean;
  label: string;
  /** Shown instead of `label` on the clickable not-ready Anchor - a plain imperative CTA ("Lägg till
   *  deltagare") rather than the descriptive "0 deltagare", which reads like a stat, not an action.
   *  Falls back to `label` when omitted. */
  missingLabel?: string;
  onGo: () => void;
  testId: string;
}

/** One row of the readiness checklist below - a plain line once ready (or still loading, so nothing
 *  flashes as "missing" before its query even resolves), a clickable link to the relevant step
 *  otherwise. Loading and ready share the same non-alarming rendering; only a confirmed gap gets the
 *  orange icon + link. */
function ReadinessItem({ ready, loading, label, missingLabel, onGo, testId }: ReadinessItemProps) {
  const icon = loading ? (
    <Loader size={14} />
  ) : ready ? (
    <IconCircleCheck size={16} color="var(--mantine-color-green-6)" />
  ) : (
    <IconAlertCircle size={16} color="var(--mantine-color-orange-6)" />
  );
  return (
    <List.Item icon={icon} data-testid={testId}>
      {ready || loading ? (
        <Text size="sm">{label}</Text>
      ) : (
        <Anchor size="sm" component="button" type="button" onClick={onGo}>
          {missingLabel ?? label}
        </Anchor>
      )}
    </List.Item>
  );
}

/**
 * v0.6.0 F4 (M-S4): the SIMPLE-mode "Skapa grupper" screen - OptimizeRoute.tsx's SIMPLE branch,
 * replacing OptimizePanel's full advanced surface (profiles, weights accordion, optimize-only/
 * blocking checkboxes, Analys - none of that renders here at all). One readiness checklist, one
 * primary button, and the same progress/LiveSolveView machinery OptimizePanel uses while a solve
 * runs, with the raw score jargon replaced by calm copy.
 *
 * The button's own click handler folds TWO steps together (auto-generate groups when needed, then
 * start the solve) so the admin never has to know "Skapa grupper" is really two backend calls - see
 * simpleSolveRequest.ts for the exact (pinned, parity-guarded) request body submitted.
 */
export function OptimizePanelSimple() {
  const { planId } = useParams<{ planId: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const participants = useParticipants(planId);
  const blocksByPlan = useTrainingBlocksForPlan(planId);
  const priorityStatus = usePriorityOrderStatus(planId);
  const groups = useGroups(planId);
  const syncStatus = useGroupSyncStatus(planId);
  const generateGroups = useGenerateGroups(planId ?? "");
  const solveStatus = useSolveStatus(planId);
  const runs = useOptimizationRuns(planId);
  const startSolve = useStartSolve(planId ?? "");
  const cancelSolve = useCancelSolve(planId ?? "");

  const status = solveStatus.data;
  const running = isSolveRunning(status?.status);
  // Never gates the button on this resolving (F4 hard rule) - see the duration hint below, which
  // falls back to a fixed 60s estimate while it's loading.
  const suggestion = useSuggestDuration(planId, { enabled: !running });
  const liveSolution = useLiveSolution(planId, running);

  const [starting, setStarting] = useState(false);
  // v0.6.0 audit fix C2 (walkthrough-proven "outcome invisibility"): shown BEFORE handleCreateGroups
  // actually fires, only when there's a previous run AND the groups are stale - see the button's
  // onClick below.
  const [confirmRerunOpen, setConfirmRerunOpen] = useState(false);

  // v0.6.0 F4 review fix (FIX 1, minor "lastRun framing"): true once THIS mount has actually kicked
  // off a solve - drives whether the outcome card below leads with "Senast körd: …" (a run from a
  // previous session/reload, which needs that framing so it doesn't read as "just now") or trails
  // with it (a run this mount just produced, where the immediate result is the headline).
  const startedThisSessionRef = useRef(false);

  // Mirrors OptimizePanel.tsx's own effect: an async CUSTOM solve's start-mutation only confirms the
  // solve WAS STARTED, not that it finished - detect the SOLVING_* -> settled transition (via
  // useSolveStatus's 1s poll) to refresh the run history + Resultatvy-facing queries the moment a
  // result is actually persisted.
  const previousStatusRef = useRef<string | undefined>(undefined);
  useEffect(() => {
    if (!planId) {
      return;
    }
    const current = status?.status;
    if (isSolveRunning(previousStatusRef.current) && !isSolveRunning(current)) {
      void queryClient.invalidateQueries({ queryKey: runsKey(planId) });
      invalidateResultQueries(queryClient, planId);
    }
    previousStatusRef.current = current;
  }, [status?.status, planId, queryClient]);

  // v0.6.0 audit fix C2 (walkthrough-proven "outcome invisibility + re-run trap"): scrolls the
  // outcome card into view exactly ONCE per settled run, the moment `running` flips false and a run
  // is present (every settled run - failed/cancelled/infeasible/waitlisted/success - gets an
  // outcomeColor, so this condition is equivalent to "the outcome card is about to render"). Keyed
  // on the run's own id (not a plain boolean) so a brand-new run settling later scrolls again, but a
  // re-render of an ALREADY-scrolled-to run never does.
  const settledRunId = !running ? runs.data?.[0]?.id : undefined;
  const scrolledRunIdRef = useRef<string | null>(null);
  const outcomeCardRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (settledRunId && scrolledRunIdRef.current !== settledRunId) {
      scrolledRunIdRef.current = settledRunId;
      // v0.6.0 final pre-release fix round (FIX 3, MAJOR): was `block: "nearest"`, which - once the
      // card is already close to the bottom of the viewport (the common case, since it renders near
      // the end of the page) - bottom-aligns the card EXACTLY behind PlanSimpleStepFooter's 60px
      // sticky footer, hiding the very outcome text this scroll exists to surface. `block: "center"`
      // plus the Card's own `scrollMarginBottom` below (belt and braces - covers browsers/cases where
      // "center" alone still lands the bottom edge under the footer for a short card near the page end).
      outcomeCardRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
    }
  }, [settledRunId]);

  if (!planId) {
    return null;
  }

  const participantsCount = participants.data?.length ?? 0;
  const participantsReady = participantsCount > 0;

  const slotsCount = blocksByPlan.data?.length ?? 0;
  // v0.6.0 F4 review fix (FIX 3): count only ACTIVE blocks - matches ResourcesPanel's own
  // per-slot activeCount (a manually-deactivated court, spec §12.3, isn't actually usable capacity).
  const courtsCount = (blocksByPlan.data ?? []).reduce(
    (sum, entry) => sum + entry.blocks.filter((block) => block.active).length,
    0,
  );
  const resourcesReady = slotsCount > 0 && courtsCount > 0;

  // v0.6.0 F4 review fix (FIX 8, decided): the "Prioriteringar satta" readiness row is dropped
  // entirely. No real per-plan "priorities set" signal exists yet (Prioriteringar is still a
  // placeholder route - PrioritiesPanel.tsx, F3): the backend always seeds every plan with its
  // default constraint weights, so a row derived from "the weights query has loaded" was always
  // true and never actually reflected anything the admin did - a fake readiness signal is worse than
  // no signal at all.
  // TODO (F3): reintroduce a "Prioriteringar satta" readiness row once F3 ships a real per-plan
  // priority-order signal to drive it (see usePriorityOrderStatus's own doc comment for the
  // anticipated endpoint).

  // v0.6.0 F4 review fix (FIX 7): any prerequisite query erroring must never be misread as "the plan
  // is empty" (e.g. "0 deltagare") - surfaced as its own error state below instead, with the
  // checklist suppressed entirely while it's showing.
  const anyError = participants.isError || blocksByPlan.isError || groups.isError || syncStatus.isError;

  const anyLoading = participants.isLoading || blocksByPlan.isLoading;
  // v0.6.0 F4 review fix (FIX 7): groups/syncStatus must have actually resolved before the button can
  // be trusted - without this, a slow-loading groups/sync-status query lets the admin click through
  // while handleCreateGroups's own `groups.data?.length ?? 0` / `syncStatus.data?.stale` reads are
  // still `undefined`, which can either skip a needed "generate groups" call (the skip-generation
  // race) or spuriously re-generate against stale/absent sync data.
  const allReady =
    !anyLoading && !anyError && participantsReady && resourcesReady && groups.isSuccess && syncStatus.isSuccess;

  const suggestedSeconds = suggestion.data?.suggestedSeconds;

  const creating = generateGroups.isPending || startSolve.isPending || starting;
  const buttonDisabled = !allReady || creating || running;

  const handleRetry = () => {
    void participants.refetch();
    void blocksByPlan.refetch();
    void groups.refetch();
    void syncStatus.refetch();
  };

  const handleCreateGroups = async () => {
    setStarting(true);
    try {
      if ((groups.data?.length ?? 0) === 0 || syncStatus.data?.stale) {
        try {
          await generateGroups.mutateAsync();
        } catch (error) {
          notifications.show({
            color: "red",
            title: sv.common.error,
            message: error instanceof ApiError ? error.message : sv.simple.optimize.generateGroupsFailed,
          });
          return;
        }
      }
      try {
        const body = buildSimpleSolveRequest(suggestedSeconds ?? 60);
        startedThisSessionRef.current = true;
        // v0.6.0 F4 review fix (minor): mirrors the advanced panel's own handleStart onSuccess - the
        // SOLVING_* -> settled effect above still catches a slower solve's eventual completion, but a
        // solve that starts AND settles inside a single 1s status-poll interval (e.g. a very small
        // plan) would otherwise never observe the running->settled edge at all, leaving runs/groups/
        // assignments stale until an unrelated refetch.
        await startSolve.mutateAsync(body, {
          onSuccess: () => {
            void queryClient.invalidateQueries({ queryKey: runsKey(planId) });
            invalidateResultQueries(queryClient, planId);
          },
        });
      } catch (error) {
        notifications.show({
          color: "red",
          title: sv.common.error,
          message: error instanceof ApiError ? error.message : sv.simple.optimize.startFailed,
        });
      }
    } finally {
      setStarting(false);
    }
  };

  const handleCancel = () => {
    cancelSolve.mutate(undefined, {
      onError: (error) => {
        notifications.show({
          color: "red",
          title: sv.common.error,
          message: error instanceof ApiError ? error.message : sv.optimize.cancelFailed,
        });
      },
    });
  };

  const latestRun = runs.data?.[0];
  const latestSummary = latestRun ? parseResultSummary(latestRun) : null;

  // v0.6.0 audit fix C2: the button's own onClick - only interposes the confirm modal when there IS
  // a previous run (a re-run) AND the groups are actually stale (re-running would regenerate/discard
  // manual placements); a first-time "Skapa grupper" click on an empty plan never shows this modal.
  const handleButtonClick = () => {
    if (latestRun && !running && syncStatus.data?.stale) {
      setConfirmRerunOpen(true);
      return;
    }
    void handleCreateGroups();
  };

  const handleConfirmRerun = () => {
    setConfirmRerunOpen(false);
    void handleCreateGroups();
  };

  // v0.6.0 F4 review fix (FIX 1, BLOCKER): honest post-solve outcomes, gated on a run actually
  // existing (not on a parsed summary, which is null for e.g. a FAILED run that never reached
  // finishRun) and switched on the run's terminal state - a solve that failed or was cancelled must
  // never be painted with the same green "Klart!" success alert a genuinely feasible result gets.
  //
  // v0.6.0 audit fix C1 (BLOCKER, "the green lie about waitlisted kids"): a feasible, zero-hard-
  // violation run that STILL left participants unassigned (`unassignedCount > 0`) is no longer
  // painted green - that used to be indistinguishable from full success, which is exactly the "why
  // did my kid end up on the waitlist and nobody told me" gap the persona audit flagged. Green is now
  // reserved for the case where every participant genuinely got placed.
  let outcomeColor: "red" | "gray" | "yellow" | "green" | null = null;
  let outcomeText = "";
  let outcomeShowViewGroups = false;
  if (!running && latestRun) {
    const failed = latestRun.status === "FAILED" || !latestSummary;
    const cancelled = !failed && latestRun.status === "CANCELLED";
    const infeasible = !failed && !cancelled && (!latestSummary!.feasible || latestSummary!.hard !== 0);
    const unassignedCount = latestSummary?.unassignedCount ?? 0;
    const waitlisted = !failed && !cancelled && !infeasible && unassignedCount > 0;
    if (failed) {
      outcomeColor = "red";
      outcomeText = sv.simple.optimize.failedAlert;
      outcomeShowViewGroups = false;
    } else if (cancelled) {
      outcomeColor = "gray";
      outcomeText = sv.simple.optimize.cancelledAlert;
      outcomeShowViewGroups = true;
    } else if (infeasible) {
      outcomeColor = "yellow";
      outcomeText = sv.simple.optimize.infeasibleAlert;
      outcomeShowViewGroups = true;
    } else if (waitlisted) {
      outcomeColor = "yellow";
      outcomeText = sv.simple.optimize.waitlistAlert(unassignedCount);
      outcomeShowViewGroups = true;
    } else {
      outcomeColor = "green";
      // N is participants actually placed: the plan's current participant count minus anyone
      // waitlisted by this run (0 here, since the `waitlisted` branch above already caught
      // unassignedCount > 0) - never the group count, which says nothing about whether every
      // participant got a seat.
      outcomeText = sv.simple.optimize.successAlert(Math.max(0, participantsCount - unassignedCount));
      outcomeShowViewGroups = true;
    }
  }

  const lastRunWhenText = latestRun ? sv.simple.optimize.lastRunWhen(formatDateTime(latestRun.startedAt)) : "";

  return (
    <Stack gap="md">
      <Card withBorder padding="lg">
        <Title order={4} mb={4}>
          {sv.simple.optimize.heading}
        </Title>
        <Text c="dimmed" size="sm" mb="md">
          {sv.simple.optimize.intro}
        </Text>

        {/* v0.6.0 audit fix C4: passive/informational (no click needed to see it) - distinct from the
            C2 confirmRerun modal, which only appears actively, gated on a "Kör igen" click. */}
        {!running && latestRun && syncStatus.data?.stale && (
          <Alert color="yellow" mb="md" data-testid="simple-optimize-stale-banner">
            {sv.simple.optimize.staleBanner}
          </Alert>
        )}

        {anyError ? (
          <Alert color="red" data-testid="simple-optimize-load-error">
            <Text size="sm" mb="xs">
              {sv.simple.optimize.loadFailed}
            </Text>
            <Button size="xs" variant="light" color="red" onClick={handleRetry}>
              {sv.simple.optimize.retryButton}
            </Button>
          </Alert>
        ) : (
          <>
            {/* v0.6.0 F4 review fix (minor): the dead `readinessHeading` copy key, now actually
                rendered above the checklist it names. */}
            <Text fw={600} size="sm" mb={4} data-testid="simple-optimize-readiness-heading">
              {sv.simple.optimize.readinessHeading}
            </Text>
            <List spacing="xs" size="sm" mb="md" data-testid="simple-optimize-readiness">
              <ReadinessItem
                testId="simple-optimize-readiness-participants"
                ready={participantsReady}
                loading={participants.isLoading}
                label={sv.simple.optimize.readiness.participants(participantsCount)}
                missingLabel={sv.simple.optimize.missingLabel.participants}
                onGo={() => navigate(`/plans/${planId}/deltagare`)}
              />
              <ReadinessItem
                testId="simple-optimize-readiness-resources"
                ready={resourcesReady}
                loading={blocksByPlan.isLoading}
                label={sv.simple.optimize.readiness.resources(slotsCount, courtsCount)}
                // v0.6.0 audit fix C4: two distinct not-ready causes, not one generic CTA - zero
                // training slots at all needs "Lägg till träningstider"; slots existing but zero
                // ACTIVE courts within them needs "Ange antal banor" instead.
                missingLabel={
                  slotsCount === 0 ? sv.simple.optimize.missingLabel.resourcesNoSlots : sv.simple.optimize.missingLabel.resourcesNoCourts
                }
                onGo={() => navigate(`/plans/${planId}/resurser`)}
              />
            </List>

            {priorityStatus.data?.customWeightsActive && (
              <Text size="xs" c="dimmed" mb="sm" data-testid="simple-optimize-custom-weights-hint">
                {sv.simple.optimize.customWeightsHint}
              </Text>
            )}

            {/* v0.6.0 audit fix C4: while prerequisite queries are still loading, the tooltip must not
                claim anything is "missing" yet (nothing has been confirmed missing - it just hasn't
                loaded) - "Läser in…" instead, the real missing-things text only once loaded. */}
            <Tooltip label={anyLoading ? sv.simple.optimize.notReadyTooltipLoading : sv.simple.optimize.notReadyTooltip} disabled={allReady}>
              <div style={{ display: "inline-block" }}>
                <Button
                  size="lg"
                  data-testid="simple-optimize-button"
                  loading={creating}
                  disabled={buttonDisabled}
                  onClick={handleButtonClick}
                >
                  {/* v0.6.0 audit fix C2: relabels to "Kör igen" once a previous run already exists and
                      nothing is currently running - a re-run reads very differently from the first
                      "Skapa grupper" click. */}
                  {latestRun && !running ? sv.simple.optimize.rerunButton : sv.simple.optimize.createButton}
                </Button>
              </div>
            </Tooltip>

            <Text size="sm" c="dimmed" mt="xs" data-testid="simple-optimize-duration-hint">
              {/* v0.6.0 F4 review fix (minor): `isFetching` (not `isPending`) - `isPending` stays true
                  forever while the query is disabled (e.g. `running`) and has never fetched, which
                  froze this on "Beräknar…" instead of falling back to the fixed-estimate copy. */}
              {suggestion.isFetching ? sv.simple.optimize.durationCalculating : sv.simple.optimize.durationEstimate(suggestedSeconds ?? 60)}
            </Text>
          </>
        )}
      </Card>

      {running && (
        <Card withBorder padding="md" data-testid="solve-progress">
          <Group justify="space-between" mb="xs">
            {/* v0.6.0 audit fix C3: SIMPLE-scoped heading override ("Skapar grupper…") - the shared
                sv.optimize.progress.heading advanced string is untouched (OptimizePanel.tsx still
                uses it verbatim). */}
            <Text fw={600}>{sv.simple.optimize.progressHeading}</Text>
            <Button size="xs" color="red" variant="outline" loading={cancelSolve.isPending} onClick={handleCancel}>
              {sv.optimize.cancelButton}
            </Button>
          </Group>
          <Progress
            value={status?.limitMs ? Math.min(100, ((status.elapsedMs ?? 0) / status.limitMs) * 100) : 0}
            animated
            mb="xs"
          />
          {status?.limitMs != null && status?.elapsedMs != null && (
            <Text size="sm" c="dimmed" mb={4}>
              {sv.optimize.progress.elapsed(Math.round(status.elapsedMs / 1000), Math.round(status.limitMs / 1000))}
            </Text>
          )}
          {/* v0.6.0 F4: calm replacement for the advanced progress card's score/waitlist/improvement
              jargon (formatScoreLine, improvementCount) - see LiveSolveView.tsx's own score line,
              wrapped <AdvancedOnly> for the same reason. */}
          <Text c="dimmed" data-testid="simple-optimize-working">
            {sv.simple.optimize.working}
          </Text>
          {/* v0.6.0 audit fix C3: makes explicit that cancelling doesn't throw away work already done. */}
          <Text size="xs" c="dimmed" mt={4} data-testid="simple-optimize-cancel-hint">
            {sv.simple.optimize.cancelHint}
          </Text>
        </Card>
      )}

      {/* v0.6.0 audit fix C2: moved ABOVE LiveSolveView (was below it) - the outcome is the headline
          the admin came back for; the live grid is supporting detail. `aria-live="polite"` + the
          scroll-into-view effect above (`outcomeCardRef`/`settledRunId`) mean a settled result is
          actually noticed, not silently rendered off-screen below a still-visible progress card. */}
      {!running && latestRun && outcomeColor && (
        <Card
          withBorder
          padding="md"
          data-testid="simple-optimize-result"
          ref={outcomeCardRef}
          aria-live="polite"
          // v0.6.0 final pre-release fix round (FIX 3, MAJOR): belt-and-braces alongside the
          // `block: "center"` scroll-into-view above - reserves 84px (the 60px sticky footer plus a
          // small margin) below the card so even a `scrollIntoView` that lands the card's bottom edge
          // right at the viewport edge still clears the footer instead of tucking the outcome text
          // behind it.
          style={{ scrollMarginBottom: 84 }}
        >
          {!startedThisSessionRef.current && (
            <Text size="sm" c="dimmed" mb="xs" data-testid="simple-optimize-lastrun-lead">
              {lastRunWhenText}
            </Text>
          )}
          <Alert color={outcomeColor} data-testid="simple-optimize-outcome">
            {outcomeText}
          </Alert>
          {/* v0.6.0 audit fix C2: a failed run must carry a PERSISTENT retry action here, not just the
              transient toast handleCreateGroups's catch already fires (kept - it's a fine immediate
              notice, but this is the one that's still there if the admin looks a minute later). */}
          {outcomeColor === "red" && (
            <Button
              mt="sm"
              color="red"
              variant="light"
              onClick={() => void handleCreateGroups()}
              data-testid="simple-optimize-retry-button"
            >
              {sv.simple.optimize.retryButton}
            </Button>
          )}
          {outcomeShowViewGroups && (
            <Button mt="sm" onClick={() => navigate(`/plans/${planId}/resultat`)} data-testid="simple-optimize-view-groups-button">
              {sv.simple.optimize.viewGroupsButton}
            </Button>
          )}
          {latestSummary?.unchangedFromPrevious && (
            // WI-C ("re-run doesn't feel like it re-runs" user feedback v0.4 #4): same note as the
            // advanced panel's own last-run card - explains a re-run that legitimately changed
            // nothing. The v0.2.0 coach-less `note` (advanced-only "last-run-note") is deliberately
            // NEVER rendered here - simple mode never mentions coach assignment at all.
            <Alert color="blue" mt="xs" data-testid="last-run-unchanged-note">
              {sv.optimize.lastRun.unchangedNote}
            </Alert>
          )}
          {startedThisSessionRef.current && (
            <Text size="sm" c="dimmed" mt="sm" data-testid="simple-optimize-lastrun-trail">
              {lastRunWhenText}
            </Text>
          )}
        </Card>
      )}

      {/* v0.6.0 audit fix C2: now rendered AFTER the outcome card (was before it) - see the comment
          above the outcome card. */}
      {liveSolution.data && <LiveSolveView planId={planId} snapshot={liveSolution.data} running={running} simple />}

      {/* v0.6.0 audit fix C2: gated on `latestRun && syncStatus.data?.stale` (via handleButtonClick) -
          only interposed before a RE-run that would regenerate/discard manual placements, never on
          the first "Skapa grupper" click. */}
      <Modal opened={confirmRerunOpen} onClose={() => setConfirmRerunOpen(false)} title={sv.simple.optimize.rerunButton} centered>
        <Text mb="lg">{sv.simple.optimize.confirmRerun.message}</Text>
        <Group justify="flex-end">
          <Button variant="default" onClick={() => setConfirmRerunOpen(false)}>
            {sv.common.cancel}
          </Button>
          <Button onClick={handleConfirmRerun} data-testid="simple-optimize-confirm-rerun-button">
            {sv.simple.optimize.confirmRerun.confirmLabel}
          </Button>
        </Group>
      </Modal>
    </Stack>
  );
}
