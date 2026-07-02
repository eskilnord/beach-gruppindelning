import { MultiSelect, NumberInput, Select, Switch, TagsInput, Text, TextInput } from "@mantine/core";
import { sv } from "../../../i18n/sv";
import type { FieldDefinition, FieldValueView } from "../../../api/types";
import type { ParticipantRow } from "./participantRow";

interface CustomFieldEditorProps {
  fieldValue: FieldValueView;
  definition: FieldDefinition | undefined;
  value: unknown;
  onChange: (value: unknown) => void;
  participants: ParticipantRow[];
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
 * §19.4). `coachRelation`/`groupRelation` have no listing endpoint yet (coaches/groups arrive M5) so
 * they render as an informational note rather than a non-functional picker.
 */
export function CustomFieldEditor({ fieldValue, definition, value, onChange, participants, selfId }: CustomFieldEditorProps) {
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
      const text = asStringArray(value)[0] ?? "";
      return (
        <TextInput
          label={label}
          placeholder={sv.participants.drawer.timeRelationHint}
          description={sv.participants.drawer.timeRelationHint}
          defaultValue={text}
          onBlur={(event) => {
            const next = event.currentTarget.value.trim();
            onChange(next.length > 0 ? [next] : null);
          }}
        />
      );
    }

    case "coachRelation":
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
