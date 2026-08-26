import { useEffect, useRef, useState } from "react";
import { Alert, Badge, Button, Group, Loader, Select, Stack, Table, Text, Title } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { useFieldDefinitions } from "../../../api/fieldDefinitions";
import { useImportColumns, useSetImportMapping, type ImportColumnMapping } from "../../../api/import";
import { ApiError, isNotFoundError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { useIsSimpleMode } from "../../../lib/uiMode/useUiMode";
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

/** v0.6.0 F4 (M-S4): the two coach-related standard targets - ADVANCED-only (STANDARD_TARGETS is
 *  filtered to exclude these as CHOOSABLE options in SIMPLE mode; a column the backend already
 *  auto-suggested one of these for renders as a disabled row instead - see MappingStep's render
 *  below - the mapping itself is never dropped).
 *
 *  Exported (v0.6.0 F4 review fix, FIX 6) so ReviewStep.tsx's one-click decisions table can filter
 *  the SAME coach-target columns out of its own rendering - one shared vocabulary for "is this
 *  column a coach-target row" rather than two independently maintained Sets. */
export const COACH_TARGETS = new Set(["coachName", "isCoach"]);

/** Wizard step 3 (spec §8.4): one row per source column, a target dropdown pre-filled from the
 *  backend's suggestion (template match or synonym/fuzzy match), custom-field targets from the
 *  plan's CUSTOM-storage field definitions, and a sensitive-data badge for comment targets. */
export function MappingStep({ planId, sessionId, onNext, onExpired }: MappingStepProps) {
  const columns = useImportColumns(planId, sessionId);
  const fieldDefinitions = useFieldDefinitions(planId);
  const setMapping = useSetImportMapping(planId, sessionId);
  const isSimple = useIsSimpleMode();

  const [targets, setTargets] = useState<Record<number, string>>({});
  const [newFieldModalColumn, setNewFieldModalColumn] = useState<number | null>(null);
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

  // v0.6.0 F4: coachName/isCoach are never CHOOSABLE targets in SIMPLE mode - a column already
  // suggested for one of them still renders (as a disabled row, see below), just not selectable
  // via this dropdown for any OTHER column either.
  const visibleStandardTargets = isSimple ? STANDARD_TARGETS.filter((target) => !COACH_TARGETS.has(target.value)) : STANDARD_TARGETS;

  const selectData = [
    { group: sv.importWizard.mapping.standardGroup, items: visibleStandardTargets },
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
      setNewFieldModalColumn(columnIndex);
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
              // v0.6.0 F4: a column the backend already auto-suggested coachName/isCoach for is
              // shown as a DISABLED row in SIMPLE mode rather than silently dropping the mapping -
              // the underlying `targets` state (and what gets submitted to the backend on "Nästa")
              // is untouched either way.
              const isCoachRow = isSimple && COACH_TARGETS.has(target);
              return (
                <Table.Tr key={column.columnIndex}>
                  <Table.Td>
                    <Group gap="xs" wrap="nowrap">
                      <Text fw={500}>{columnLabel}</Text>
                      {column.synthetic && (
                        <Badge color="blue" variant="light">
                          {sv.importWizard.mapping.derivedBadge}
                        </Badge>
                      )}
                    </Group>
                  </Table.Td>
                  {/* v0.6.0 F4 review fix (FIX 6, MAJOR): a disabled coach row used to still leak
                      its sample value (e.g. a real coach's name from the file) AND its target label
                      ("Önskad tränare (fritext)"/"Är tränare") even while "disabled" - both are
                      coach-identifying content that SIMPLE mode must never display, regardless of
                      whether the underlying mapping/import behavior stays unchanged (it does - see
                      COACH_TARGETS' own doc comment). Column header stays (it's the FILE's generic
                      column name, e.g. "Önskad tränare" - not a person). */}
                  {isCoachRow ? (
                    <>
                      <Table.Td />
                      <Table.Td>
                        <Text size="xs" c="dimmed" data-testid="mapping-coach-row-note">
                          {sv.uiMode.handledInAdvanced}
                        </Text>
                      </Table.Td>
                    </>
                  ) : (
                    <>
                      <Table.Td>
                        <Text size="sm" c="dimmed">
                          {column.sampleValues.join(", ") || "—"}
                        </Text>
                        {column.synthetic && (
                          <Text size="xs" c="dimmed" fs="italic">
                            {sv.importWizard.mapping.derivedHint}
                          </Text>
                        )}
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
                    </>
                  )}
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

      <NewCustomFieldModal
        planId={planId}
        opened={newFieldModalColumn !== null}
        onClose={() => setNewFieldModalColumn(null)}
        onCreated={(field) => {
          if (newFieldModalColumn !== null) {
            setTargets((prev) => ({ ...prev, [newFieldModalColumn]: `customField:${field.key}` }));
          }
        }}
      />
    </Stack>
  );
}
