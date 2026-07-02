import { Alert, Button, Card, Group, Loader, Text, Title } from "@mantine/core";
import { ApiError } from "../../../api/client";
import { useSuggestDuration } from "../../../api/solve";
import { sv } from "../../../i18n/sv";

interface SuggestDurationCardProps {
  planId: string;
  /** True while a solve is running or being started for this plan - `POST .../solve/suggest-duration`
   *  409s then (its hardware benchmark competes for CPU with the solver), so the query is paused and
   *  the card shows the solve-active info state instead of firing a doomed request. */
  solveActive: boolean;
  /** The shared start-solve mutation's pending state (one solve per plan - both this card's primary
   *  button and the Avancerat start button funnel into the same mutation in OptimizePanel). */
  startPending: boolean;
  onOptimize: (durationSeconds: number) => void;
}

/**
 * Suggestion-first solve UX (v0.2.0, SUGGESTED OPTIMIZATION TIME): fetches
 * `POST .../solve/suggest-duration` eagerly on tab open (useSuggestDuration's javadoc) and renders
 * "Föreslagen optimeringstid: N s" + the backend's own rationaleSv + a problem-size summary line.
 * The primary [Optimera (N s)] button submits `{profile: "CUSTOM", durationSeconds: N}` via
 * `onOptimize`. Four states: solve-active info (paused/409), loading (first call runs a ~2s hardware
 * benchmark, cached after that), error + retry, and the suggestion itself with a manual
 * "Uppdatera förslag" re-run (problem size is recomputed on every call).
 */
export function SuggestDurationCard({ planId, solveActive, startPending, onOptimize }: SuggestDurationCardProps) {
  const suggestion = useSuggestDuration(planId, { enabled: !solveActive });
  // A 409 can still slip through the `enabled` gate (race: solve started between the status poll and
  // the suggest call) - render it as the same calm info state, not an error.
  const conflict = suggestion.isError && suggestion.error instanceof ApiError && suggestion.error.status === 409;

  return (
    <Card withBorder padding="md" mb="lg" data-testid="suggest-duration-card">
      <Title order={5} mb="xs">
        {sv.optimize.suggest.heading}
      </Title>

      {(solveActive || conflict) && (
        <Alert color="blue" data-testid="suggest-solve-active">
          {sv.optimize.suggest.solveActive}
        </Alert>
      )}

      {!solveActive && !conflict && suggestion.isPending && (
        <Group gap="xs" data-testid="suggest-loading">
          <Loader size="sm" />
          <Text size="sm" c="dimmed">
            {sv.optimize.suggest.loading}
          </Text>
        </Group>
      )}

      {!solveActive && !conflict && suggestion.isError && (
        <Alert color="red" data-testid="suggest-error">
          <Text size="sm" mb="xs">
            {suggestion.error instanceof ApiError ? suggestion.error.message : sv.optimize.suggest.loadFailed}
          </Text>
          <Button size="xs" variant="light" color="red" loading={suggestion.isFetching} onClick={() => void suggestion.refetch()}>
            {sv.optimize.suggest.retryButton}
          </Button>
        </Alert>
      )}

      {!solveActive && suggestion.data && (
        <>
          <Text fw={700} size="lg" data-testid="suggest-seconds">
            {sv.optimize.suggest.suggestedSeconds(suggestion.data.suggestedSeconds)}
          </Text>
          <Text size="sm" c="dimmed">
            {suggestion.data.rationaleSv}
          </Text>
          <Text size="xs" c="dimmed" mb="sm" data-testid="suggest-problem-size">
            {sv.optimize.suggest.problemSummary(suggestion.data.problemSize)}
          </Text>
          <Group gap="sm">
            <Button
              data-testid="suggest-optimize-button"
              loading={startPending}
              onClick={() => onOptimize(suggestion.data.suggestedSeconds)}
            >
              {sv.optimize.suggest.optimizeButton(suggestion.data.suggestedSeconds)}
            </Button>
            <Button variant="subtle" size="xs" loading={suggestion.isFetching} onClick={() => void suggestion.refetch()}>
              {sv.optimize.suggest.refreshButton}
            </Button>
          </Group>
        </>
      )}
    </Card>
  );
}
