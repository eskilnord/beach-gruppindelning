import { Button, Group, Stack, Text, Title } from "@mantine/core";
import { sv } from "../../../../i18n/sv";
import type { IndirectFactorView } from "../../../../api/types";

interface IndirectFactorsSectionProps {
  indirectFactors: IndirectFactorView[];
  onNavigateToParticipant: (participantProfileId: string, name: string) => void;
}

/**
 * v0.3.0 WI-5 (user feedback: "Förklaringen av varför en spelare blev tilldelad en grupp bör även
 * visa om det beror på att en annan spelare påverkas av en tränare") - the second-order "via a
 * coach" section, rendered after the broken-wishes section in {@link ExplainDrawer}. Every entry's
 * `messageSv` is already the finished Swedish sentence (rendered server-side, see backend
 * ExplanationService#buildIndirectFactors); this component is a pure list renderer, same pattern
 * as the positive/negative factor lists, with the same "jump to that person's own explanation"
 * link affordance {@code BrokenWishRow} uses for a waitlisted friend.
 */
export function IndirectFactorsSection({ indirectFactors, onNavigateToParticipant }: IndirectFactorsSectionProps) {
  return (
    <div data-testid="explain-indirect-factors">
      <Title order={5}>{sv.results.explain.indirectFactorsHeading}</Title>
      {indirectFactors.length === 0 && (
        <Text size="sm" c="dimmed">
          {sv.results.explain.noIndirectFactors}
        </Text>
      )}
      <Stack gap={2} mt={indirectFactors.length > 0 ? "xs" : undefined}>
        {indirectFactors.map((factor, i) => (
          <Group key={i} gap={6} wrap="wrap" data-testid="explain-indirect-factor">
            <Text size="sm">{factor.messageSv}</Text>
            <Button
              size="compact-xs"
              variant="subtle"
              onClick={() => onNavigateToParticipant(factor.otherParticipantProfileId, factor.otherPersonName)}
            >
              {sv.results.explain.friendExplanationLink(factor.otherPersonName)}
            </Button>
          </Group>
        ))}
      </Stack>
    </div>
  );
}
