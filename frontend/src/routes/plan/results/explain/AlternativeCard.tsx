import { Badge, Card, Group, Text } from "@mantine/core";
import { sv } from "../../../../i18n/sv";
import type { AlternativeGroupView } from "../../../../api/types";
import { originBadge, verdictBadge } from "./badges";
import { formatScoreDelta } from "./formatScoreDelta";

interface AlternativeCardProps {
  alternative: AlternativeGroupView;
}

/**
 * One ALTERNATIVEN comparison card (spec §17.3): origin badge(s) (a candidate can carry more than
 * one - the union rule, design §11.3), verdict badge + score delta, and the newlyBroken/newlyFixed
 * constraint-message lists. Shared between the person-level `alternatives[]` list and the ad-hoc
 * "Varför inte grupp X?" why-not result, which returns the exact same {@code AlternativeGroupView}
 * shape (design §11.5's "guaranteed identical answer" framing).
 */
export function AlternativeCard({ alternative }: AlternativeCardProps) {
  const verdict = verdictBadge(alternative.verdict);
  return (
    <Card withBorder padding="sm" data-testid="alternative-card">
      <Group justify="space-between" mb={4} wrap="nowrap">
        <Text fw={600}>{alternative.name}</Text>
        <Badge color={verdict.color} data-testid="alternative-verdict-badge">
          {verdict.label}
        </Badge>
      </Group>
      {alternative.origin.length > 0 && (
        <Group gap={4} mb={4}>
          {alternative.origin.map((origin) => {
            const badge = originBadge(origin);
            return (
              <Badge key={origin} size="xs" variant="light" color={badge.color} data-testid="alternative-origin-badge">
                {badge.label}
              </Badge>
            );
          })}
        </Group>
      )}
      <Text size="sm" mb={4}>
        {alternative.narrativeSv}
      </Text>
      <Text size="xs" c="dimmed" mb={4}>
        {sv.results.whatIf.scoreDeltaLabel}: {formatScoreDelta(alternative.scoreDelta)}
      </Text>
      {alternative.newlyBroken.length > 0 && (
        <div>
          <Text size="xs" fw={600}>
            {sv.results.explain.newlyBrokenHeading}
          </Text>
          {alternative.newlyBroken.map((m, i) => (
            <Text key={`${m.key}-${i}`} size="xs" c="red">
              {m.messageSv}
            </Text>
          ))}
        </div>
      )}
      {alternative.newlyFixed.length > 0 && (
        <div>
          <Text size="xs" fw={600}>
            {sv.results.explain.newlyFixedHeading}
          </Text>
          {alternative.newlyFixed.map((m, i) => (
            <Text key={`${m.key}-${i}`} size="xs" c="green">
              {m.messageSv}
            </Text>
          ))}
        </div>
      )}
    </Card>
  );
}
