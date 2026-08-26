import { Alert, Card, Text } from "@mantine/core";
import { useCapacity } from "../../../api/capacity";
import { sv } from "../../../i18n/sv";
import { describeWaitlistRisk } from "../capacity/riskBanner";

interface SimpleCapacitySummaryProps {
  planId: string;
}

/**
 * v0.6.0 F4 (M-S4): a one-line capacity readout rendered under ResourcesPanel's slot list in SIMPLE
 * mode - "Plats för ungefär X deltagare i Y grupper. Ni har Z anmälda.", plus the waitlist-risk
 * alert when applicable. Reuses the SAME `GET .../capacity` query (api/capacity.ts) and risk mapping
 * (capacity/riskBanner.ts's describeWaitlistRisk) the ADVANCED-only Kapacitetsvy tab already uses -
 * nothing new is computed here, and this component never gates that query on uiMode itself (F4 hard
 * rule) - it simply isn't mounted in ADVANCED mode (ResourcesPanel.tsx wraps it in `<SimpleOnly>`),
 * so it fires its own independent query only when it's actually on screen.
 *
 * Deliberately NEVER calls `describeCoachShortage` (F4 hard rule: simple mode never surfaces
 * coach-related content) - only the waitlist/participant side of CapacityResponse is read here.
 */
export function SimpleCapacitySummary({ planId }: SimpleCapacitySummaryProps) {
  const capacity = useCapacity(planId);

  if (capacity.isLoading || capacity.isError || !capacity.data) {
    // Silent while loading/erroring - ResourcesPanel's slot list above is the primary content here;
    // this is a supplementary readout, not worth its own dedicated error banner.
    return null;
  }

  const data = capacity.data;
  // v0.6.0 F4 review fix (minor): "ungefär" (approximate seating) maps to the TARGET capacity
  // (activeBlocks × target group size, CapacityService.compute) - it must NEVER silently fall back to
  // the hard maxCapacity ceiling when targetCapacity is absent (a plan with no default group size set
  // has no honest "approximate" number at all; maxCapacity answers a different question). Absent
  // targetCapacity now falls straight through to the unknown-summary copy instead.
  const capacityEstimate = data.targetCapacity;

  // v0.6.0 F4 review fix (minor): the group estimate used to just reuse
  // `activeTrainingBlockCount` (CapacityService's own `groupsRequiringCoachEstimate`, one group per
  // active block, MVP) - honest for THAT field's own purpose, but not actually what "Generera
  // grupper" produces (useGenerateGroups' javadoc: `clamp(ceil(active/target), 1, activeBlocks)`).
  // Mirrors that same clamp here from the capacity payload's own inputs, so the two numbers agree.
  const groups =
    data.targetGroupSize && data.targetGroupSize > 0
      ? Math.min(
          Math.max(Math.ceil(data.participantCount / data.targetGroupSize), 1),
          Math.max(data.activeTrainingBlockCount, 1),
        )
      : data.activeTrainingBlockCount;

  const summaryText =
    capacityEstimate != null
      ? sv.simple.capacity.summary(capacityEstimate, groups, data.participantCount)
      : sv.simple.capacity.summaryUnknown(data.participantCount);

  const showRiskAlert = data.waitlistRisk === "OVER_TARGET" || data.waitlistRisk === "OVER_MAX";
  const banner = describeWaitlistRisk(data.waitlistRisk, data.waitlistMessage);

  return (
    <Card withBorder padding="md" mt="md" data-testid="simple-capacity-summary">
      <Text size="sm">{summaryText}</Text>
      {showRiskAlert && (
        <Alert color={banner.color} title={banner.title} mt="xs" data-testid="simple-capacity-risk-alert">
          {banner.message}
        </Alert>
      )}
    </Card>
  );
}
