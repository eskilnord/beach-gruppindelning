import { Badge, Button, Card, Group, Table, Text, Title, Tooltip } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { ApiError } from "../../../api/client";
import { useLockPlayerAssignment, useUnlockPlayerAssignment } from "../../../api/assignments";
import { useLockGroupBlock, useLockGroupCoach, useUnlockGroupBlock, useUnlockGroupCoach } from "../../../api/groups";
import type { TrainingGroup } from "../../../api/types";
import { sv } from "../../../i18n/sv";
import { computeLevelStats } from "./groupMetrics";

export interface GroupCardMember {
  participantProfileId: string;
  name: string;
  level: number | null;
  source: string;
  locked: boolean;
}

export interface GroupCardCoach {
  coachProfileId: string;
  name: string;
  locked: boolean;
}

interface GroupCardProps {
  planId: string;
  group: TrainingGroup;
  timeBanaLabel: string | null;
  coaches: GroupCardCoach[];
  members: GroupCardMember[];
  /** The plan's latest run id (M7) - explain/what-if actions are disabled until the plan has been
   *  solved at least once (spec §17/§18 need a run to explain/probe against). */
  runId: string | undefined;
  /** Ctrl/Cmd+F player search (PlayerSearchSpotlight.tsx): the participant whose row
   *  ResultsPanel.tsx should scroll to and flash-highlight, from the `?highlight=` query param. */
  highlightedParticipantId?: string | null;
  onExplain: (participantProfileId: string, name: string) => void;
  onTestMove: (participantProfileId: string, name: string) => void;
  onExplainGroup: (groupId: string, name: string) => void;
}

function showError(error: unknown, fallback: string) {
  notifications.show({ color: "red", title: sv.common.error, message: error instanceof ApiError ? error.message : fallback });
}

/**
 * One group card in the Resultatvy (spec §19.10): name, tid+bana, tränare, spelarantal, nivåsnitt/
 * nivåspridning (computed client-side from members' levels, groupMetrics.ts), lock toggles for the
 * block/coaches/each member (spec §15.1-15.3 lock endpoints), a group-level "Förklara grupp" action
 * (M7, kravspec §17.1 "Gruppnivå"), and per-member [Förklara]/[Testa flytt] buttons (M7, spec §19.10)
 * - all three disabled with a tooltip until the plan has a `runId` to explain/probe against.
 */
