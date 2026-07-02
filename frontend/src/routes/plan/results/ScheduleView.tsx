import { Fragment } from "react";
import { Alert, Badge, Loader, Stack, Text, Title } from "@mantine/core";
import { ApiError } from "../../../api/client";
import { useSeasonConflicts } from "../../../api/conflicts";
import type { SeasonConflict, SlotBlocksView, TrainingGroup } from "../../../api/types";
import { ConflictList } from "../../../components/ConflictList";
import { sv } from "../../../i18n/sv";
import { buildScheduleGrid, type ScheduleCell } from "./scheduleGrid";

interface ScheduleViewProps {
  planId: string;
  seasonPlanId: string | undefined;
  slotBlocks: SlotBlocksView[];
  groups: TrainingGroup[];
  coachNameByGroupId: Record<string, string | null | undefined>;
}

/** Every conflict whose `usages` include this exact (plan, group, time) triple - see
 *  ConflictService's javadoc: a conflict's `usages` always has exactly one entry per involved plan,
 *  so matching on our own planId + this cell's group/time reliably finds the ones that touch it,
 *  regardless of conflict type (person/coach/court). */
function conflictsForCell(conflicts: SeasonConflict[], planId: string, groupName: string, time: string): SeasonConflict[] {
  return conflicts.filter((conflict) =>
    conflict.usages.some((usage) => usage.planId === planId && usage.groupName === groupName && usage.time === time),
  );
}

function cellContent(cell: ScheduleCell): string {
  if (cell.kind === "none") {
    return "";
  }
  if (cell.kind === "inactive") {
    return sv.results.schedule.inactive;
  }
  if (cell.kind === "empty") {
    return sv.results.schedule.free;
  }
  return cell.coachName ? `${cell.groupName} / ${cell.coachName}` : cell.groupName;
}

/**
 * Planeringskarta (spec §19.11): a hand-rolled CSS grid (no library, per the milestone brief) - rows
 * = time slots (backend-ordered chronologically), columns = courts. Conflicts from
 * `GET /api/seasons/{seasonId}/conflicts` are listed above the grid and marked as red badges on the
 * affected cells.
 */
export function ScheduleView({ planId, seasonPlanId, slotBlocks, groups, coachNameByGroupId }: ScheduleViewProps) {
  const conflicts = useSeasonConflicts(seasonPlanId);
  const grid = buildScheduleGrid(slotBlocks, groups, coachNameByGroupId);

  const conflictList = conflicts.data ?? [];

  return (
    <Stack gap="md">
      <div>
        <Title order={5} mb="xs">
          {sv.results.schedule.conflictsHeading}
        </Title>
        {conflicts.isLoading && <Loader size="sm" />}
        {conflicts.isError && (
          <Alert color="red">{conflicts.error instanceof ApiError ? conflicts.error.message : sv.results.schedule.loadFailed}</Alert>
        )}
        {conflicts.data && conflictList.length === 0 && <Text c="dimmed">{sv.results.schedule.noConflicts}</Text>}
        <ConflictList conflicts={conflictList} />
      </div>

      {grid.rows.length === 0 ? (
        <Text c="dimmed">{sv.results.empty}</Text>
      ) : (
        <div
          data-testid="schedule-grid"
          style={{
            display: "grid",
            gridTemplateColumns: `140px repeat(${grid.courts.length}, minmax(160px, 1fr))`,
            gap: 1,
            backgroundColor: "var(--mantine-color-gray-3)",
            border: "1px solid var(--mantine-color-gray-3)",
          }}
        >
          <div style={{ backgroundColor: "var(--mantine-color-gray-0)", padding: 8, fontWeight: 600 }}>
            {sv.results.schedule.timeColumn}
          </div>
          {grid.courts.map((court) => (
            <div key={court.id} style={{ backgroundColor: "var(--mantine-color-gray-0)", padding: 8, fontWeight: 600 }}>
              {court.name}
            </div>
          ))}

          {grid.rows.map((row) => (
            <Fragment key={row.timeSlotId}>
              <div style={{ backgroundColor: "white", padding: 8, fontWeight: 500 }}>{row.label}</div>
              {row.cells.map((cell, cellIndex) => {
                const cellConflicts =
                  cell.kind === "group" ? conflictsForCell(conflictList, planId, cell.groupName, row.label) : [];
                const inactive = cell.kind === "inactive";
                const empty = cell.kind === "empty";
                return (
                  <div
                    key={`${row.timeSlotId}-${cellIndex}`}
                    data-testid="schedule-cell"
                    style={{
                      backgroundColor: "white",
                      padding: 8,
                      backgroundImage: inactive
                        ? "repeating-linear-gradient(45deg, var(--mantine-color-gray-2), var(--mantine-color-gray-2) 6px, white 6px, white 12px)"
                        : undefined,
                      color: empty || inactive ? "var(--mantine-color-dimmed)" : undefined,
                      position: "relative",
                    }}
                  >
                    <Text size="sm">{cellContent(cell)}</Text>
                    {cellConflicts.length > 0 && (
                      <Badge color="red" size="xs" mt={4} data-testid="cell-conflict-badge">
                        {sv.results.schedule.conflictBadge} ({cellConflicts.length})
                      </Badge>
                    )}
                  </div>
                );
              })}
            </Fragment>
          ))}
        </div>
      )}
    </Stack>
  );
}
