import { memo, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Anchor, Badge, Card, Group as MantineGroup, SimpleGrid, Stack, Text, Title } from "@mantine/core";
import type { LiveGroup, LivePlayer, LiveSnapshot } from "../../../api/types";
import { sv } from "../../../i18n/sv";
import { formatScoreLine } from "./scoreFormat";

interface LiveSolveViewProps {
  planId: string;
  snapshot: LiveSnapshot;
  /** True while the solve that produced `snapshot` is still SOLVING_ACTIVE/SCHEDULED - false once it
   *  has settled, at which point this view keeps rendering the LAST frame (dimmed, with a "go to
   *  Resultat" hint) rather than disappearing (the parent stops polling, see `useLiveSolution`). */
  running: boolean;
}

/** Sentinel "group" key for the waitlist bucket in the moved-player tracking map below - distinct
 *  from any real `groupId` (a TEXT UUIDv7), so it can never collide. */
const WAITLIST_KEY = "__waitlist__";

/** Order-independent identity of a group's current membership - two frames with the SAME set of
 *  players (regardless of the solver's internal list order) must compare equal so `LiveGroupBox`'s
 *  memo can skip re-rendering a group nothing happened to. */
function groupSignature(group: LiveGroup): string {
  return group.players
    .map((p) => p.participantProfileId)
    .sort()
    .join(",");
}

interface LevelRange {
  min: number;
  max: number;
}

/** Relative (not absolute-threshold) level tint: blue for the lowest levelScaled in THIS snapshot,
 *  orange for the highest, interpolated in between - works regardless of the club's actual level
 *  scale/domain (spec gives no fixed 1-10 or 0-1000 convention to hardcode against). */
function levelDotColor(levelScaled: number, range: LevelRange): string {
  if (range.max <= range.min) {
    return "var(--mantine-color-blue-5)";
  }
  const fraction = Math.min(1, Math.max(0, (levelScaled - range.min) / (range.max - range.min)));
  const hue = 210 - fraction * 190; // 210° (blue, lowest) -> ~20° (orange, highest).
  return `hsl(${hue.toFixed(0)}, 65%, 45%)`;
}

interface PlayerChipsProps {
  players: LivePlayer[];
  movedParticipantIds: Set<string>;
  flashSeed: number;
  levelRange: LevelRange;
  emptyLabel: string;
}

function PlayerChips({ players, movedParticipantIds, flashSeed, levelRange, emptyLabel }: PlayerChipsProps) {
  if (players.length === 0) {
    return (
      <Text size="xs" c="dimmed">
        {emptyLabel}
      </Text>
    );
  }
  return (
    <>
      {players.map((player) => {
        const flashed = movedParticipantIds.has(player.participantProfileId);
        return (
          <Badge
            // Remounting on a flash (key changes) forces the browser to replay the CSS animation -
            // without this, re-applying an unchanged class name across renders does not restart it.
            key={flashed ? `${player.participantProfileId}-flash-${flashSeed}` : player.participantProfileId}
            variant="light"
            size="sm"
            className={flashed ? "gp-live-flash" : undefined}
            leftSection={
              <span
                aria-hidden="true"
                style={{
                  display: "inline-block",
                  width: 6,
                  height: 6,
                  borderRadius: "50%",
                  background: levelDotColor(player.levelScaled, levelRange),
                }}
              />
            }
          >
            {player.displayName}
          </Badge>
        );
      })}
    </>
  );
}

interface LiveGroupBoxProps {
  group: LiveGroup;
  movedParticipantIds: Set<string>;
  flashSeed: number;
  levelRange: LevelRange;
}

/** Memoized so a group whose membership didn't change between two polled frames (the common case -
 *  most groups are stable while the solver reshuffles a handful of players elsewhere) skips
 *  re-rendering entirely, per the custom comparator below. */
const LiveGroupBox = memo(
  function LiveGroupBox({ group, movedParticipantIds, flashSeed, levelRange }: LiveGroupBoxProps) {
    return (
      <Card withBorder padding="xs" data-testid="live-group">
        <Text fw={600} size="sm" mb={4} truncate>
          {group.name}
        </Text>
        <MantineGroup gap={4} wrap="wrap">
          <PlayerChips
            players={group.players}
            movedParticipantIds={movedParticipantIds}
            flashSeed={flashSeed}
            levelRange={levelRange}
            emptyLabel={sv.optimize.live.emptyGroup}
          />
        </MantineGroup>
      </Card>
    );
  },
  (prev, next) =>
    prev.levelRange.min === next.levelRange.min &&
    prev.levelRange.max === next.levelRange.max &&
    groupSignature(prev.group) === groupSignature(next.group) &&
    // Even with unchanged membership, a group must re-render if one of ITS players is flashing this
    // frame (only possible if membership actually differs too, in practice - kept as a defensive
    // belt-and-braces check rather than relying solely on the signature comparison above).
    !next.group.players.some((p) => next.movedParticipantIds.has(p.participantProfileId)),
);

/**
 * v0.3.0 WI-2 ("se det live"): a compact live grid of every group + the waitlist, refreshed from
 * `useLiveSolution`'s polled snapshot while a non-GREEDY solve runs. Players that moved to a
 * different group (or in/out of the waitlist) since the PREVIOUS frame this component actually
 * rendered get a one-shot flash (`gp-live-flash`); the score/improvement line pulses on every new
 * frame. Deliberately a lighter component than `GroupCard` (no lock toggles, no explain/what-if
 * actions - this is a live spectacle, not an editable results view).
 */
