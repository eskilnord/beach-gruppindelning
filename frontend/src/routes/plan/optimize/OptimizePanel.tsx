import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import {
  Accordion,
  Alert,
  Anchor,
  Badge,
  Button,
  Card,
  Checkbox,
  Group,
  Loader,
  NumberInput,
  Progress,
  Radio,
  Stack,
  Table,
  Text,
  Title,
  Tooltip,
} from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { ApiError } from "../../../api/client";
import { useConstraintWeights } from "../../../api/constraintWeights";
import { useGenerateGroups, useGroups, useGroupSyncStatus } from "../../../api/groups";
import { usePlan } from "../../../api/plans";
import { runsKey, useOptimizationRuns } from "../../../api/runs";
import {
  invalidateResultQueries,
  isSolveRunning,
  useCancelSolve,
  useLiveSolution,
  useSolveStatus,
  useStartSolve,
} from "../../../api/solve";
import type { SolveProfile, SolveRequestBody } from "../../../api/types";
import { HelpTip } from "../../../components/HelpTip";
import { sv } from "../../../i18n/sv";
import { formatDateTime } from "../../../lib/formatDateTime";
import { effectiveGroupSizeDefaults } from "../../../lib/planDefaults";
import { useEditPlanModalStore } from "../editPlanModalStore";
import { LiveSolveView } from "./LiveSolveView";
import { PlanAnalysisSection } from "./PlanAnalysisSection";
import { formatScoreLine } from "./scoreFormat";
import { parseResultSummary, runDurationSeconds } from "./runSummary";
import { SuggestDurationCard } from "./SuggestDurationCard";

/** Avancerat-panel choices (v0.2.0): the three wall-clock presets + the GREEDY baseline + CUSTOM
 *  ("Egen tid") with its own seconds input. The suggestion-first primary flow (SuggestDurationCard)
 *  bypasses this list entirely and always submits CUSTOM with the suggested duration. */
const PROFILES: SolveProfile[] = ["FAST", "NORMAL", "THOROUGH", "GREEDY", "CUSTOM"];

/** Mirror of the backend's SolveProfile.CUSTOM_MIN/MAX_SECONDS (10..900, 400 outside) - used only to
 *  gate the Avancerat start button client-side; the backend remains the source of truth. */
const CUSTOM_MIN_SECONDS = 10;
const CUSTOM_MAX_SECONDS = 900;

/**
 * Optimeringsvy (spec §19.9, suggestion-first since v0.2.0): the SUGGESTED OPTIMIZATION TIME card
 * (fetched eagerly on tab open - SuggestDurationCard.tsx) is the primary flow, its
 * [Optimera (N s)]-button submitting `{profile:"CUSTOM", durationSeconds:N}`; the old presets
 * (Snabb/Normal/Grundlig/Greedy) plus a manual seconds input live on under an "Avancerat" collapse
 * with the original "Starta optimering" button. Also: a collapsible read-only constraint-weights
 * summary (reuses the M4 `constraint-weights` data, links to Fält for editing), §15.5 "Optimera
 * endast" checkboxes (they apply to BOTH start paths), start/cancel with a 1s-polling progress panel
 * (the backend's `limitMs` already reflects a CUSTOM duration, so the bar needs no special-casing),
 * and a persistent "Senaste körning" card that doubles as the completion result banner - see
 * `api/runs.ts`'s javadoc for why that reads `GET .../runs` rather than the (post-completion, empty)
 * `GET .../solve/status`. The run summary's v0.2.0 `note` (coach-less solve) renders as an info
 * Alert under the score banner.
 */
