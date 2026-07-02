import { Alert, Stack, Text } from "@mantine/core";
import type { SeasonConflict } from "../api/types";
import { sv } from "../i18n/sv";

interface ConflictListProps {
  conflicts: SeasonConflict[];
}

/**
 * Renders a season's person/coach/court double-booking conflicts (spec §19.2/§14.4,
 * `GET /api/seasons/{seasonId}/conflicts`) as a stack of red alerts, one line per conflict:
 * "<type> — <who/what>: <plan A / group A (time)> ↔ <plan B / group B (time)>". Shared between the
 * per-plan Planeringskarta (ScheduleView.tsx, spec §19.11) and the Säsongsvy Konflikter panel
 * (SeasonPage.tsx, spec §19.2) so the two views never drift on how a conflict is described. Callers
 * own the surrounding heading/empty-state/loading/error chrome, since that copy differs by context.
 */
export function ConflictList({ conflicts }: ConflictListProps) {
  if (conflicts.length === 0) {
    return null;
  }
  return (
    <Stack gap={4} data-testid="conflict-list">
      {conflicts.map((conflict, index) => (
        <Alert key={index} color="red" py={6}>
          <Text size="sm">
            {sv.results.schedule.conflictType[conflict.type as keyof typeof sv.results.schedule.conflictType] ?? conflict.type}
            {conflict.personName ? ` — ${conflict.personName}` : conflict.courtName ? ` — ${conflict.courtName}` : ""}:{" "}
            {conflict.usages.map((usage) => `${usage.planName} / ${usage.groupName} (${usage.time})`).join(" ↔ ")}
          </Text>
        </Alert>
      ))}
    </Stack>
  );
}
