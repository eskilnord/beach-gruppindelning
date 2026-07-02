import { useEffect, useState } from "react";
import { Badge, Button, NumberInput, SegmentedControl, Select, Switch, Table, Text, TextInput, Tooltip } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { useDeleteFieldDefinition, useUpdateFieldDefinition } from "../../../api/fieldDefinitions";
import { ApiError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import type { FieldDefinition, UpdateFieldDefinitionRequest } from "../../../api/types";
import { DeleteConfirmModal } from "../../../components/DeleteConfirmModal";
import {
  canAffectOptimization,
  compatibleConstraintFamilies,
  constraintFamilyLabel,
  fieldTypeLabel,
  hardOrSoftLabel,
} from "./constraintCompatibility";

const DEFAULT_SOFT_WEIGHT = 50;

interface FieldRowProps {
  field: FieldDefinition;
  planId: string;
}

/**
 * One row of the Fältbyggare table (spec §19.5). Standard fields only expose the optimization-facing
 * columns (affectsOptimization/constraint/hardOrSoft/weight/explanation, matching the backend's PATCH
 * restriction - see FieldDefinitionController javadoc); custom fields additionally allow editing the
 * label and can be deleted.
 */
export function FieldRow({ field, planId }: FieldRowProps) {
  const updateField = useUpdateFieldDefinition(planId);
  const deleteField = useDeleteFieldDefinition(planId);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [labelDraft, setLabelDraft] = useState(field.label);
  const [explanationDraft, setExplanationDraft] = useState(field.explanationText ?? "");
  const [weightDraft, setWeightDraft] = useState<number | "">(field.weight ?? "");

  useEffect(() => setLabelDraft(field.label), [field.label]);
  useEffect(() => setExplanationDraft(field.explanationText ?? ""), [field.explanationText]);
  useEffect(() => setWeightDraft(field.weight ?? ""), [field.weight]);

  // The 'priority' standard field is seeded with hardOrSoft=MEDIUM directly by the V2 migration
  // (reserved for the future unassignedPlayer waitlist constraint, ADR-006). The backend allows
  // editing weight (floor >= 1), constraintType, direction and explanationText on it, but rejects
  // reclassifying hardOrSoft away from MEDIUM and turning affectsOptimization off (verified against
  // the live FieldDefinitionValidator behavior) - so only those two controls are locked, with an
  // explanatory tooltip, and everything else stays editable.
  const mediumReserved = field.hardOrSoft === "MEDIUM";

  const compatibleFamilies = compatibleConstraintFamilies(field.fieldType).filter((family) => family !== "NONE");
  const canOptimize = canAffectOptimization(field.fieldType);

  const runUpdate = (body: UpdateFieldDefinitionRequest) => {
    updateField.mutate(
      { id: field.id, body },
      {
        onError: (error) => {
          notifications.show({
            color: "red",
            title: sv.common.error,
            message: error instanceof ApiError ? error.message : sv.fieldBuilder.updateFailed,
          });
        },
      },
    );
  };

  const handleAffectsOptimizationChange = (checked: boolean) => {
    if (checked) {
      runUpdate({
        affectsOptimization: true,
        constraintType: compatibleFamilies[0] ?? "NONE",
        hardOrSoft: "SOFT",
        weight: DEFAULT_SOFT_WEIGHT,
      });
    } else {
      runUpdate({ affectsOptimization: false });
    }
  };

  const handleConstraintChange = (value: string | null) => {
    if (value) {
      runUpdate({ constraintType: value });
    }
  };

  const handleHardOrSoftChange = (value: string) => {
    runUpdate({
      hardOrSoft: value,
      weight: value === "SOFT" ? (field.weight ?? DEFAULT_SOFT_WEIGHT) : undefined,
    });
  };

  const commitWeight = () => {
    if (weightDraft !== "" && Number(weightDraft) !== field.weight) {
      runUpdate({ weight: Number(weightDraft) });
    }
  };

  const commitLabel = () => {
    if (labelDraft.trim().length > 0 && labelDraft !== field.label) {
      runUpdate({ label: labelDraft.trim() });
    } else {
      setLabelDraft(field.label);
    }
  };

  const commitExplanation = () => {
    if (explanationDraft !== (field.explanationText ?? "")) {
      runUpdate({ explanationText: explanationDraft });
    }
  };

  return (
    <Table.Tr>
      <Table.Td>
        {!field.isStandard ? (
          <TextInput
            size="xs"
            value={labelDraft}
            onChange={(event) => setLabelDraft(event.currentTarget.value)}
            onBlur={commitLabel}
            aria-label={`Etikett för ${field.label}`}
          />
        ) : (
          <Text size="sm">{field.label}</Text>
        )}
        <Badge size="xs" variant="light" color={field.isStandard ? "gray" : "blue"} mt={4}>
          {field.isStandard ? sv.fieldBuilder.standardBadge : sv.fieldBuilder.customBadge}
        </Badge>
      </Table.Td>
      <Table.Td>
        <Text size="sm">{fieldTypeLabel(field.fieldType)}</Text>
      </Table.Td>
      <Table.Td>
        <Tooltip
          label={mediumReserved ? sv.fieldBuilder.mediumReservedTooltip : sv.fieldBuilder.newFieldModal.noCompatibleConstraint}
          disabled={canOptimize && !mediumReserved}
        >
          <Switch
            aria-label={`Påverkar optimering för ${field.label}`}
            checked={field.affectsOptimization}
            disabled={mediumReserved || !canOptimize || updateField.isPending}
            onChange={(event) => handleAffectsOptimizationChange(event.currentTarget.checked)}
          />
        </Tooltip>
      </Table.Td>
      <Table.Td>
        {field.affectsOptimization ? (
          <Select
            aria-label={`Constraint för ${field.label}`}
            size="xs"
            w={170}
            data={compatibleFamilies.map((family) => ({ value: family, label: constraintFamilyLabel(family) }))}
            value={field.constraintType}
            onChange={handleConstraintChange}
            disabled={updateField.isPending}
            allowDeselect={false}
            comboboxProps={{ withinPortal: false }}
          />
        ) : (
          <Text size="sm" c="dimmed">
            —
          </Text>
        )}
      </Table.Td>
      <Table.Td>
        {mediumReserved ? (
          <Tooltip label={sv.fieldBuilder.mediumReservedTooltip}>
            <Badge size="sm" variant="light" color="grape">
              {sv.hardOrSoft.MEDIUM}
            </Badge>
          </Tooltip>
        ) : field.affectsOptimization ? (
          <SegmentedControl
            size="xs"
            disabled={updateField.isPending}
            data={[
              { value: "HARD", label: sv.hardOrSoft.HARD },
              { value: "SOFT", label: sv.hardOrSoft.SOFT },
            ]}
            value={field.hardOrSoft ?? "SOFT"}
            onChange={handleHardOrSoftChange}
          />
        ) : (
          <Badge size="sm" variant="light" color="gray">
            {hardOrSoftLabel(field.hardOrSoft)}
          </Badge>
        )}
      </Table.Td>
      <Table.Td>
        {field.affectsOptimization && (field.hardOrSoft === "SOFT" || mediumReserved) ? (
          <NumberInput
            aria-label={`Vikt för ${field.label}`}
            size="xs"
            w={90}
            min={1}
            value={weightDraft}
            disabled={updateField.isPending}
            onChange={(value) => setWeightDraft(value === "" ? "" : Number(value))}
            onBlur={commitWeight}
          />
        ) : (
          <Text size="sm" c="dimmed">
            —
          </Text>
        )}
      </Table.Td>
      <Table.Td>
        <TextInput
          aria-label={`Förklaringstext för ${field.label}`}
          size="xs"
          value={explanationDraft}
          onChange={(event) => setExplanationDraft(event.currentTarget.value)}
          onBlur={commitExplanation}
        />
      </Table.Td>
      <Table.Td>
        {!field.isStandard && (
          <Button size="xs" color="red" variant="subtle" onClick={() => setDeleteOpen(true)}>
            {sv.fieldBuilder.deleteButton}
          </Button>
        )}
      </Table.Td>

      <DeleteConfirmModal
        opened={deleteOpen}
        title={sv.fieldBuilder.deleteModal.title}
        message={sv.fieldBuilder.deleteModal.message(field.label)}
        confirmLabel={sv.fieldBuilder.deleteModal.confirm}
        loading={deleteField.isPending}
        onClose={() => setDeleteOpen(false)}
        onConfirm={() => {
          deleteField.mutate(field.id, {
            onSuccess: () => setDeleteOpen(false),
            onError: (error) => {
              notifications.show({
                color: "red",
                title: sv.common.error,
                message: error instanceof ApiError ? error.message : sv.fieldBuilder.deleteModal.failed,
              });
            },
          });
        }}
      />
    </Table.Tr>
  );
}
