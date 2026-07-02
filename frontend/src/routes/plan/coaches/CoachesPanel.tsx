import { useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { Alert, Badge, Button, Card, Group, Loader, Table, Text, Title } from "@mantine/core";
import { useCoachAvailabilitySummaries, useCoaches } from "../../../api/coaches";
import { usePersons } from "../../../api/persons";
import { useParticipants } from "../../../api/participants";
import { ApiError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { CoachDrawer } from "./CoachDrawer";
import { NewCoachModal } from "./NewCoachModal";
import type { CoachRow } from "./coachRow";

function dash(value: unknown): string {
  return value === null || value === undefined || value === "" ? "—" : String(value);
}

function levelRange(min: number | null | undefined, max: number | null | undefined): string {
  if (min == null && max == null) {
    return "—";
  }
  return `${min ?? "—"}–${max ?? "—"}`;
}

/**
 * Tränarvy (spec §19.7): a table of every coach in the plan with a "Ny tränare" modal (link
 * existing person or create new, spec §13.1/§13.2) and a row-click detail drawer for editing the
 * profile + the tri-state availability matrix + custom fields (CoachDrawer.tsx).
 */
export function CoachesPanel() {
  const { planId } = useParams<{ planId: string }>();
  const coaches = useCoaches(planId);
  const persons = usePersons();
  const participants = useParticipants(planId);

  const coachIds = useMemo(() => (coaches.data ?? []).map((coach) => coach.id), [coaches.data]);
  const summaries = useCoachAvailabilitySummaries(planId, coachIds);

  const [newCoachOpen, setNewCoachOpen] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const personName = (personId: string): string => {
    const person = persons.data?.find((candidate) => candidate.id === personId);
    if (!person) {
      return personId;
    }
    return person.displayName || `${person.firstName} ${person.lastName}`.trim();
  };

  const rows: CoachRow[] = useMemo(
    () => (coaches.data ?? []).map((coach) => ({ ...coach, name: personName(coach.personId) })),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [coaches.data, persons.data],
  );

  const allParticipants = useMemo(
    () =>
      (participants.data ?? []).map((participant) => ({
        ...participant,
        name: personName(participant.personId),
      })),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [participants.data, persons.data],
  );

  const allCoachOptions = useMemo(() => rows.map((row) => ({ id: row.id, name: row.name })), [rows]);

  if (coaches.isLoading || persons.isLoading) {
    return <Loader size="sm" />;
  }
  if (coaches.isError) {
    return <Alert color="red">{coaches.error instanceof ApiError ? coaches.error.message : sv.coaches.loadFailed}</Alert>;
  }

  const isEmpty = rows.length === 0;
  const selectedCoach = rows.find((row) => row.id === selectedId) ?? null;

  return (
    <Card withBorder padding="lg">
      <Group justify="space-between" mb="sm">
        <Title order={4}>{sv.coaches.heading}</Title>
        <Button onClick={() => setNewCoachOpen(true)}>{sv.coaches.newCoachButton}</Button>
      </Group>

      {isEmpty && <Text c="dimmed">{sv.coaches.empty}</Text>}

      {!isEmpty && (
        <Table.ScrollContainer minWidth={860}>
          <Table verticalSpacing="xs" withTableBorder highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{sv.coaches.columns.name}</Table.Th>
                <Table.Th>{sv.coaches.columns.coachLevel}</Table.Th>
                <Table.Th>{sv.coaches.columns.canCoachRange}</Table.Th>
                <Table.Th>{sv.coaches.columns.maxGroupsPerDay}</Table.Th>
                <Table.Th>{sv.coaches.columns.maxGroupsPerWeek}</Table.Th>
                <Table.Th>{sv.coaches.columns.alsoParticipant}</Table.Th>
                <Table.Th>{sv.coaches.columns.availability}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((row) => {
                const summary = summaries[row.id] ?? { available: 0, unavailable: 0, preferred: 0 };
                return (
                  <Table.Tr key={row.id} style={{ cursor: "pointer" }} onClick={() => setSelectedId(row.id)}>
                    <Table.Td>{row.name}</Table.Td>
                    <Table.Td>{dash(row.coachLevel)}</Table.Td>
                    <Table.Td>{levelRange(row.canCoachMinLevel, row.canCoachMaxLevel)}</Table.Td>
                    <Table.Td>{dash(row.maxGroupsPerDay)}</Table.Td>
                    <Table.Td>{dash(row.maxGroupsPerWeek)}</Table.Td>
                    <Table.Td>
                      {row.canAlsoTrainAsParticipant && (
                        <Badge size="sm" variant="light" color="grape">
                          {sv.coaches.alsoParticipantBadge}
                        </Badge>
                      )}
                    </Table.Td>
                    <Table.Td>
                      <Text size="xs" c="dimmed">
                        {sv.coaches.availabilitySummary(summary.available, summary.preferred, summary.unavailable)}
                      </Text>
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}

      {planId && (
        <>
          <NewCoachModal planId={planId} opened={newCoachOpen} onClose={() => setNewCoachOpen(false)} />
          <CoachDrawer
            planId={planId}
            coach={selectedCoach}
            allParticipants={allParticipants}
            allCoaches={allCoachOptions}
            onClose={() => setSelectedId(null)}
          />
        </>
      )}
    </Card>
  );
}