export function OptimizePanel() {
  const { planId } = useParams<{ planId: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const plan = usePlan(planId);
  const groups = useGroups(planId);
  const syncStatus = useGroupSyncStatus(planId);
  const generateGroups = useGenerateGroups(planId ?? "");
  const weights = useConstraintWeights(planId);
  const solveStatus = useSolveStatus(planId);
  const runs = useOptimizationRuns(planId);
  const startSolve = useStartSolve(planId ?? "");
  const cancelSolve = useCancelSolve(planId ?? "");

  const [profile, setProfile] = useState<SolveProfile>("NORMAL");
  const [customSeconds, setCustomSeconds] = useState<number | "">(60);
  const [optimizePlayers, setOptimizePlayers] = useState(true);
  const [optimizeSchedule, setOptimizeSchedule] = useState(true);
  const [optimizeCoaches, setOptimizeCoaches] = useState(true);
  // WI-C ("re-run doesn't feel like it re-runs" user feedback v0.4 #4, root cause B): "Börja om
  // från grunden" under Avancerat - see useStartSolve's SolveRequestBody.coldStart.
  const [coldStart, setColdStart] = useState(false);
  // Inline error surfaced on the staleness banner's own "Generera om grupper" button (e.g. a 409
  // when groups/assignments are locked) - separate from the shared toast handleGenerateGroups also
  // shows, so the reason stays visible on screen rather than only flashing as a notification.
  const [staleRegenerateError, setStaleRegenerateError] = useState<string | null>(null);

  // §14.4 cross-plan blocking checkboxes. MVP note ("bör minst stödja blockering av personer och
  // tränare utifrån överlappande tider") is honored by defaulting blockPlayers+blockCoaches to true
  // here - the backend itself defaults every flag to false when the request omits them entirely.
  const [blockPlayers, setBlockPlayers] = useState(true);
  const [blockCoaches, setBlockCoaches] = useState(true);
  const [blockCourts, setBlockCourts] = useState(false);
  const [conflictsAsWarnings, setConflictsAsWarnings] = useState(false);

  const status = solveStatus.data;
  const running = isSolveRunning(status?.status);
  // v0.3.0 WI-2 ("se det live"): polls only while `running` (never for the synchronous GREEDY
  // baseline, which never reaches SOLVING_ACTIVE/SCHEDULED) - see useLiveSolution's javadoc for why
  // the cached frame still renders (dimmed) after the solve settles, and useStartSolve's javadoc for
  // why it's guaranteed empty again at the start of the NEXT solve.
  const liveSolution = useLiveSolution(planId, running);

  // Detects the SOLVING_* -> settled transition (async FAST/NORMAL/THOROUGH profiles) to refresh the
  // run history + Resultatvy-facing queries the moment a result is actually persisted. GREEDY (fully
  // synchronous) is handled directly in the start-mutation's onSuccess below instead.
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

  if (!planId) {
    return null;
  }

  // Mirrors GroupGenerator's own fallback formula (lib/planDefaults.ts) so this hint always
  // describes what "Generera grupper" will actually use, whether or not the plan has explicit
  // defaults set yet.
  const groupSizeDefaults = effectiveGroupSizeDefaults(
    plan.data?.defaultGroupTargetSize,
    plan.data?.defaultGroupMinSize,
    plan.data?.defaultGroupMaxSize,
  );

  const handleGenerateGroups = () => {
    setStaleRegenerateError(null);
    generateGroups.mutate(undefined, {
      onSuccess: (data) => {
        notifications.show({ color: "green", message: sv.optimize.groups.generateSuccess(data.length) });
      },
      onError: (error) => {
        // WI-C: the staleness banner's own button reuses this same handler - a 409 (groups/
        // assignments locked, GroupGenerator#requireSafeToRegenerate) is shown INLINE on the banner
        // (not just as a toast) so the reason stays visible rather than only flashing briefly.
        const message = error instanceof ApiError ? error.message : sv.optimize.groups.generateFailed;
        setStaleRegenerateError(message);
        notifications.show({ color: "red", title: sv.common.error, message });
      },
    });
  };

  // Shared by both start paths (the suggestion card's primary [Optimera (N s)] and the Avancerat
  // panel's "Starta optimering"): one mutation, one error surface. A 409 (a solve already active -
  // e.g. a race with another window) arrives as an ApiError whose backend message is shown verbatim
  // in the toast; the buttons are additionally disabled while `running`, so the 409 path is a
  // race-window fallback, not the normal guard.
  const handleStart = (startProfile: SolveProfile, durationSeconds?: number) => {
    const body: SolveRequestBody = {
      profile: startProfile,
      durationSeconds,
      optimize: { players: optimizePlayers, schedule: optimizeSchedule, coaches: optimizeCoaches },
      blocking: { blockPlayers, blockCoaches, blockCourts, conflictsAsWarnings },
      coldStart,
    };
    startSolve.mutate(body, {
      onSuccess: () => {
        void queryClient.invalidateQueries({ queryKey: runsKey(planId) });
        invalidateResultQueries(queryClient, planId);
      },
      onError: (error) => {
        notifications.show({
          color: "red",
          title: sv.common.error,
          message: error instanceof ApiError ? error.message : sv.optimize.startFailed,
        });
      },
    });
  };

  const customSecondsValid =
    typeof customSeconds === "number" && customSeconds >= CUSTOM_MIN_SECONDS && customSeconds <= CUSTOM_MAX_SECONDS;
  const advancedStartDisabled = running || (profile === "CUSTOM" && !customSecondsValid);

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
  const latestDurationSeconds = latestRun ? runDurationSeconds(latestRun) : null;

  return (
    <Stack gap="md">
      <Card withBorder padding="lg">
        <Title order={4} mb="md">
          {sv.optimize.heading}
        </Title>

        <Group justify="space-between" mb="lg" data-testid="groups-summary">
          <div>
            <Group gap={4}>
              <Text fw={600}>{sv.optimize.groups.heading}</Text>
              <HelpTip label={sv.help.ariaLabel(sv.optimize.groups.heading)}>{sv.help.optimize.groupDefaults}</HelpTip>
            </Group>
            <Text size="sm" c="dimmed">
              {sv.optimize.groups.count(groups.data?.length ?? 0)}
            </Text>
            {plan.data && (
              <Text size="xs" c="dimmed" data-testid="group-defaults-summary">
                {sv.optimize.groups.defaultsSummary(
                  groupSizeDefaults.target,
                  groupSizeDefaults.min,
                  groupSizeDefaults.max,
                  plan.data.defaultLevelMin ?? null,
                )}{" "}
                <Anchor size="xs" component="button" type="button" onClick={() => useEditPlanModalStore.getState().open()}>
                  {sv.optimize.groups.changeDefaultsLink}
                </Anchor>
              </Text>
            )}
          </div>
          <Button variant="default" size="xs" loading={generateGroups.isPending} onClick={handleGenerateGroups}>
            {sv.optimize.groups.generateButton}
          </Button>
        </Group>

        <Accordion variant="separated" mb="lg">
          <Accordion.Item value="weights">
            <Accordion.Control>{sv.optimize.weightsSummary.heading}</Accordion.Control>
            <Accordion.Panel>
              <Text size="sm" c="dimmed" mb="sm">
                {sv.optimize.weightsSummary.subheading}
              </Text>
              {weights.isLoading && <Loader size="sm" />}
              {weights.isError && (
                <Alert color="red">
                  {weights.error instanceof ApiError ? weights.error.message : sv.optimize.weightsSummary.loadFailed}
                </Alert>
              )}
              {weights.data && (
                <Table.ScrollContainer minWidth={480}>
                  <Table verticalSpacing={4} withTableBorder striped highlightOnHover>
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>{sv.optimize.weightsSummary.table.label}</Table.Th>
                        <Table.Th>{sv.optimize.weightsSummary.table.hardOrSoft}</Table.Th>
                        <Table.Th>{sv.optimize.weightsSummary.table.weight}</Table.Th>
                        <Table.Th>{sv.optimize.weightsSummary.table.enabled}</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {weights.data.map((constraint) => (
                        <Table.Tr key={constraint.key}>
                          <Table.Td>{constraint.label}</Table.Td>
                          <Table.Td>
                            <Badge size="sm" variant="light">
                              {sv.hardOrSoft[constraint.hardOrSoft as keyof typeof sv.hardOrSoft] ?? constraint.hardOrSoft}
                            </Badge>
                          </Table.Td>
                          <Table.Td>{constraint.enabled ? constraint.weight : "—"}</Table.Td>
                          <Table.Td>{constraint.enabled ? "✓" : "—"}</Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                </Table.ScrollContainer>
              )}
              <Button variant="subtle" size="xs" mt="sm" onClick={() => navigate(`/plans/${planId}/falt`)}>
                {sv.optimize.weightsSummary.goToFieldsButton}
              </Button>
            </Accordion.Panel>
          </Accordion.Item>
        </Accordion>

        <Tooltip label={sv.optimize.optimizeOnly.tooltip} multiline w={280}>
          <Text fw={500} span style={{ textDecoration: "underline dotted", cursor: "help" }}>
            {sv.optimize.optimizeOnly.heading}
          </Text>
        </Tooltip>
        <Group mt="xs" mb="lg" align="flex-start">
          <Checkbox
            label={sv.optimize.optimizeOnly.players}
            description={
              <HelpTip label={sv.help.ariaLabel(sv.optimize.optimizeOnly.players)}>
                {sv.help.optimize.optimizeOnlyPlayers}
              </HelpTip>
            }
            checked={optimizePlayers}
            onChange={(event) => setOptimizePlayers(event.currentTarget.checked)}
          />
          <Checkbox
            label={sv.optimize.optimizeOnly.schedule}
            description={
              <HelpTip label={sv.help.ariaLabel(sv.optimize.optimizeOnly.schedule)}>
                {sv.help.optimize.optimizeOnlySchedule}
              </HelpTip>
            }
            checked={optimizeSchedule}
            onChange={(event) => setOptimizeSchedule(event.currentTarget.checked)}
          />
          <Checkbox
            label={sv.optimize.optimizeOnly.coaches}
            description={
              <HelpTip label={sv.help.ariaLabel(sv.optimize.optimizeOnly.coaches)}>
                {sv.help.optimize.optimizeOnlyCoaches}
              </HelpTip>
            }
            checked={optimizeCoaches}
            onChange={(event) => setOptimizeCoaches(event.currentTarget.checked)}
          />
        </Group>

        <Tooltip label={sv.optimize.blocking.tooltip} multiline w={280}>
          <Text fw={500} span style={{ textDecoration: "underline dotted", cursor: "help" }}>
            {sv.optimize.blocking.heading}
          </Text>
        </Tooltip>
        <Group mt="xs" mb="lg" align="flex-start" data-testid="blocking-checkboxes">
          <Checkbox
            label={sv.optimize.blocking.blockCoaches}
            description={
              <HelpTip label={sv.help.ariaLabel(sv.optimize.blocking.blockCoaches)}>{sv.help.optimize.blockCoaches}</HelpTip>
            }
            checked={blockCoaches}
            onChange={(event) => setBlockCoaches(event.currentTarget.checked)}
          />
          <Checkbox
            label={sv.optimize.blocking.blockPlayers}
            description={
              <HelpTip label={sv.help.ariaLabel(sv.optimize.blocking.blockPlayers)}>{sv.help.optimize.blockPlayers}</HelpTip>
            }
            checked={blockPlayers}
            onChange={(event) => setBlockPlayers(event.currentTarget.checked)}
          />
          <Checkbox
            label={sv.optimize.blocking.blockCourts}
            description={
              <HelpTip label={sv.help.ariaLabel(sv.optimize.blocking.blockCourts)}>{sv.help.optimize.blockCourts}</HelpTip>
            }
            checked={blockCourts}
            onChange={(event) => setBlockCourts(event.currentTarget.checked)}
          />
          <Checkbox
            label={sv.optimize.blocking.conflictsAsWarnings}
            description={
              <HelpTip label={sv.help.ariaLabel(sv.optimize.blocking.conflictsAsWarnings)}>
                {sv.help.optimize.conflictsAsWarnings}
              </HelpTip>
            }
            checked={conflictsAsWarnings}
            onChange={(event) => setConflictsAsWarnings(event.currentTarget.checked)}
          />
        </Group>

        {syncStatus.data?.stale && (
          // WI-C ("re-run doesn't feel like it re-runs" user feedback v0.4 #4, root cause A):
          // groups were generated from settings/participants that have since changed - warn before
          // the user starts a solve against stale group definitions.
          <Alert color="yellow" title={sv.optimize.groups.staleBanner.title} mb="lg" data-testid="groups-stale-banner">
            <Stack gap={4}>
              {syncStatus.data.reasons.map((reason) => (
                <Text size="sm" key={reason}>
                  {reason}
                </Text>
              ))}
              {staleRegenerateError && (
                <Text size="sm" c="red">
                  {staleRegenerateError}
                </Text>
              )}
              <Button
                size="xs"
                variant="light"
                color="yellow"
                mt="xs"
                loading={generateGroups.isPending}
                onClick={handleGenerateGroups}
              >
                {sv.optimize.groups.staleBanner.regenerateButton}
              </Button>
            </Stack>
          </Alert>
        )}

        <SuggestDurationCard
          planId={planId}
          solveActive={running}
          startPending={startSolve.isPending}
          onOptimize={(durationSeconds) => handleStart("CUSTOM", durationSeconds)}
        />

        <Accordion variant="separated">
          <Accordion.Item value="advanced">
            <Accordion.Control data-testid="advanced-toggle">{sv.optimize.advanced.heading}</Accordion.Control>
            <Accordion.Panel>
              <Radio.Group
                value={profile}
                onChange={(value) => setProfile(value as SolveProfile)}
                label={sv.optimize.profileHeading}
                description={
                  <HelpTip label={sv.help.ariaLabel(sv.optimize.profileHeading)}>{sv.help.optimize.solveProfiles}</HelpTip>
                }
                mb="md"
              >
                <Stack gap="xs" mt="xs">
                  {PROFILES.map((p) => (
                    <Radio
                      key={p}
                      value={p}
                      label={sv.optimize.profiles[p].label}
                      description={sv.optimize.profiles[p].description}
                    />
                  ))}
                </Stack>
              </Radio.Group>

              {profile === "CUSTOM" && (
                <NumberInput
                  label={sv.optimize.advanced.customSecondsLabel}
                  min={CUSTOM_MIN_SECONDS}
                  max={CUSTOM_MAX_SECONDS}
                  w={200}
                  mb="md"
                  value={customSeconds}
                  onChange={(value) => setCustomSeconds(value === "" ? "" : Number(value))}
                />
              )}

              <Checkbox
                label={sv.optimize.advanced.coldStartLabel}
                description={sv.optimize.advanced.coldStartDescription}
                checked={coldStart}
                onChange={(event) => setColdStart(event.currentTarget.checked)}
                mb="md"
              />

              <Button
                variant="default"
                onClick={() => handleStart(profile, profile === "CUSTOM" ? Number(customSeconds) : undefined)}
                loading={startSolve.isPending}
                disabled={advancedStartDisabled}
              >
                {sv.optimize.startButton}
              </Button>
            </Accordion.Panel>
          </Accordion.Item>
        </Accordion>
      </Card>

      {running && (
        <Card withBorder padding="md" data-testid="solve-progress">
          <Group justify="space-between" mb="xs">
            <Text fw={600}>{sv.optimize.progress.heading}</Text>
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
          {status?.hard != null ? (
            <Text fw={500} data-testid="live-score-line">
              {formatScoreLine(status)}
            </Text>
          ) : (
            <Text c="dimmed">{sv.optimize.progress.waitingForFirstResult}</Text>
          )}
          <Text size="sm" c="dimmed">
            {sv.optimize.progress.improvementCount(status?.improvementCount ?? 0)}
          </Text>
        </Card>
      )}

      {/* v0.3.0 WI-2 ("se det live"): gated on the SNAPSHOT existing, not on `running` - this keeps
          the last frame visible (dimmed, with a "go to Resultat" hint - LiveSolveView's own `running`
          prop drives that) for a moment after the solve settles, instead of vanishing the instant
          `solve-progress` above does. `useStartSolve`'s onSuccess guarantees `liveSolution.data` is
          cleared at the start of every new solve (including GREEDY, which never repopulates it). */}
      {liveSolution.data && <LiveSolveView planId={planId} snapshot={liveSolution.data} running={running} />}

      <Card withBorder padding="md" data-testid="last-run-summary">
        <Title order={5} mb="xs">
          {sv.optimize.lastRun.heading}
        </Title>
        {!latestRun && <Text c="dimmed">{sv.optimize.lastRun.empty}</Text>}
        {latestRun && latestSummary && (
          <Stack gap={6}>
            <Alert
              color={latestSummary.feasible ? "green" : "red"}
              title={latestSummary.feasible ? sv.optimize.lastRun.feasibleTitle : sv.optimize.lastRun.infeasibleTitle(Math.abs(latestSummary.hard))}
            >
              <Text data-testid="last-run-score-line">{formatScoreLine(latestSummary)}</Text>
            </Alert>
            {latestSummary.note && (
              // v0.2.0 (COACH-OPTIONAL SOLVING): the backend's own Swedish note, verbatim - present
              // only when the run solved a plan with zero coaches. Info, not a warning: solving
              // without coaches is fully supported.
              <Alert color="blue" data-testid="last-run-note">
                {latestSummary.note}
              </Alert>
            )}
            {latestSummary.unchangedFromPrevious && (
              // WI-C ("re-run doesn't feel like it re-runs" user feedback v0.4 #4, root cause B/C):
              // explains why re-running right after a successful solve can legitimately look like
              // "nothing happened" (deterministic warm start + randomSeed 0).
              <Alert color="blue" data-testid="last-run-unchanged-note">
                {sv.optimize.lastRun.unchangedNote}
              </Alert>
            )}
            <Group gap="xs">
              {latestRun.status === "CANCELLED" && <Badge color="yellow">{sv.optimize.lastRun.cancelledBadge}</Badge>}
              {latestDurationSeconds != null && (
                <Text size="sm" c="dimmed">
                  {sv.optimize.lastRun.duration(latestDurationSeconds)}
                </Text>
              )}
              <Text size="sm" c="dimmed">
                {sv.optimize.lastRun.when(formatDateTime(latestRun.startedAt))}
              </Text>
            </Group>
          </Stack>
        )}
        {latestRun && !latestSummary && latestRun.status === "FAILED" && (
          <Badge color="red">{sv.optimize.lastRun.failedBadge}</Badge>
        )}
        {latestRun && (
          <Accordion variant="separated" mt="sm">
            <Accordion.Item value="analysis">
              <Accordion.Control>{sv.optimize.analysis.heading}</Accordion.Control>
              <Accordion.Panel>
                <PlanAnalysisSection planId={planId} runId={latestRun.id} />
              </Accordion.Panel>
            </Accordion.Item>
          </Accordion>
        )}
      </Card>
    </Stack>
  );
}
