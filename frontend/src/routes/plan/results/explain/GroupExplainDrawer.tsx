import { Alert, Button, Divider, Drawer, Group, Loader, Stack, Table, Text, Title } from "@mantine/core";
import { useGroupExplanation } from "../../../../api/explanations";
import { ApiError } from "../../../../api/client";
import { sv } from "../../../../i18n/sv";
import { describeStaleness } from "./staleness";

interface GroupExplainDrawerProps {
  planId: string;
  runId: string | undefined;
  groupId: string | null;
  groupName: string;
  onClose: () => void;
}

/**
 * "Förklara grupp" (kravspec §17.1's "Gruppnivå" level): antal spelare/target/max, nivåsnitt/
 * -spridning, tränare, tid/bana, varningar, the constraint matches that touched this group, and
 * which members have a broken wish inside it (spec §17.1 "vilka spelare som har brutna önskemål").
 */
export function GroupExplainDrawer({ planId, runId, groupId, groupName, onClose }: GroupExplainDrawerProps) {
  const opened = groupId !== null;
  return (
    <Drawer
      opened={opened}
      onClose={onClose}
      position="right"
      size="lg"
      title={sv.results.groupExplain.title(groupName)}
      data-testid="group-explain-drawer"
    >
      {opened && (
        <GroupExplainDrawerBody
          key={`${runId ?? ""}:${groupId}`}
          planId={planId}
          runId={runId}
          groupId={groupId}
          onClose={onClose}
        />
      )}
    </Drawer>
  );
}

interface GroupExplainDrawerBodyProps {
  planId: string;
  runId: string | undefined;
  groupId: string;
  onClose: () => void;
}

function GroupExplainDrawerBody({ planId, runId, groupId, onClose }: GroupExplainDrawerBodyProps) {
  const explanation = useGroupExplanation(planId, runId, groupId);

  if (explanation.isLoading) {
    return <Loader size="sm" />;
  }
  if (explanation.isError || !explanation.data) {
    return (
      <Alert color="red">
        {explanation.error instanceof ApiError ? explanation.error.message : sv.results.groupExplain.loadFailed}
      </Alert>
    );
  }

  const data = explanation.data;
  const banner = describeStaleness(data.stale);

  return (
    <Stack gap="md">
      {banner.show && (
        <Alert color="yellow" data-testid="group-explain-stale-banner">
          {banner.message}
        </Alert>
      )}

      <div>
        <Text size="sm" c="dimmed">
          {sv.results.groupCard.playersCount(data.size, data.targetSize ?? null, data.maxSize ?? null)}
        </Text>
        {data.levelMeanSv && (
          <Text size="sm" c="dimmed">
            {sv.results.groupCard.levelMean}: {data.levelMeanSv}
            {data.levelSpread != null ? ` · ${sv.results.groupCard.levelSpread}: ${data.levelSpread}` : ""}
          </Text>
        )}
        {data.coach && <Text size="sm">{data.coach.name}</Text>}
        {data.block && <Text size="sm">{data.block.label}</Text>}
      </div>

      <Divider />

      <div data-testid="group-explain-warnings">
        <Title order={5}>{sv.results.groupExplain.warningsHeading}</Title>
        {data.warnings.length === 0 && (
          <Text size="sm" c="dimmed">
            {sv.results.groupExplain.noWarnings}
          </Text>
        )}
        {data.warnings.map((w, i) => (
          <Text size="sm" c="orange" key={i}>
            ⚠ {w}
          </Text>
        ))}
      </div>

      <div data-testid="group-explain-matches">
        <Title order={5}>{sv.results.groupExplain.matchesHeading}</Title>
        {data.matches.length === 0 && (
          <Text size="sm" c="dimmed">
            {sv.results.groupExplain.noMatches}
          </Text>
        )}
        {data.matches.length > 0 && (
          <Table verticalSpacing={4} withTableBorder mt={4}>
            <Table.Tbody>
              {data.matches.map((m, i) => (
                <Table.Tr key={i}>
                  <Table.Td>{m.messageSv}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </div>

      <div data-testid="group-explain-broken-wish-members">
        <Title order={5}>{sv.results.groupExplain.membersWithBrokenWishesHeading}</Title>
        {data.membersWithBrokenWishes.length === 0 && (
          <Text size="sm" c="dimmed">
            {sv.results.groupExplain.noBrokenWishMembers}
          </Text>
        )}
        {data.membersWithBrokenWishes.map((m, i) => (
          <Text size="sm" key={i}>
            {m.name}: {m.messageSv}
          </Text>
        ))}
      </div>

      <Group justify="flex-end">
        <Button variant="default" onClick={onClose}>
          {sv.common.close}
        </Button>
      </Group>
    </Stack>
  );
}
