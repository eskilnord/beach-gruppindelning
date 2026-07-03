import { useState } from "react";
import { ActionIcon, Alert, Badge, Card, Collapse, Group, Loader, Stack, Text, Title } from "@mantine/core";
import { IconChevronDown, IconChevronUp, IconClock, IconResize, IconUserStar } from "@tabler/icons-react";
import type { Icon } from "@tabler/icons-react";
import { useImprovementSuggestions } from "../../../api/explanations";
import { ApiError } from "../../../api/client";
import type { SuggestionKind, SuggestionView } from "../../../api/types";
import { sv } from "../../../i18n/sv";

interface ImprovementSuggestionsProps {
  planId: string;
  /** The plan's latest run id - the caller (ResultsPanel) only mounts this component once a run
   *  exists, so this is required (not optional) here, unlike the explain drawers which stay mounted
   *  with an undefined runId and disable themselves. */
  runId: string;
}

/** One icon per suggestion `kind` (task brief: "clock for *_TIME, users/resize for GROUP_MAX*,
 *  user-star for COACH_*") - COACH_TIME is listed under both the "*_TIME" and "COACH_*" patterns in
 *  that brief; explicit per-kind mapping (rather than a suffix/prefix match) resolves the ambiguity
 *  in favor of the more specific "it's about a coach" signal. */
const KIND_ICON: Record<SuggestionKind, Icon> = {
  PLAYER_TIME: IconClock,
  PLAYER_TIME_WISH: IconClock,
  GROUP_MAX: IconResize,
  GROUP_MAX_WISH: IconResize,
  COACH_TIME: IconUserStar,
  COACH_MAX: IconUserStar,
};

/**
 * "Förbättringsförslag" (WI-D, user feedback v0.4 #2): post-solve, low-hanging-fruit suggestions for
 * SMALL data changes the council could make to unlock a bigger improvement - e.g. "Om tränaren Lisa
 * kunde ta torsdag 19.30-21.00 skulle Grupp 4 få en tränare." Rendered on the Resultat tab above the
 * group cards grid (ResultsPanel.tsx), self-contained: owns its own data fetch, loading/error/empty/
 * stale states.
 */
export function ImprovementSuggestions({ planId, runId }: ImprovementSuggestionsProps) {
  const suggestions = useImprovementSuggestions(planId, runId);
  const [opened, setOpened] = useState(true);

  return (
    <Card withBorder padding="lg" data-testid="improvement-suggestions">
      <Group justify="space-between" mb={4}>
        <Title order={4}>{sv.results.suggestions.heading}</Title>
        {suggestions.data && suggestions.data.suggestions.length > 0 && (
          <ActionIcon
            variant="subtle"
            aria-label={opened ? sv.results.suggestions.hideButton : sv.results.suggestions.showButton}
            onClick={() => setOpened((v) => !v)}
            data-testid="improvement-suggestions-toggle"
          >
            {opened ? <IconChevronUp size={18} /> : <IconChevronDown size={18} />}
          </ActionIcon>
        )}
      </Group>
      <Text size="sm" c="dimmed" mb="sm">
        {sv.results.suggestions.subtitle}
      </Text>

      {suggestions.isLoading && (
        <Group gap="xs" data-testid="improvement-suggestions-loading">
          <Loader size="sm" />
        </Group>
      )}

      {suggestions.isError && (
        <Alert color="red" data-testid="improvement-suggestions-error">
          {suggestions.error instanceof ApiError ? suggestions.error.message : sv.results.suggestions.loadFailed}
        </Alert>
      )}

      {suggestions.data && (
        <>
          {suggestions.data.stale && (
            <Alert color="yellow" mb="sm" data-testid="improvement-suggestions-stale-banner">
              {sv.results.suggestions.staleBanner}
            </Alert>
          )}

          {suggestions.data.suggestions.length === 0 ? (
            <Text size="sm" c="dimmed" data-testid="improvement-suggestions-empty">
              {sv.results.suggestions.empty}
            </Text>
          ) : (
            <Collapse in={opened}>
              <Stack gap="sm">
                {suggestions.data.suggestions.map((suggestion, index) => (
                  <SuggestionRow key={index} suggestion={suggestion} />
                ))}
              </Stack>
              {suggestions.data.omittedCount > 0 && (
                <Text size="xs" c="dimmed" mt="sm" data-testid="improvement-suggestions-omitted">
                  {sv.results.suggestions.omittedCount(suggestions.data.omittedCount)}
                </Text>
              )}
            </Collapse>
          )}
        </>
      )}
    </Card>
  );
}

function SuggestionRow({ suggestion }: { suggestion: SuggestionView }) {
  const KindIcon = KIND_ICON[suggestion.kind] ?? IconClock;
  return (
    <Group align="flex-start" gap="sm" wrap="nowrap" data-testid="improvement-suggestion-row">
      <KindIcon size={20} style={{ flexShrink: 0, marginTop: 2 }} />
      <div style={{ flex: 1 }}>
        <Text size="sm">{suggestion.titleSv}</Text>
        {suggestion.detailSv && (
          <Text size="xs" c="dimmed">
            {suggestion.detailSv}
          </Text>
        )}
        <Badge size="xs" variant="light" color="green" mt={4}>
          {suggestion.impactSv}
        </Badge>
      </div>
    </Group>
  );
}