export function LiveSolveView({ planId, snapshot, running }: LiveSolveViewProps) {
  const navigate = useNavigate();

  // Tracks each participant's group (or WAITLIST_KEY) as of the last frame this component actually
  // processed - a plain ref, not state, since it must survive across renders without itself
  // triggering one. Starts empty on every MOUNT, which is exactly what we want: OptimizePanel only
  // mounts this component once a snapshot exists, and unmounts it when `useLiveSolution`'s cached
  // data is cleared at the start of a NEW solve (see `useStartSolve`) - so a fresh mount naturally
  // means "no flashes on the new run's first frame", without any explicit runId bookkeeping here.
  const previousGroupByParticipantIdRef = useRef<Map<string, string>>(new Map());
  const lastProcessedSequenceRef = useRef<number | null>(null);
  const [movedParticipantIds, setMovedParticipantIds] = useState<Set<string>>(new Set());
  const [flashSeed, setFlashSeed] = useState(0);

  useEffect(() => {
    if (lastProcessedSequenceRef.current === snapshot.sequence) {
      return; // Same frame re-rendered for an unrelated reason (e.g. a parent re-render) - no new moves.
    }
    const currentGroupByParticipantId = new Map<string, string>();
    for (const group of snapshot.groups) {
      for (const player of group.players) {
        currentGroupByParticipantId.set(player.participantProfileId, group.groupId);
      }
    }
    for (const player of snapshot.waitlist) {
      currentGroupByParticipantId.set(player.participantProfileId, WAITLIST_KEY);
    }

    const previous = previousGroupByParticipantIdRef.current;
    const moved = new Set<string>();
    if (previous.size > 0) {
      // No flashes on the very first frame processed (nothing to compare against yet).
      for (const [participantId, groupKey] of currentGroupByParticipantId) {
        const previousGroupKey = previous.get(participantId);
        if (previousGroupKey !== undefined && previousGroupKey !== groupKey) {
          moved.add(participantId);
        }
      }
    }

    previousGroupByParticipantIdRef.current = currentGroupByParticipantId;
    lastProcessedSequenceRef.current = snapshot.sequence;
    setMovedParticipantIds(moved);
    if (moved.size > 0) {
      setFlashSeed((seed) => seed + 1);
    }
  }, [snapshot]);

  const levelRange = useMemo<LevelRange>(() => {
    let min = Infinity;
    let max = -Infinity;
    for (const group of snapshot.groups) {
      for (const player of group.players) {
        min = Math.min(min, player.levelScaled);
        max = Math.max(max, player.levelScaled);
      }
    }
    for (const player of snapshot.waitlist) {
      min = Math.min(min, player.levelScaled);
      max = Math.max(max, player.levelScaled);
    }
    return Number.isFinite(min) && Number.isFinite(max) ? { min, max } : { min: 0, max: 0 };
  }, [snapshot]);

  return (
    <Card withBorder padding="md" data-testid="live-solve-view" style={running ? undefined : { opacity: 0.6 }}>
      <MantineGroup justify="space-between" mb="xs">
        <Title order={5}>{sv.optimize.live.heading}</Title>
        {/* Warm sand accent (v0.3.0 WI-6 palette) - the brief calls this out by name as one of the
            sparing places the accent color should show up, since it's the one piece of text that
            visibly ticks forward on every polled frame ("it's alive"). */}
        <Text key={snapshot.sequence} fw={600} size="sm" c="sand.7" className="gp-live-pulse">
          {sv.optimize.live.improvementNumber(snapshot.improvementCount)}
        </Text>
      </MantineGroup>
      {/* Distinct testid from the existing solve-progress card's `live-score-line` (OptimizePanel.tsx)
          - both render simultaneously while a solve is running, so reusing that id would create a
          duplicate on the page and break its Playwright selector's single-match assumption. */}
      <Text size="sm" c="dimmed" mb="sm" data-testid="live-solve-score-line">
        {formatScoreLine({ hard: snapshot.hard, soft: snapshot.soft, unassignedCount: snapshot.waitlist.length })}
      </Text>

      <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="xs" mb="sm">
        {snapshot.groups.map((group) => (
          <LiveGroupBox
            key={group.groupId}
            group={group}
            movedParticipantIds={movedParticipantIds}
            flashSeed={flashSeed}
            levelRange={levelRange}
          />
        ))}
      </SimpleGrid>

      <Card withBorder padding="xs" data-testid="live-waitlist">
        <Text fw={600} size="sm" mb={4}>
          {sv.optimize.live.waitlistLabel(snapshot.waitlist.length)}
        </Text>
        <MantineGroup gap={4} wrap="wrap">
          <PlayerChips
            players={snapshot.waitlist}
            movedParticipantIds={movedParticipantIds}
            flashSeed={flashSeed}
            levelRange={levelRange}
            emptyLabel={sv.optimize.live.emptyWaitlist}
          />
        </MantineGroup>
      </Card>

      {!running && (
        <Stack gap={2} mt="sm">
          <Text size="sm" c="dimmed">
            {sv.optimize.live.finishedHint}{" "}
            <Anchor size="sm" component="button" type="button" onClick={() => navigate(`/plans/${planId}/resultat`)}>
              {sv.optimize.live.goToResultsLink}
            </Anchor>
          </Text>
        </Stack>
      )}
    </Card>
  );
}
