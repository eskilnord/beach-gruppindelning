import { Badge, Box, Button, Card, Group, Stack, Table, Text, Title, Tooltip } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { ApiError } from "../../../api/client";
import { useLockPlayerAssignment, useUnlockPlayerAssignment } from "../../../api/assignments";
import { useLockGroupBlock, useLockGroupCoach, useUnlockGroupBlock, useUnlockGroupCoach } from "../../../api/groups";
import type { TrainingGroup } from "../../../api/types";
import { sv } from "../../../i18n/sv";
import { computeLevelStats } from "./groupMetrics";
import { computeGroupQuality, formatBandBoundary, severityColor, type QualitySignal } from "./groupQuality";

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
  /** This group's `problematicGroups[].penaltySum` from the plan explanation (M7's Planeringsnivå),
   *  passed only for the top-3 (penaltySum > 0) groups ResultsPanel identifies - see its own javadoc.
   *  Feeds groupQuality.ts's "Störst poängavdrag i planen" warn signal (user feedback v0.4 #5). */
  penaltySum?: number;
  onExplain: (participantProfileId: string, name: string) => void;
  onTestMove: (participantProfileId: string, name: string) => void;
  onExplainGroup: (groupId: string, name: string) => void;
}

/** Finds the first signal (of possibly several) whose `key` is in `keys`, or `undefined` if the
 *  group has none of them (e.g. no target size configured, so neither "sizeAtTarget" nor
 *  "sizeBelow/AboveTarget" was ever emitted) - GroupCard's chip row falls back to a neutral gray in
 *  that case rather than guessing a color for a check that doesn't apply. */
function findSignal(signals: QualitySignal[], keys: QualitySignal["key"][]): QualitySignal | undefined {
  return signals.find((s) => keys.includes(s.key));
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
  penaltySum,
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

  // "Are these groups good?" at-a-glance signals (user feedback v0.4 #5) - drives the status
  // dot/top border and the compact chip row below, all from view-model fields this card already has.
  const quality = computeGroupQuality({
    size: members.length,
    minSize: group.minSize ?? null,
    targetSize: group.targetSize ?? null,
    maxSize: group.maxSize ?? null,
    requiredCoachCount: group.requiredCoachCount ?? null,
    coachCount: coaches.length,
    levelMean: levelStats.mean,
    levelSpread: levelStats.spread,
    levelMin: group.levelMin ?? null,
    levelMax: group.levelMax ?? null,
    penaltySum,
  });
  const statusColor = severityColor(quality.status);
  // Falls back to a neutral gray (not "ok"/teal) when the check simply doesn't apply to this group
  // (e.g. no target size configured at all) - a gray chip reads as "no data", a teal one would
  // falsely claim the check passed.
  const sizeSignal = findSignal(quality.signals, [
    "sizeBelowMin",
    "sizeAboveMax",
    "sizeBelowTarget",
    "sizeAboveTarget",
    "sizeAtTarget",
  ]);
  const coachSignal = findSignal(quality.signals, ["coachMissing", "coachBelowRequired", "coachInPlace"]);
  const levelSignal = findSignal(quality.signals, ["levelOutsideBand", "levelInsideBand"]);
  const sizeChipColor = sizeSignal ? severityColor(sizeSignal.severity) : "gray";
  const coachChipColor = coachSignal ? severityColor(coachSignal.severity) : "gray";
  const levelChipColor = levelSignal ? severityColor(levelSignal.severity) : "gray";

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
    <Card
      withBorder
      padding="md"
      data-testid="group-card"
      // "Are these groups good?" (user feedback v0.4 #5): a 3px colored top border gives the
      // group's overall quality status a glanceable presence even before reading any chip - purely
      // decorative (the same information is always spelled out in the chips/tooltip below).
      style={{ borderTopWidth: 3, borderTopColor: `var(--mantine-color-${statusColor}-6)` }}
    >
      <Group justify="space-between" mb={4}>
        <Group gap={6}>
          <Title order={5}>{group.name}</Title>
          <Tooltip
            label={
              <Stack gap={2}>
                {quality.signals.length > 0 ? (
                  quality.signals.map((signal) => (
                    <Text key={signal.key} size="xs">
                      {signal.textSv}
                    </Text>
                  ))
                ) : (
                  <Text size="xs">{sv.results.quality.noSignals}</Text>
                )}
              </Stack>
            }
            multiline
            w={260}
          >
            {/* Decorative supplement to the chips row below (which always spells the same signals
                out as visible text) - aria-hidden rather than a redundant announcement. */}
            <Box
              w={10}
              h={10}
              aria-hidden="true"
              style={{ borderRadius: "50%", backgroundColor: `var(--mantine-color-${statusColor}-6)`, flexShrink: 0 }}
            />
          </Tooltip>
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
      </Group>

      <Group gap={6} mb="xs" wrap="wrap">
        <Badge size="sm" variant="light" color={sizeChipColor}>
          {sv.results.groupCard.playersCount(members.length, group.targetSize ?? null, group.maxSize ?? null)}
        </Badge>
        <Badge size="sm" variant="light" color={coachChipColor}>
          {sv.results.quality.chips.coachLabel(coaches.length, group.requiredCoachCount ?? null)}
        </Badge>
        {levelStats.mean != null && (
          <Badge size="sm" variant="light" color={levelChipColor}>
            {sv.results.quality.chips.levelLabel(levelStats.mean, levelStats.spread ?? 0)}
          </Badge>
        )}
        {group.levelMin != null && group.levelMax != null && (
          <Text size="xs" c="dimmed">
            {sv.results.quality.chips.bandSuffix(formatBandBoundary(group.levelMin), formatBandBoundary(group.levelMax))}
          </Text>
        )}
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

      {/* v0.4.0 (user feedback v0.4 #5): the separate Nivåsnitt/Nivåspridning stat block folded into
          the "Nivå X (±Y)" chip above (same computeLevelStats numbers, still labeled Nivåsnitt/
          Nivåspridning in the group explain drawer's own detail view) - only the no-data case still
          needs its own line here, since the chip itself is omitted entirely when there's nothing to
          show. */}
      {levelStats.mean == null && (
        <Text size="xs" c="dimmed" mb="sm">
          {sv.results.groupCard.noLevelData}
        </Text>
      )}

      <Text size="sm" fw={500} mb={4}>
        {sv.results.groupCard.membersHeading}
      </Text>
      {/* v0.3.0 WI-6: wrapped in a ScrollContainer (same pattern as every other data table in the
          app) as a safety net for narrow windows - name + level + source badge + three compact
          action buttons are tightened (compact-xs buttons with reduced px, tight cell spacing,
          xs badge) to fit a 2-per-row card without scrolling at typical laptop widths;
          ScrollContainer still catches anything narrower than that. */}
      <Table.ScrollContainer minWidth={470}>
        <Table verticalSpacing={4} horizontalSpacing={6} withTableBorder striped>
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
                      px={6}
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
                        px={6}
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
                        px={6}
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
      </Table.ScrollContainer>
    </Card>
  );
}