export function GroupCard({
  planId,
  group,
  timeBanaLabel,
  coaches,
  members,
  runId,
  highlightedParticipantId,
  onExplain,
  onTestMove,
  onExplainGroup,
}: GroupCardProps) {
  const lockBlock = useLockGroupBlock(planId);
  const unlockBlock = useUnlockGroupBlock(planId);
  const lockCoach = useLockGroupCoach(planId);
  const unlockCoach = useUnlockGroupCoach(planId);
  const lockPlayer = useLockPlayerAssignment(planId);
  const unlockPlayer = useUnlockPlayerAssignment(planId);

  const levelStats = computeLevelStats(members.map((m) => m.level));

  const toggleBlockLock = () => {
    if (group.locked) {
      unlockBlock.mutate({ groupId: group.id }, { onError: (e) => showError(e, sv.results.groupCard.lockBlockFailed) });
    } else if (group.assignedTrainingBlockId) {
      lockBlock.mutate(
        { groupId: group.id, trainingBlockId: group.assignedTrainingBlockId },
        { onError: (e) => showError(e, sv.results.groupCard.lockBlockFailed) },
      );
    }
  };

  const toggleCoachLock = (coach: GroupCardCoach) => {
    if (coach.locked) {
      unlockCoach.mutate(
        { groupId: group.id, coachProfileId: coach.coachProfileId },
        { onError: (e) => showError(e, sv.results.groupCard.lockCoachFailed) },
      );
    } else {
      lockCoach.mutate(
        { groupId: group.id, coachProfileId: coach.coachProfileId },
        { onError: (e) => showError(e, sv.results.groupCard.lockCoachFailed) },
      );
    }
  };

  const togglePlayerLock = (member: GroupCardMember) => {
    if (member.locked) {
      unlockPlayer.mutate(
        { participantProfileId: member.participantProfileId },
        { onError: (e) => showError(e, sv.results.groupCard.unlockFailed) },
      );
    } else {
      lockPlayer.mutate(
        { participantProfileId: member.participantProfileId, groupId: group.id },
        { onError: (e) => showError(e, sv.results.groupCard.lockFailed) },
      );
    }
  };

  return (
    <Card withBorder padding="md" data-testid="group-card">
      <Group justify="space-between" mb={4}>
        <Group gap={6}>
          <Title order={5}>{group.name}</Title>
          <Tooltip label={sv.results.noRunTooltip} disabled={runId !== undefined}>
            <Button
              size="compact-xs"
              variant="subtle"
              disabled={runId === undefined}
              onClick={() => onExplainGroup(group.id, group.name)}
            >
              {sv.results.groupCard.explainGroupButton}
            </Button>
          </Tooltip>
        </Group>
        <Text size="sm" c="dimmed">
          {sv.results.groupCard.playersCount(members.length, group.targetSize ?? null, group.maxSize ?? null)}
        </Text>
      </Group>

      <Group gap={6} mb={4} wrap="nowrap">
        <Text size="sm">{timeBanaLabel ?? sv.results.groupCard.noBlock}</Text>
        <Tooltip label={group.locked ? sv.results.groupCard.blockUnlockTooltip : sv.results.groupCard.blockLockTooltip}>
          <Button
            size="compact-xs"
            variant="subtle"
            data-testid={`block-lock-${group.id}`}
            disabled={!group.assignedTrainingBlockId && !group.locked}
            loading={lockBlock.isPending || unlockBlock.isPending}
            onClick={toggleBlockLock}
          >
            {group.locked ? sv.results.groupCard.unlockButton : sv.results.groupCard.lockButton}
          </Button>
        </Tooltip>
        {group.locked && (
          <Badge size="xs" color="blue">
            {sv.results.groupCard.blockLocked}
          </Badge>
        )}
      </Group>

      <Group gap={6} mb="xs" wrap="wrap">
        {coaches.length === 0 && (
          <Text size="sm" c="dimmed">
            {sv.results.groupCard.noCoach}
          </Text>
        )}
        {coaches.map((coach) => (
          <Group key={coach.coachProfileId} gap={4} wrap="nowrap">
            <Text size="sm">{coach.name}</Text>
            <Tooltip label={coach.locked ? sv.results.groupCard.coachUnlockTooltip : sv.results.groupCard.coachLockTooltip}>
              <Button
                size="compact-xs"
                variant="subtle"
                data-testid={`coach-lock-${group.id}-${coach.coachProfileId}`}
                loading={lockCoach.isPending || unlockCoach.isPending}
                onClick={() => toggleCoachLock(coach)}
              >
                {coach.locked ? sv.results.groupCard.unlockButton : sv.results.groupCard.lockButton}
              </Button>
            </Tooltip>
            {coach.locked && (
              <Badge size="xs" color="blue">
                {sv.results.groupCard.coachLocked}
              </Badge>
            )}
          </Group>
        ))}
      </Group>

      <Group gap="lg" mb="sm">
        <div>
          <Text size="xs" c="dimmed">
            {sv.results.groupCard.levelMean}
          </Text>
          <Text fw={600}>{levelStats.mean ?? sv.results.groupCard.noLevelData}</Text>
        </div>
        <div>
          <Text size="xs" c="dimmed">
            {sv.results.groupCard.levelSpread}
          </Text>
          <Text fw={600}>{levelStats.spread ?? sv.results.groupCard.noLevelData}</Text>
        </div>
      </Group>

      <Text size="sm" fw={500} mb={4}>
        {sv.results.groupCard.membersHeading}
      </Text>
      <Table verticalSpacing={4} withTableBorder>
        <Table.Tbody>
          {members.map((member) => (
            <Table.Tr
              key={member.participantProfileId}
              id={`participant-row-${member.participantProfileId}`}
              className={highlightedParticipantId === member.participantProfileId ? "gp-highlight-flash" : undefined}
            >
              <Table.Td>{member.name}</Table.Td>
              <Table.Td>{member.level != null ? Math.round(member.level) : "—"}</Table.Td>
              <Table.Td>
                <Badge size="xs" variant="light">
                  {sv.results.groupCard.sourceBadge[member.source as keyof typeof sv.results.groupCard.sourceBadge] ??
                    member.source}
                </Badge>
              </Table.Td>
              <Table.Td>
                <Group gap={4} wrap="nowrap">
                  <Button
                    size="compact-xs"
                    variant="subtle"
                    data-testid={`member-lock-${member.participantProfileId}`}
                    loading={lockPlayer.isPending || unlockPlayer.isPending}
                    onClick={() => togglePlayerLock(member)}
                  >
                    {member.locked ? sv.results.groupCard.unlockButton : sv.results.groupCard.lockButton}
                  </Button>
                  <Tooltip label={sv.results.noRunTooltip} disabled={runId !== undefined}>
                    <Button
                      size="compact-xs"
                      variant="subtle"
                      disabled={runId === undefined}
                      onClick={() => onExplain(member.participantProfileId, member.name)}
                    >
                      {sv.results.groupCard.explainButton}
                    </Button>
                  </Tooltip>
                  <Tooltip label={sv.results.noRunTooltip} disabled={runId !== undefined}>
                    <Button
                      size="compact-xs"
                      variant="subtle"
                      disabled={runId === undefined}
                      onClick={() => onTestMove(member.participantProfileId, member.name)}
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
    </Card>
  );
}
