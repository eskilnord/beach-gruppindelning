import { MultiSelect, NumberInput, Select, Stack, Switch, TagsInput, Text, TextInput } from "@mantine/core";
import { sv } from "../../../i18n/sv";
import type { FieldDefinition, FieldValueView, TimeSlot } from "../../../api/types";
import type { ParticipantRow } from "./participantRow";

/** Monday..Sunday - mirrors backend TimeSlotRepository.findByActivityPlanId's weekdayOrdinal (the
 *  frontend UI only ever creates dayOfWeek-based slots, never the dated one-off variant - see
 *  TimeSlotModal.tsx - so no date-derived fallback is needed here). */
const DAY_ORDER = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];

function dayOrdinal(dayOfWeek: string | null | undefined): number {
  const index = dayOfWeek ? DAY_ORDER.indexOf(dayOfWeek) : -1;
  return index === -1 ? DAY_ORDER.length : index;
}

/** Sorted by day-of-week then start time, so a `timeRelation` MultiSelect's options read like the
 *  plan's actual weekly schedule rather than DB insertion order. */
function sortTimeSlots(slots: TimeSlot[]): TimeSlot[] {
  return [...slots].sort((a, b) => {
    const dayDiff = dayOrdinal(a.dayOfWeek) - dayOrdinal(b.dayOfWeek);
    return dayDiff !== 0 ? dayDiff : (a.startTime ?? "").localeCompare(b.startTime ?? "");
  });
}

/** Per-key description for the three seeded timeRelation standard fields (v0.3.0 WI-A: the field
 *  label alone - "Kan tider" etc. - doesn't say what it actually does); falls back to the generic
 *  hint for a council-created custom timeRelation field (the "Nytt fält" modal does allow that type,
 *  compatible with TIME_AVAILABILITY/TIME_PREFERENCE - constraintCompatibility.ts). */
function timeRelationDescription(key: string): string {
  switch (key) {
    case "canTimes":
      return sv.participants.drawer.canTimesDescription;
    case "cannotTimes":
      return sv.participants.drawer.cannotTimesDescription;
    case "preferTimes":
      return sv.participants.drawer.preferTimesDescription;
    default:
      return sv.participants.drawer.timeRelationHint;
  }
}

/** Minimal shape CustomFieldEditor needs for a coachRelation option - a real CoachProfile joined
 *  with its person's display name (mirrors ParticipantRow's "profile + name" shape). */
export interface CoachOption {
  id: string;
  name: string;
}

interface CustomFieldEditorProps {
  fieldValue: FieldValueView;
  definition: FieldDefinition | undefined;
  value: unknown;
  onChange: (value: unknown) => void;
  /** Options for a `personRelation` field. Pass `[]` where participants aren't loaded (e.g. a
   *  Tränarvy drawer that hasn't fetched them). */
  participants: ParticipantRow[];
  /** Options for a `coachRelation` field (arrives M5 - coaches didn't exist before). Pass `[]`
   *  where coaches aren't loaded/relevant. */
  coaches?: CoachOption[];
  /** Options for a `timeRelation` field (v0.3.0 WI-A - the plan's own time slots, replacing the old
   *  free-text picker). Pass `[]` where time slots aren't loaded/relevant. */
  timeSlots?: TimeSlot[];
  selfId: string;
}

function parseOptions(definition: FieldDefinition | undefined): string[] {
  if (!definition?.optionsJson) {
    return [];
  }
  try {
    const parsed: unknown = JSON.parse(definition.optionsJson);
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === "string") : [];
  } catch {
    return [];
  }
}

function asStringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}

/**
 * Renders one custom-field editor by fieldType (spec §9.3, Deltagarvy drawer's structured side,
 * §19.4; generalized in M5 for the Tränarvy drawer, which reuses this same component for a coach's
 * custom fields). `groupRelation` still has no listing endpoint (groups arrive M6) so it renders as
 * an informational note rather than a non-functional picker; `coachRelation` is a real picker now
 * that the coach register exists.
 */
