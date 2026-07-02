import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import {
  Accordion,
  Alert,
  Badge,
  Button,
  Card,
  Checkbox,
  Group,
  Loader,
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
import { useGenerateGroups, useGroups } from "../../../api/groups";
import { runsKey, useOptimizationRuns } from "../../../api/runs";
import { invalidateResultQueries, isSolveRunning, useCancelSolve, useSolveStatus, useStartSolve } from "../../../api/solve";
import type { SolveProfile } from "../../../api/types";
import { sv } from "../../../i18n/sv";
import { formatScoreLine } from "./scoreFormat";
import { parseResultSummary, runDurationSeconds } from "./runSummary";

const PROFILES: SolveProfile[] = ["FAST", "NORMAL", "THOROUGH", "GREEDY"];

function formatWhen(iso: string | undefined): string {
  if (!iso) {
    return "";
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  return date.toLocaleString("sv-SE", { dateStyle: "short", timeStyle: "short" });
}

/**
 * Optimeringsvy (spec §19.9): profile picker, a collapsible read-only constraint-weights summary
 * (reuses the M4 `constraint-weights` data, links to Fält for editing), §15.5 "Optimera endast"
 * checkboxes, start/cancel with a 1s-polling progress panel, and a persistent "Senaste körning"
 * card that doubles as the completion result banner (feasible green / hard-violations red) - see
 * `api/runs.ts`'s javadoc for why that reads `GET .../runs` rather than the (post-completion, empty)
 * `GET .../solve/status`.
 */
export function OptimizePanel() {
  const { planId } = useParams<{ planId: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const groups = useGroups(planId);
  const generateGroups = useGenerateGroups(planId ?? "");
  const weights = useConstraintWeights(planId);
  const solveStatus = useSolveStatus(planId);
  const runs = useOptimizationRuns(planId);
  const startSolve = useStartSolve(planId ?? "");
  const cancelSolve = useCancelSolve(planId ?? "");

  const [profile, setProfile] = useState<SolveProfile>("NORMAL");
  const [optimizePlayers, setOptimizePlayers] = useState(true);
  const [optimizeSchedule, setOptimizeSchedule] = useState(true);
  const [optimizeCoaches, setOptimizeCoaches] = useState(true);

  const status = solveStatus.data;
  const running = isSolveRunning(status?.status);

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

  const handleGenerateGroups = () => {
    generateGroups.mutate(undefined, {
      onSuccess: (data) => {
        notifications.show({ color: "green", message: sv.optimize.groups.generateSuccess(data.length) });
      },
      onError: (error) => {
        notifications.show({
          color: "red",
          title: sv.common.error,
          message: error instanceof ApiError ? error.message : sv.optimize.groups.generateFailed,
        });
      },
    });
  };

  const handleStart = () => {
    startSolve.mutate(
      { profile, optimize: { players: optimizePlayers, schedule: optimizeSchedule, coaches: optimizeCoaches } },
      {
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
      },
    );
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
  const latestDurationSeconds = latestRun ? runDurationSeconds(latestRun) : null;

  return (
    <Stack gap="md">
      <Card withBorder padding="lg">
        <Title order={4} mb="md">
          {sv.optimize.heading}
        </Title>

        <Group justify="space-between" mb="lg" data-testid="groups-summary">
          <div>
            <Text fw={600}>{sv.optimize.groups.heading}</Text>
            <Text size="sm" c="dimmed">
              {sv.optimize.groups.count(groups.data?.length ?? 0)}
            </Text>
          </div>
          <Button variant="default" size="xs" loading={generateGroups.isPending} onClick={handleGenerateGroups}>
            {sv.optimize.groups.generateButton}
          </Button>
        </Group>

        <Radio.Group
          value={profile}
          onChange={(value) => setProfile(value as SolveProfile)}
          label={sv.optimize.profileHeading}
          mb="lg"
        >
          <Stack gap="xs" mt="xs">
            {PROFILES.map((p) => (
              <Radio key={p} value={p} label={sv.optimize.profiles[p].label} description={sv.optimize.profiles[p].description} />
            ))}
          </Stack>
        </Radio.Group>

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
                  <Table verticalSpacing={4} withTableBorder>
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
        <Group mt="xs" mb="lg">
          <Checkbox
            label={sv.optimize.optimizeOnly.players}
            checked={optimizePlayers}
            onChange={(event) => setOptimizePlayers(event.currentTarget.checked)}
          />
          <Checkbox
            label={sv.optimize.optimizeOnly.schedule}
            checked={optimizeSchedule}
            onChange={(event) => setOptimizeSchedule(event.currentTarget.checked)}
          />
          <Checkbox
            label={sv.optimize.optimizeOnly.coaches}
            checked={optimizeCoaches}
            onChange={(event) => setOptimizeCoaches(event.currentTarget.checked)}
          />
        </Group>

        <Button onClick={handleStart} loading={startSolve.isPending} disabled={running}>
          {sv.optimize.startButton}
        </Button>
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
            <Group gap="xs">
              {latestRun.status === "CANCELLED" && <Badge color="yellow">{sv.optimize.lastRun.cancelledBadge}</Badge>}
              {latestDurationSeconds != null && (
                <Text size="sm" c="dimmed">
                  {sv.optimize.lastRun.duration(latestDurationSeconds)}
                </Text>
              )}
              <Text size="sm" c="dimmed">
                {sv.optimize.lastRun.when(formatWhen(latestRun.startedAt))}
              </Text>
            </Group>
          </Stack>
        )}
        {latestRun && !latestSummary && latestRun.status === "FAILED" && (
          <Badge color="red">{sv.optimize.lastRun.failedBadge}</Badge>
        )}
      </Card>
    </Stack>
  );
}
