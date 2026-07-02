import { useEffect, useRef, useState } from "react";
import { Alert, Badge, Button, Group, Loader, Select, Stack, Table, Text, Title } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { useFieldDefinitions } from "../../../api/fieldDefinitions";
import { useImportColumns, useSetImportMapping, type ImportColumnMapping } from "../../../api/import";
import { ApiError, isNotFoundError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { SessionExpiredPanel } from "../SessionExpiredPanel";
import { NewCustomFieldModal } from "../NewCustomFieldModal";

interface MappingStepProps {
  planId: string;
  sessionId: string;
  onNext: () => void;
  onExpired: () => void;
}

const IGNORE_VALUE = "ignore";
const CREATE_FIELD_VALUE = "__create_field__";

/** Targets a column can never be automatically suggested for creation (customField/ignore) are
 *  appended separately below; this is the fixed §8.4 top-level target vocabulary. Not "hardcoding
 *  column names" (CLAUDE.md forbids that) — this is the backend's own generic MappingTargetKind
 *  enum, identical for every imported file. */
const STANDARD_TARGETS: { value: string; label: string }[] = [
  { value: "firstName", label: sv.importWizard.mapping.targets.firstName },
  { value: "lastName", label: sv.importWizard.mapping.targets.lastName },
  { value: "displayName", label: sv.importWizard.mapping.targets.displayName },
  { value: "email", label: sv.importWizard.mapping.targets.email },
  { value: "phone", label: sv.importWizard.mapping.targets.phone },
  { value: "externalId", label: sv.importWizard.mapping.targets.externalId },
  { value: "rankingPoints", label: sv.importWizard.mapping.targets.rankingPoints },
  { value: "previousGroupName", label: sv.importWizard.mapping.targets.previousGroupName },
  { value: "previousGroupLevel", label: sv.importWizard.mapping.targets.previousGroupLevel },
  { value: "manualLevelScore", label: sv.importWizard.mapping.targets.manualLevelScore },
  { value: "comment", label: sv.importWizard.mapping.targets.comment },
  { value: "internalNote", label: sv.importWizard.mapping.targets.internalNote },
  { value: "coachName", label: sv.importWizard.mapping.targets.coachName },
  { value: "isCoach", label: sv.importWizard.mapping.targets.isCoach },
];

const SENSITIVE_TARGETS = new Set(["comment", "internalNote"]);

/** Wizard step 3 (spec §8.4): one row per source column, a target dropdown pre-filled from the
 *  backend's suggestion (template match or synonym/fuzzy match), custom-field targets from the
 *  plan's CUSTOM-storage field definitions, and a sensitive-data badge for comment targets. */
export function MappingStep({ planId, sessionId, onNext, onExpired }: MappingStepProps) {
  const columns = useImportColumns(planId, sessionId);
  const fieldDefinitions = useFieldDefinitions(planId);
  const setMapping = useSetImportMapping(planId, sessionId);

  const [targets, setTargets] = useState<Record<number, string>>({});
  const [newFieldModalOpen, setNewFieldModalOpen] = useState(false);
  const initialized = useRef(false);

  useEffect(() => {
    if (columns.data && !initialized.current) {
      const initial: Record<number, string> = {};
      for (const column of columns.data.columns) {
        initial[column.columnIndex] = column.suggestedTarget ?? IGNORE_VALUE;
      }
      setTargets(initial);
      initialized.current = true;
    }
  }, [columns.data]);

  if (columns.isError && isNotFoundError(columns.error)) {
    return <SessionExpiredPanel onRestart={onExpired} />;
  }
  if (columns.isError) {
    return (
      <Alert color="red">
        {columns.error instanceof ApiError ? columns.error.message : sv.common.unknownError}
      </Alert>
    );
  }
  if (columns.isLoading || !columns.data) {
    return <Loader />;
  }

  const customFieldOptions = (fieldDefinitions.data ?? [])
    .filter((field) => field.storageKind === "CUSTOM")
    .map((field) => ({ value: `customField:${field.key}`, label: field.label }));

  const selectData = [
    { group: sv.importWizard.mapping.standardGroup, items: STANDARD_TARGETS },
    ...(customFieldOptions.length > 0
      ? [{ group: sv.importWizard.mapping.customGroup, items: customFieldOptions }]
      : []),
    { value: CREATE_FIELD_VALUE, label: sv.importWizard.mapping.createFieldOption },
    { value: IGNORE_VALUE, label: sv.importWizard.mapping.ignoreOption },
  ];

  const handleTargetChange = (columnIndex: number, value: string | null) => {
    if (!value) {
      return;
    }
    if (value === CREATE_FIELD_VALUE) {
      setNewFieldModalOpen(true);
      return;
    }
    setTargets((prev) => ({ ...prev, [columnIndex]: value }));
  };

  const handleNext = async () => {
    const mappings: ImportColumnMapping[] = columns.data!.columns.map((column) => ({
      columnIndex: column.columnIndex,
      target: targets[column.columnIndex] ?? IGNORE_VALUE,
    }));
    try {
      await setMapping.mutateAsync({ sheet: columns.data!.sheet, mappings });
      onNext();
    } catch (error) {
      if (isNotFoundError(error)) {
        onExpired();
        return;
      }
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: error instanceof ApiError ? error.message : sv.importWizard.mapping.saveFailed,
      });
    }
  };

  return (
    <Stack gap="md">
      <Title order={4}>{sv.importWizard.mapping.heading}</Title>

      <Table.ScrollContainer minWidth={640}>
        <Table withTableBorder verticalSpacing="xs">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{sv.importWizard.mapping.columnHeader}</Table.Th>
              <Table.Th>{sv.importWizard.mapping.sampleHeader}</Table.Th>
              <Table.Th>{sv.importWizard.mapping.targetHeader}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {columns.data.columns.map((column) => {
              const target = targets[column.columnIndex] ?? IGNORE_VALUE;
              const columnLabel = column.headerText || `#${column.columnIndex + 1}`;
              return (
                <Table.Tr key={column.columnIndex}>
                  <Table.Td>
                    <Text fw={500}>{columnLabel}</Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" c="dimmed">
                      {column.sampleValues.join(", ") || "—"}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Group gap="xs" wrap="nowrap">
                      <Select
                        aria-label={`Mappning för kolumn ${columnLabel}`}
                        data={selectData}
                        value={target}
                        onChange={(value) => handleTargetChange(column.columnIndex, value)}
                        w={280}
                        comboboxProps={{ withinPortal: false }}
                      />
                      {SENSITIVE_TARGETS.has(target) && (
                        <Badge color="orange" variant="light">
                          {sv.importWizard.mapping.sensitiveBadge}
                        </Badge>
                      )}
                    </Group>
                  </Table.Td>
                </Table.Tr>
              );
            })}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>

      <Group justify="flex-end">
        <Button onClick={handleNext} loading={setMapping.isPending}>
          {sv.importWizard.mapping.nextButton}
        </Button>
      </Group>

      <NewCustomFieldModal opened={newFieldModalOpen} onClose={() => setNewFieldModalOpen(false)} />
    </Stack>
  );
}
