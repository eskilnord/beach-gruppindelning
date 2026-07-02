import { useParams } from "react-router-dom";
import { Alert, Badge, Card, Group, Loader, SimpleGrid, Table, Text, Title } from "@mantine/core";
import { useCapacity } from "../../../api/capacity";
import { ApiError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { describeCoachShortage, describeWaitlistRisk } from "./riskBanner";

function dash(value: number | null | undefined): string {
  return value === null || value === undefined ? "—" : String(value);
}

interface HeadlineCardProps {
  label: string;
  value: string;
  caption?: string;
}

function HeadlineCard({ label, value, caption }: HeadlineCardProps) {
  return (
    <Card withBorder padding="md" data-testid="headline-card">
      <Text size="xs" c="dimmed" tt="uppercase">
        {label}
      </Text>
      <Text size="xl" fw={700}>
        {value}
      </Text>
      {caption && (
        <Text size="xs" c="dimmed">
          {caption}
        </Text>
      )}
    </Card>
  );
}

/**
 * Kapacitetsanalysvy (spec §12.4/§19.8): a pre-solve dashboard reading GET .../capacity - headline
 * numbers, the waitlist-risk banner, the coach-shortage section, and a per-slot breakdown table.
 * `useCapacity` auto-refetches on both tab navigation and window focus (see capacity.ts javadoc).
 */
export function CapacityPanel() {
  const { planId } = useParams<{ planId: string }>();
  const capacity = useCapacity(planId);

  if (capacity.isLoading) {
    return <Loader size="sm" />;
  }
  if (capacity.isError || !capacity.data) {
    return (
      <Alert color="red">{capacity.error instanceof ApiError ? capacity.error.message : sv.capacity.loadFailed}</Alert>
    );
  }

  const data = capacity.data;

  if (data.perTimeSlot.length === 0) {
    return (
      <Card withBorder padding="lg">
        <Title order={4} mb="xs">
          {sv.capacity.heading}
        </Title>
        <Text c="dimmed">{sv.capacity.empty}</Text>
      </Card>
    );
  }

  const waitlistBanner = describeWaitlistRisk(data.waitlistRisk, data.waitlistMessage);
  const coachBanner = describeCoachShortage(data.coachShortageRisk, data.coachShortageMessage);

  return (
    <Card withBorder padding="lg">
      <Title order={4} mb="md">
        {sv.capacity.heading}
      </Title>

      <SimpleGrid cols={{ base: 2, sm: 4 }} mb="md">
        <HeadlineCard
          label={sv.capacity.headline.participants}
          value={String(data.participantCount)}
          caption={data.waitlistedCount > 0 ? sv.capacity.headline.waitlistedCaption(data.waitlistedCount) : undefined}
        />
        <HeadlineCard label={sv.capacity.headline.activeBlocks} value={String(data.activeTrainingBlockCount)} />
        <HeadlineCard label={sv.capacity.headline.targetCapacity} value={dash(data.targetCapacity)} />
        <HeadlineCard label={sv.capacity.headline.maxCapacity} value={dash(data.maxCapacity)} />
      </SimpleGrid>

      <Text size="sm" c="dimmed" mb="md">
        {sv.capacity.groupSizes(data.targetGroupSize ?? null, data.maxGroupSize ?? null)}
      </Text>

      <Alert color={waitlistBanner.color} title={waitlistBanner.title} mb="md">
        {waitlistBanner.message}
      </Alert>

      <Title order={5} mb="xs">
        {sv.capacity.coachSection.heading}
      </Title>
      <Group mb="xs" gap="xl">
        <div data-testid="coach-stat">
          <Text size="xs" c="dimmed">
            {sv.capacity.coachSection.coachCount}
          </Text>
          <Text fw={600}>{data.coachCount}</Text>
        </div>
        <div data-testid="coach-stat">
          <Text size="xs" c="dimmed">
            {sv.capacity.coachSection.groupsRequiringCoach}
          </Text>
          <Text fw={600}>{data.groupsRequiringCoachEstimate}</Text>
        </div>
      </Group>
      <Alert color={coachBanner.color} title={coachBanner.title} mb="md">
        {coachBanner.message}
      </Alert>

      <Title order={5} mb="xs">
        {sv.capacity.perSlotHeading}
      </Title>
      <Table.ScrollContainer minWidth={520}>
        <Table verticalSpacing="xs" withTableBorder>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{sv.capacity.perSlotTable.slot}</Table.Th>
              <Table.Th>{sv.capacity.perSlotTable.blocks}</Table.Th>
              <Table.Th>{sv.capacity.perSlotTable.coaches}</Table.Th>
              <Table.Th>{sv.capacity.perSlotTable.preferred}</Table.Th>
              <Table.Th>{sv.capacity.perSlotTable.status}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {data.perTimeSlot.map((slot) => {
              const deficient = slot.activeBlockCount > 0 && slot.coachesAvailableCount < slot.activeBlockCount;
              return (
                <Table.Tr key={slot.timeSlotId}>
                  <Table.Td>{slot.label}</Table.Td>
                  <Table.Td>{slot.activeBlockCount}</Table.Td>
                  <Table.Td>{slot.coachesAvailableCount}</Table.Td>
                  <Table.Td>{slot.coachesPreferredCount}</Table.Td>
                  <Table.Td>
                    <Badge size="sm" variant="light" color={deficient ? "red" : "green"}>
                      {deficient ? sv.capacity.perSlotTable.deficient : sv.capacity.perSlotTable.ok}
                    </Badge>
                  </Table.Td>
                </Table.Tr>
              );
            })}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>
    </Card>
  );
}
