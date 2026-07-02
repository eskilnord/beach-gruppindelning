import { Alert, Loader, Stack, Table, Text, Title } from "@mantine/core";
import { usePlanExplanation } from "../../../api/explanations";
import { ApiError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { describeStaleness } from "../results/explain/staleness";
import { weightBadgeLabel } from "../results/explain/badges";

interface PlanAnalysisSectionProps {
  planId: string;
  runId: string;
}

/**
 * "Analys" expandable content for the Optimeringsvy's "Senaste körning" card (kravspec §17.1's
 * "Planeringsnivå" level): the constraintSummaries table (label/nivå/vikt/poäng/antal), the
 * hardViolations list (should be empty on a feasible run - shown red if not), the waitlist with its
 * per-participant `reasonSv`, and the problematicGroups ranking.
 */
export function PlanAnalysisSection({ planId, runId }: PlanAnalysisSectionProps) {
  const explanation = usePlanExplanation(planId, runId);

  if (explanation.isLoading) {
    return <Loader size="sm" />;
  }
  if (explanation.isError || !explanation.data) {
    return (
      <Alert color="red">
        {explanation.error instanceof ApiError ? explanation.error.message : sv.optimize.analysis.loadFailed}
      </Alert>
    );
  }

  const data = explanation.data;
  const banner = describeStaleness(data.stale);

  return (
    <Stack gap="md" data-testid="plan-analysis-section">
      {banner.show && (
        <Alert color="yellow" data-testid="plan-analysis-stale-banner">
          {banner.message}
        </Alert>
      )}

      <div>
        <Title order={6}>{sv.optimize.analysis.constraintSummariesHeading}</Title>
        <Table.ScrollContainer minWidth={520}>
          <Table verticalSpacing={4} withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{sv.optimize.analysis.table.label}</Table.Th>
                <Table.Th>{sv.optimize.analysis.table.level}</Table.Th>
                <Table.Th>{sv.optimize.analysis.table.weight}</Table.Th>
                <Table.Th>{sv.optimize.analysis.table.score}</Table.Th>
                <Table.Th>{sv.optimize.analysis.table.matches}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {data.constraintSummaries.map((c) => (
                <Table.Tr key={c.key}>
                  <Table.Td>{c.label}</Table.Td>
                  <Table.Td>{sv.hardOrSoft[c.level as keyof typeof sv.hardOrSoft] ?? c.level}</Table.Td>
                  <Table.Td>{weightBadgeLabel(c.level, c.weightApplied)}</Table.Td>
                  <Table.Td>{c.scoreTotal}</Table.Td>
                  <Table.Td>{c.matchCount}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      </div>

      <div data-testid="plan-analysis-hard-violations">
        <Title order={6}>{sv.optimize.analysis.hardViolationsHeading}</Title>
        {data.hardViolations.length === 0 ? (
          <Text size="sm" c="green">
            {sv.optimize.analysis.noHardViolations}
          </Text>
        ) : (
          data.hardViolations.map((v, i) => (
            <Text size="sm" c="red" key={i}>
              {v.messageSv}
            </Text>
          ))
        )}
      </div>

      <div data-testid="plan-analysis-waitlist">
        <Title order={6}>{sv.optimize.analysis.waitlistHeading}</Title>
        {data.waitlist.length === 0 ? (
          <Text size="sm" c="dimmed">
            {sv.optimize.analysis.noWaitlist}
          </Text>
        ) : (
          <Table verticalSpacing={4} withTableBorder>
            <Table.Tbody>
              {data.waitlist.map((w) => (
                <Table.Tr key={w.participantProfileId}>
                  <Table.Td>{w.name}</Table.Td>
                  <Table.Td>{w.reasonSv}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </div>

      <div data-testid="plan-analysis-problematic-groups">
        <Title order={6}>{sv.optimize.analysis.problematicGroupsHeading}</Title>
        {data.problematicGroups.length === 0 ? (
          <Text size="sm" c="dimmed">
            {sv.optimize.analysis.noProblematicGroups}
          </Text>
        ) : (
          data.problematicGroups.map((g) => (
            <Text size="sm" key={g.groupId}>
              {g.name}: {g.penaltySum}
            </Text>
          ))
        )}
      </div>

      {data.manualReview.length > 0 && (
        <div>
          <Title order={6}>{sv.optimize.analysis.manualReviewHeading}</Title>
          {data.manualReview.map((m) => (
            <Text size="sm" key={m.participantProfileId}>
              {m.name}: {m.reasonSv}
            </Text>
          ))}
        </div>
      )}
    </Stack>
  );
}