export function CustomFieldEditor({
  fieldValue,
  definition,
  value,
  onChange,
  participants,
  coaches = [],
  timeSlots = [],
  selfId,
}: CustomFieldEditorProps) {
  const label = fieldValue.label;

  switch (fieldValue.fieldType) {
    case "text":
      return (
        <TextInput
          label={label}
          value={typeof value === "string" ? value : ""}
          onChange={(event) => onChange(event.currentTarget.value === "" ? null : event.currentTarget.value)}
        />
      );

    case "number":
      return (
        <NumberInput
          label={label}
          value={typeof value === "number" ? value : ""}
          onChange={(next) => onChange(next === "" ? null : Number(next))}
        />
      );

    case "boolean":
      return (
        <Switch
          label={label}
          checked={Boolean(value)}
          onChange={(event) => onChange(event.currentTarget.checked)}
        />
      );

    case "singleSelect":
      return (
        <Select
          label={label}
          data={parseOptions(definition)}
          value={typeof value === "string" ? value : null}
          onChange={(next) => onChange(next)}
          clearable
          comboboxProps={{ withinPortal: false }}
        />
      );

    case "multiSelect":
      return (
        <MultiSelect
          label={label}
          data={parseOptions(definition)}
          value={asStringArray(value)}
          onChange={(next) => onChange(next.length > 0 ? next : null)}
          comboboxProps={{ withinPortal: false }}
        />
      );

    case "tag":
      return (
        <TagsInput
          label={label}
          placeholder={sv.participants.drawer.tagPlaceholder}
          value={asStringArray(value)}
          onChange={(next) => onChange(next.length > 0 ? next : null)}
        />
      );

    case "personRelation": {
      const options = participants
        .filter((participant) => participant.id !== selfId)
        .map((participant) => ({ value: participant.id, label: participant.name }));
      return (
        <MultiSelect
          label={label}
          placeholder={sv.participants.drawer.personRelationPlaceholder}
          data={options}
          value={asStringArray(value)}
          onChange={(next) => onChange(next.length > 0 ? next : null)}
          searchable
          comboboxProps={{ withinPortal: false }}
        />
      );
    }

    case "timeRelation": {
      // Post-M6a storage is a JSON array of real time_slot ids (FieldValueService), but legacy
      // free-text values (pre-M6a) or shape-incompatible import leftovers (ImportCommitService skips
      // writing these now, but older data may still have them) can still be sitting in the DB - drop
      // anything that isn't a valid slot id in THIS plan rather than let the MultiSelect choke on it.
      const validSlotIds = new Set(timeSlots.map((slot) => slot.id));
      const rawValues = asStringArray(value);
      const selected = rawValues.filter((id) => validSlotIds.has(id));
      const hadInvalidValues = selected.length !== rawValues.length;
      const options = sortTimeSlots(timeSlots).map((slot) => ({ value: slot.id, label: slot.label }));
      return (
        <Stack gap={2}>
          <MultiSelect
            label={label}
            placeholder={sv.participants.drawer.timeRelationPlaceholder}
            description={timeRelationDescription(fieldValue.key)}
            data={options}
            value={selected}
            onChange={(next) => onChange(next.length > 0 ? next : null)}
            searchable
            comboboxProps={{ withinPortal: false }}
          />
          {hadInvalidValues && (
            <Text size="xs" c="dimmed">
              {sv.participants.drawer.timeRelationInvalidValuesNote}
            </Text>
          )}
        </Stack>
      );
    }

    case "coachRelation": {
      const options = coaches
        .filter((coach) => coach.id !== selfId)
        .map((coach) => ({ value: coach.id, label: coach.name }));
      return (
        <MultiSelect
          label={label}
          placeholder={sv.coaches.coachRelationPlaceholder}
          data={options}
          value={asStringArray(value)}
          onChange={(next) => onChange(next.length > 0 ? next : null)}
          searchable
          comboboxProps={{ withinPortal: false }}
        />
      );
    }

    case "groupRelation":
    default:
      return (
        <div>
          <Text size="sm" fw={500}>
            {label}
          </Text>
          <Text size="sm" c="dimmed">
            {sv.participants.drawer.relationUnavailable}
          </Text>
        </div>
      );
  }
}
