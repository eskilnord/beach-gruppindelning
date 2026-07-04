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

/** User feedback v0.4.1: GROUP_MAX/GROUP_MAX_WISH name a fixed limit (court capacity/plan max size)
 *  that cannot actually be changed from this screen - they are rendered as an explanation of the
 *  result ("limitation"), never alongside the genuinely actionable "ask a person" suggestions
 *  (PLAYER_TIME/PLAYER_TIME_WISH/COACH_TIME/COACH_MAX). */
const LIMITATION_KINDS: ReadonlySet<SuggestionKind> = new Set(["GROUP_MAX", "GROUP_MAX_WISH"]);

function isLimitation(kind: SuggestionKind): boolean {
  return LIMITATION_KINDS.has(kind);
}

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

  const all = suggestions.data?.suggestions ?? [];
  const actionable = all.filter((s) => !isLimitation(s.kind));
  const limitations = all.filter((s) => isLimitation(s.kind));

  return (
    <Card withBorder padding="lg" data-testid="improvement-suggestions">
      <Group justify="space-between" mb={4}>
        <Title order={4}>{sv.results.suggestions.heading}</Title>
        {all.length > 0 && (
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
      {/* The card subtitle promises "changes the council can make" - hide it when the body holds
          ONLY fixed limitations, or the card would contradict its own content. */}
      {!(suggestions.data && actionable.length === 0 && limitations.length > 0) && (
        <Text size="sm" c="dimmed" mb="sm">
          {sv.results.suggestions.subtitle}
        </Text>
      )}

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

          {/* Only fall back to the "nothing found" empty text when there is truly NOTHING to show -
              a plan with only limitations (GROUP_MAX*) must never claim no improvements exist. */}
          {all.length === 0 ? (
            <Text size="sm" c="dimmed" data-testid="improvement-suggestions-empty">
              {sv.results.suggestions.empty}
            </Text>
          ) : (
            <Collapse in={opened}>
              {actionable.length > 0 && (
                <Stack gap="sm">
                  {actionable.map((suggestion, index) => (
                    <SuggestionRow key={index} suggestion={suggestion} />
                  ))}
                </Stack>
              )}

              {limitations.length > 0 && (
                <div>
                  <Title order={6} mt={actionable.length > 0 ? "md" : 0}>
                    {sv.results.suggestions.limitationsHeading}
                  </Title>
                  <Text size="xs" c="dimmed" mb="xs">
                    {sv.results.suggestions.limitationsSubtitle}
                  </Text>
                  <Stack gap="sm">
                    {limitations.map((suggestion, index) => (
                      <SuggestionRow key={index} suggestion={suggestion} isLimitation />
                    ))}
                  </Stack>
                </div>
              )}

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

function SuggestionRow({ suggestion, isLimitation }: { suggestion: SuggestionView; isLimitation?: boolean }) {
  const KindIcon = KIND_ICON[suggestion.kind] ?? IconClock;
  return (
    <Group
      align="flex-start"
      gap="sm"
      wrap="nowrap"
      data-testid={isLimitation ? "improvement-limitation-row" : "improvement-suggestion-row"}
    >
      <KindIcon size={20} style={{ flexShrink: 0, marginTop: 2 }} />
      <div style={{ flex: 1 }}>
        <Text size="sm">{suggestion.titleSv}</Text>
        {suggestion.detailSv && (
          <Text size="xs" c="dimmed">
            {suggestion.detailSv}
          </Text>
        )}
        {/* Non-green badge for limitations (task brief) - a fixed constraint explanation must never
            read as a "win" the way an actionable suggestion's green badge does. */}
        <Badge size="xs" variant="light" color={isLimitation ? "gray" : "green"} mt={4}>
          {suggestion.impactSv}
        </Badge>
      </div>
    </Group>
  );
}
