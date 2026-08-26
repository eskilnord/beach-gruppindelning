import { Badge, Card, Group, Text } from "@mantine/core";
import { IconAlertCircle, IconCircleCheck } from "@tabler/icons-react";
import { usePlanExplanation } from "../../../api/explanations";
import type { RunResultSummary } from "../../../api/types";
import { AdvancedOnly } from "../../../components/uimode/AdvancedOnly";
import { HelpTip } from "../../../components/HelpTip";
import { sv } from "../../../i18n/sv";
import { formatSoftLine } from "../optimize/scoreFormat";

export interface CoachCoverage {
  covered: number;
  total: number;
}

interface ResultsSummaryProps {
  planId: string;
  /** The plan's latest run id - `undefined` (no run yet) hides the whole strip, same as `runSummary`
   *  being `null` (a run exists but its `resultSummaryJson` failed to parse). */
  runId: string | undefined;
  runStartedAtLabel: string | undefined;
  runSummary: RunResultSummary | null;
  /** Groups-with-a-coach / total-groups, or `null` to omit the chip entirely - the caller
   *  (ResultsPanel) passes `null` under the exact same condition the coach-less `results-note` Alert
   *  already uses (a plan with zero coach profiles has nothing meaningful to report here). */
  coachCoverage: CoachCoverage | null;
  /** v0.6.0 audit-fix batch C (C5, P1, persona audit "Gunilla" - "groups first"): shrinks the
   *  strip's padding and badge size - the strip still leads the Resultat tab in every mode and still
   *  carries every one of its chips/lines (nothing is removed, see this component's own javadoc),
   *  just with less visual weight so SIMPLE's group cards (now rendered immediately below it, see
   *  ResultsPanel.tsx) read as the main content. Defaults to `false` (today's ADVANCED look). */
  compact?: boolean;
}

/**
 * "Are these groups good?" at-a-glance strip (user feedback v0.4 #5), sitting between the
 * Resultatvy header and ImprovementSuggestions: hard-violations and waitlist chips, a soft-ONLY
 * dimmed score line (formatSoftLine - see its javadoc for why the full score line's weighted |hard|
 * part is deliberately not repeated here), a coach-coverage stat that exists nowhere else on this
 * tab, and the explain-based-on timestamp (moved in from the Resultatvy header Card - same
 * data-testid/text, just relocated).
 *
 * The hard-violations count prefers the plan explanation's own `hardViolations` list (a true
 * per-violation count) once it has loaded. v0.6.0 audit-fix batch C (C8, P2, persona audit
 * "Gunilla"): it no longer falls back to the run summary's raw `Math.abs(hard)` score magnitude
 * while the explanation is still loading/failed - that value is a WEIGHTED SCORE (any magnitude,
 * not a per-violation count) and displaying it labeled "N måste-krav bryts" fabricated a count this
 * app has no real basis for yet. The chip instead shows a neutral "Kontrollerar hårda krav…" phrase
 * until the true count resolves - the rest of the strip never blocks on that one extra request.
 */
export function ResultsSummary({ planId, runId, runStartedAtLabel, runSummary, coachCoverage, compact = false }: ResultsSummaryProps) {
  const explanation = usePlanExplanation(planId, runId);

  if (!runId || !runSummary) {
    return null;
  }

  const trueHardCount = explanation.data ? explanation.data.hardViolations.length : null;
  const hardOk = trueHardCount === 0;
  const waitlistOk = runSummary.unassignedCount === 0;
  const badgeSize = compact ? "sm" : "lg";

  return (
    <Card
      padding={compact ? "sm" : "lg"}
      data-testid="results-quality-summary"
      aria-label={sv.results.quality.regionLabel}
    >
      <Text size="xs" c="dimmed" mb="xs" data-testid="explain-based-on">
        {sv.results.explainBasedOn(runStartedAtLabel ?? "")}
      </Text>

      <Group gap="sm" wrap="wrap">
        {trueHardCount == null ? (
          <Badge size={badgeSize} variant="light" color="gray">
            {sv.results.quality.hardViolations.checking}
          </Badge>
        ) : (
          <Badge
            size={badgeSize}
            variant="light"
            color={hardOk ? "teal" : "red"}
            leftSection={hardOk ? <IconCircleCheck size={14} /> : <IconAlertCircle size={14} />}
          >
            {hardOk ? sv.results.quality.hardViolations.ok : sv.results.quality.hardViolations.bad(trueHardCount)}
          </Badge>
        )}
        <Badge size={badgeSize} variant="light" color={waitlistOk ? "teal" : "sand"}>
          {waitlistOk ? sv.results.quality.waitlist.ok : sv.results.quality.waitlist.bad(runSummary.unassignedCount)}
        </Badge>
        {coachCoverage && (
          <Badge size={badgeSize} variant="light" color={coachCoverage.covered === coachCoverage.total ? "teal" : "yellow"}>
            {sv.results.quality.coachCoverage(coachCoverage.covered, coachCoverage.total)}
          </Badge>
        )}
      </Group>

      {/* Soft component ONLY (review fix 2): the chips above already carry the hard-violation and
          waitlist counts from better sources - formatScoreLine's own "|hard| hårda brott" is a
          WEIGHTED score magnitude that can contradict the chip's per-violation count. v0.6.0 F5
          (M-S5): a raw solver score is exactly the kind of jargon SIMPLE hides - the chips above
          (hard violations, waitlist) stay, they're plain counts, not a score. */}
      <AdvancedOnly>
        <Group gap={4} mt="xs">
          <Text size="xs" c="dimmed">
            {formatSoftLine(runSummary.soft)}
          </Text>
          <HelpTip label={sv.help.ariaLabel(sv.results.quality.softScoreLabel)}>{sv.help.results.softScore}</HelpTip>
        </Group>
      </AdvancedOnly>
    </Card>
  );
}
