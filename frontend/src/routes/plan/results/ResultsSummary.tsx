import { Badge, Card, Group, Text } from "@mantine/core";
import { IconAlertCircle, IconCircleCheck } from "@tabler/icons-react";
import { usePlanExplanation } from "../../../api/explanations";
import type { RunResultSummary } from "../../../api/types";
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
 * per-violation count) once it has loaded, falling back to the run summary's raw score magnitude
 * (the same `Math.abs(hard)` convention the Optimeringsvy already uses for its own "N hårda brott")
 * while the explanation is still loading or failed to load - the rest of the strip never blocks on
 * that one extra request.
 */
export function ResultsSummary({ planId, runId, runStartedAtLabel, runSummary, coachCoverage }: ResultsSummaryProps) {
  const explanation = usePlanExplanation(planId, runId);

  if (!runId || !runSummary) {
    return null;
  }

  const hardCount = explanation.data ? explanation.data.hardViolations.length : Math.abs(runSummary.hard);
  const hardOk = hardCount === 0;
  const waitlistOk = runSummary.unassignedCount === 0;

  return (
    <Card padding="lg" data-testid="results-quality-summary" aria-label={sv.results.quality.regionLabel}>
      <Text size="xs" c="dimmed" mb="xs" data-testid="explain-based-on">
        {sv.results.explainBasedOn(runStartedAtLabel ?? "")}
      </Text>

      <Group gap="sm" wrap="wrap">
        <Badge
          size="lg"
          variant="light"
          color={hardOk ? "teal" : "red"}
          leftSection={hardOk ? <IconCircleCheck size={14} /> : <IconAlertCircle size={14} />}
        >
          {hardOk ? sv.results.quality.hardViolations.ok : sv.results.quality.hardViolations.bad(hardCount)}
        </Badge>
        <Badge size="lg" variant="light" color={waitlistOk ? "teal" : "sand"}>
          {waitlistOk ? sv.results.quality.waitlist.ok : sv.results.quality.waitlist.bad(runSummary.unassignedCount)}
        </Badge>
        {coachCoverage && (
          <Badge size="lg" variant="light" color={coachCoverage.covered === coachCoverage.total ? "teal" : "yellow"}>
            {sv.results.quality.coachCoverage(coachCoverage.covered, coachCoverage.total)}
          </Badge>
        )}
      </Group>

      {/* Soft component ONLY (review fix 2): the chips above already carry the hard-violation and
          waitlist counts from better sources - formatScoreLine's own "|hard| hårda brott" is a
          WEIGHTED score magnitude that can contradict the chip's per-violation count. */}
      <Group gap={4} mt="xs">
        <Text size="xs" c="dimmed">
          {formatSoftLine(runSummary.soft)}
        </Text>
        <HelpTip label={sv.help.ariaLabel(sv.results.quality.softScoreLabel)}>{sv.help.results.softScore}</HelpTip>
      </Group>
    </Card>
  );
}
