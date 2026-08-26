import { Alert, Card, Text } from "@mantine/core";
import { useCapacity } from "../../../api/capacity";
import { sv } from "../../../i18n/sv";
import { effectiveGroupSizeDefaults, FALLBACK_GROUP_TARGET_SIZE } from "../../../lib/planDefaults";
import { describeWaitlistRisk } from "../capacity/riskBanner";

interface SimpleCapacitySummaryProps {
  planId: string;
}

/**
 * v0.6.0 F4 (M-S4): a one-line capacity readout rendered under ResourcesPanel's slot list in SIMPLE
 * mode - "Plats för ungefär X deltagare i upp till Y grupper. Ni har Z anmälda.", plus the waitlist-
 * risk alert when applicable. Reuses the SAME `GET .../capacity` query (api/capacity.ts) and risk
 * mapping (capacity/riskBanner.ts's describeWaitlistRisk) the ADVANCED-only Kapacitetsvy tab already
 * uses - nothing new is computed here beyond the null-target fallback (see below), and this
 * component never gates that query on uiMode itself (F4 hard rule) - it simply isn't mounted in
 * ADVANCED mode (ResourcesPanel.tsx wraps it in `<SimpleOnly>`), so it fires its own independent
 * query only when it's actually on screen.
 *
 * Deliberately NEVER calls `describeCoachShortage` (F4 hard rule: simple mode never surfaces
 * coach-related content) - only the waitlist/participant side of CapacityResponse is read here.
 *
 * v0.6.0 audit-fix B13 ("Gunilla" persona - "the capacity summary is broken/nonsensical whenever
 * target/max aren't set"):
 *  - When the plan has no `defaultGroupTargetSize`/`defaultGroupMaxSize` set (M2 scope - both are
 *    optional), this used to fall back to a dead-end "kapaciteten kan inte beräknas" sentence with
 *    no numbers at all, even though `effectiveGroupSizeDefaults` (planDefaults.ts, mirrored 1:1 from
 *    the backend's own `GroupGenerator#effectiveSizes`) already tells you EXACTLY what a "Skapa
 *    grupper" click would actually use (target ?? 10). Now the capacity sentence is always computed,
 *    using the plan's real target when set, or that SAME fallback default when it isn't - and the
 *    "Med standardgruppstorlek 10: ..." prefix makes the substitution visible rather than silent.
 *  - A frontend-COMPUTED `registered > capacity` guard renders an actionable overCapacityWarning
 *    regardless of what CapacityResponse.waitlistRisk says - the backend enum stays untouched (and
 *    still drives `showRiskAlert` below for ADVANCED-style nuance), this is a strictly additive
 *    SIMPLE-mode supplement.
 *  - Short-circuits to the SAME zero-courts warning ResourcesPanel/SlotRow renders per-row (B9) when
 *    the PLAN-WIDE active court count is 0 - "plats för ungefär 0 deltagare" is nonsense, not an
 *    honest capacity readout, and B9's row-level warning(s) already explain the underlying cause.
 *  - "Ni har Z anmälda" is participantCount (already inclusive of waitlisted, per CapacityService's
 *    own javadoc), with "(varav W på kölista)" appended only when waitlistedCount > 0.
 */
export function SimpleCapacitySummary({ planId }: SimpleCapacitySummaryProps) {
  const capacity = useCapacity(planId);

  if (capacity.isLoading || capacity.isError || !capacity.data) {
    // Silent while loading/erroring - ResourcesPanel's slot list above is the primary content here;
    // this is a supplementary readout, not worth its own dedicated error banner.
    return null;
  }

  const data = capacity.data;

  if (data.activeTrainingBlockCount === 0) {
    return (
      <Card withBorder padding="md" mt="md" data-testid="simple-capacity-summary">
        <Alert color="orange" data-testid="simple-capacity-zero-courts-warning">
          {sv.resources.zeroCourtsWarning}
        </Alert>
      </Card>
    );
  }

  // Only `targetGroupSize` feeds this card's numbers (capacityEstimate/groups below) - `maxGroupSize`
  // is a separate, unrelated field (ADVANCED's own "Maxkapacitet" headline) this card never shows,
  // so it must not influence whether the "Med standardgruppstorlek ..." fallback prefix applies.
  const usesFallback = data.targetGroupSize == null;
  const effective = effectiveGroupSizeDefaults(data.targetGroupSize, null, null);
  const capacityEstimate = data.activeTrainingBlockCount * effective.target;

  // Mirrors useGenerateGroups' own clamp (`clamp(ceil(active/target), 1, activeBlocks)`) so this
  // number always agrees with what "Skapa grupper" would actually produce - see the F4 review-fix
  // note this replaces below.
  const groups = Math.min(
    Math.max(Math.ceil(data.participantCount / effective.target), 1),
    Math.max(data.activeTrainingBlockCount, 1),
  );

  const summaryText = usesFallback
    ? sv.simple.capacity.summaryDefault(
        FALLBACK_GROUP_TARGET_SIZE,
        capacityEstimate,
        groups,
        data.participantCount,
        data.waitlistedCount,
      )
    : sv.simple.capacity.summary(capacityEstimate, groups, data.participantCount, data.waitlistedCount);

  const overCapacity = data.participantCount > capacityEstimate;

  const showRiskAlert = data.waitlistRisk === "OVER_TARGET" || data.waitlistRisk === "OVER_MAX";
  const banner = describeWaitlistRisk(data.waitlistRisk, data.waitlistMessage);

  return (
    <Card withBorder padding="md" mt="md" data-testid="simple-capacity-summary">
      <Text size="sm">{summaryText}</Text>
      {overCapacity && (
        <Alert color="orange" mt="xs" data-testid="simple-capacity-over-capacity-warning">
          {sv.simple.capacity.overCapacityWarning}
        </Alert>
      )}
      {showRiskAlert && (
        <Alert color={banner.color} title={banner.title} mt="xs" data-testid="simple-capacity-risk-alert">
          {banner.message}
        </Alert>
      )}
    </Card>
  );
}
