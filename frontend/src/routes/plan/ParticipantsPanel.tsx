import { useNavigate, useParams } from "react-router-dom";
import { Alert, Badge, Button, Card, Group, Loader, Table, Text, Title } from "@mantine/core";
import { useParticipants } from "../../api/participants";
import { usePersons } from "../../api/persons";
import { ApiError } from "../../api/client";
import { sv } from "../../i18n/sv";

/** Basic Deltagare tab (M3 scope): just enough to see the import result — the full AG Grid
 *  Deltagarvy (structured-field drawer, editing, comment/reference panes) is M4. */
export function ParticipantsPanel() {
  const { planId } = useParams<{ planId: string }>();
  const navigate = useNavigate();
  const participants = useParticipants(planId);
  const persons = usePersons();

  const personName = (personId: string): string => {
    const person = persons.data?.find((candidate) => candidate.id === personId);
    if (!person) {
      return personId;
    }
    return person.displayName || `${person.firstName} ${person.lastName}`.trim();
  };

  return (
    <Card withBorder padding="lg">
      <Group justify="space-between" mb="sm">
        <Title order={4}>{sv.participants.heading}</Title>
        <Button onClick={() => navigate(`/plans/${planId}/import`)}>{sv.participants.importButton}</Button>
      </Group>

      {(participants.isLoading || persons.isLoading) && <Loader size="sm" />}
      {participants.isError && (
        <Alert color="red">
          {participants.error instanceof ApiError ? participants.error.message : sv.participants.loadFailed}
        </Alert>
      )}
      {participants.data && participants.data.length === 0 && <Text c="dimmed">{sv.participants.empty}</Text>}
      {participants.data && participants.data.length > 0 && (
        <Table verticalSpacing="xs">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{sv.participants.columns.name}</Table.Th>
              <Table.Th>{sv.participants.columns.ranking}</Table.Th>
              <Table.Th>{sv.participants.columns.previousGroup}</Table.Th>
              <Table.Th>{sv.participants.columns.status}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {participants.data.map((participant) => (
              <Table.Tr key={participant.id}>
                <Table.Td>{personName(participant.personId)}</Table.Td>
                <Table.Td>{participant.rankingPoints ?? "—"}</Table.Td>
                <Table.Td>{participant.previousGroupName ?? "—"}</Table.Td>
                <Table.Td>
                  {participant.waitlisted && <Badge color="yellow">{sv.participants.waitlistedBadge}</Badge>}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Card>
  );
}
