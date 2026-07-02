import { Group, SegmentedControl, Stack, Text } from "@mantine/core";
import { sv } from "../../../i18n/sv";
import type { TimeSlot } from "../../../api/types";
import { AVAILABILITY_UNKNOWN, type AvailabilityDraft, type AvailabilityDraftValue } from "./availability";

interface AvailabilityMatrixProps {
  timeSlots: TimeSlot[];
  draft: AvailabilityDraft;
  onChange: (timeSlotId: string, kind: AvailabilityDraftValue) => void;
  disabled?: boolean;
}

const OPTIONS: { value: AvailabilityDraftValue; label: string }[] = [
  { value: AVAILABILITY_UNKNOWN, label: sv.coaches.availabilityKind.UNKNOWN },
  { value: "UNAVAILABLE", label: sv.coaches.availabilityKind.UNAVAILABLE },
  { value: "AVAILABLE", label: sv.coaches.availabilityKind.AVAILABLE },
  { value: "PREFERRED", label: sv.coaches.availabilityKind.PREFERRED },
];

/**
 * Tränarvy availability matrix (spec §13.1): one row per plan time slot, each with a 4-state
 * segmented control (Okänd/Otillgänglig/Tillgänglig/Föredrar). "Okänd" is the draft's rest state -
 * a slot never explicitly touched, mapped to "no row in the PUT payload" by availability.ts.
 */
export function AvailabilityMatrix({ timeSlots, draft, onChange, disabled }: AvailabilityMatrixProps) {
  if (timeSlots.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        {sv.coaches.drawer.noTimeSlots}
      </Text>
    );
  }

  return (
    <Stack gap="xs">
      {timeSlots.map((slot) => (
        // role="group"/aria-label makes each row addressable on its own (spec §19.7's matrix has one
        // 4-option control per row - without this, "find the radio labeled Föredrar" is ambiguous
        // across rows, since every row's SegmentedControl shares the same four option labels).
        <Group key={slot.id} justify="space-between" role="group" aria-label={slot.label}>
          <Text size="sm">{slot.label}</Text>
          <SegmentedControl
            size="xs"
            disabled={disabled}
            data={OPTIONS}
            value={draft[slot.id] ?? AVAILABILITY_UNKNOWN}
            onChange={(value) => onChange(slot.id, value as AvailabilityDraftValue)}
          />
        </Group>
      ))}
    </Stack>
  );
}
