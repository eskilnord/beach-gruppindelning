import { Badge, Button, Card, Group, Table, Text, Title, Tooltip } from "@mantine/core";
import { sv } from "../../../i18n/sv";

export interface WaitlistEntry {
  participantProfileId: string;
  name: string;
  level: number | null;
  priority: string | null;
}

interface WaitlistCardProps {
  entries: WaitlistEntry[];
  /** The plan's latest run id (M7) - explain/what-if actions are disabled until the plan has been
   *  solved at least once. */
  runId: string | undefined;
  onExplain: (participantProfileId: string, name: string) => void;
  onTestMove: (participantProfileId: string, name: string) => void;
}

/**
 * OPLACERAD/KÖLISTA card (spec §19.10, docs/design/03-adversarial-review-round1.md's waitlist
 * decision): every `player_assignment` with `group_id == null` - the MVP waitlist bucket. `priority`
 * is the plan's "Prioritet" custom field value when one is configured (the same field the solver's
 * `unassignedPlayer` medium penalty scales by); omitted when the plan has none. Each row also gets
 * the M7 [Förklara]/[Testa flytt] actions (kravspec §17's waitlist narrative + §18 what-if, which
 * works symmetrically for probing a move INTO a group from the kölista).
 */
export function WaitlistCard({ entries, runId, onExplain, onTestMove }: WaitlistCardProps) {
  return (
    <Card withBorder padding="md" data-testid="waitlist-card">
      <Group justify="space-between" mb="xs">
        <Title order={5}>{sv.results.waitlist.heading}</Title>
        <Badge color={entries.length > 0 ? "yellow" : "green"} variant="light">
          {entries.length}
        </Badge>
      </Group>
      {entries.length === 0 && <Text c="dimmed">{sv.results.waitlist.empty}</Text>}
      {entries.length > 0 && (
        <Table verticalSpacing={4} withTableBorder>
          <Table.Tbody>
            {entries.map((entry) => (
              <Table.Tr key={entry.participantProfileId}>
                <Table.Td>{entry.name}</Table.Td>
                <Table.Td>{entry.level != null ? Math.round(entry.level) : "—"}</Table.Td>
                <Table.Td>
                  {entry.priority != null && (
                    <Badge size="xs" variant="light">
                      {sv.results.waitlist.priorityLabel(entry.priority)}
                    </Badge>
                  )}
                </Table.Td>
                <Table.Td>
                  <Group gap={4} wrap="nowrap">
                    <Tooltip label={sv.results.noRunTooltip} disabled={runId !== undefined}>
                      <Button
                        size="compact-xs"
                        variant="subtle"
                        disabled={runId === undefined}
                        onClick={() => onExplain(entry.participantProfileId, entry.name)}
                      >
                        {sv.results.waitlist.explainButton}
                      </Button>
                    </Tooltip>
                    <Tooltip label={sv.results.noRunTooltip} disabled={runId !== undefined}>
                      <Button
                        size="compact-xs"
                        variant="subtle"
                        disabled={runId === undefined}
                        onClick={() => onTestMove(entry.participantProfileId, entry.name)}
                      >
                        {sv.results.groupCard.testMoveButton}
                      </Button>
                    </Tooltip>
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Card>
  );
}
