import { useEffect, useState } from "react";
import { Alert, Badge, Button, Group, Loader, NumberInput, SegmentedControl, Stack, Switch, Table, Text, Tooltip } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { useConstraintDefinitions } from "../../../api/constraintDefinitions";
import { useConstraintWeights, useUpdateConstraintWeights } from "../../../api/constraintWeights";
import { ApiError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { HelpTip } from "../../../components/HelpTip";
import type { ConstraintDefinition, ConstraintWeightOverrideRequest, ConstraintWeightView } from "../../../api/types";

interface ConstraintWeightsTableProps {
  planId: string;
}

/**
 * "Konfiguration" section of Fältbyggaren (spec §9.4/§7.16): the 24 standard constraints, merged
 * with this plan's overrides. Reclassification is restricted to HARD/SOFT (no MEDIUM in the MVP UI,
 * ADR-006) - a future MEDIUM-classified row (M6a's `unassignedPlayer` waitlist constraint) is
 * rendered read-only, mirroring the guardrail already enforced by ConstraintWeightService.
 */
export function ConstraintWeightsTable({ planId }: ConstraintWeightsTableProps) {
  const weights = useConstraintWeights(planId);
  const definitions = useConstraintDefinitions();

  const defaultsByKey = new Map<string, ConstraintDefinition>((definitions.data ?? []).map((def) => [def.key, def]));

  return (
    <Stack gap="sm">
      <div>
        <Group gap={4}>
          <Text fw={500}>{sv.constraintWeights.heading}</Text>
          <HelpTip label={sv.help.ariaLabel(sv.constraintWeights.heading)}>{sv.help.constraintWeights.section}</HelpTip>
        </Group>
        <Text size="sm" c="dimmed">
          {sv.constraintWeights.subheading}
        </Text>
      </div>

      {(weights.isLoading || definitions.isLoading) && <Loader size="sm" />}
      {weights.isError && (
        <Alert color="red">
          {weights.error instanceof ApiError ? weights.error.message : sv.constraintWeights.loadFailed}
        </Alert>
      )}

      {weights.data && (
        <Table.ScrollContainer minWidth={820}>
          <Table verticalSpacing="xs" withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{sv.constraintWeights.table.label}</Table.Th>
                <Table.Th>{sv.constraintWeights.table.category}</Table.Th>
                <Table.Th>
                  <Group gap={4} wrap="nowrap">
                    {sv.constraintWeights.table.hardOrSoft}
                    <HelpTip label={sv.help.ariaLabel(sv.constraintWeights.table.hardOrSoft)}>
                      {sv.help.fields.hardOrSoft}
                    </HelpTip>
                  </Group>
                </Table.Th>
                <Table.Th>
                  <Group gap={4} wrap="nowrap">
                    {sv.constraintWeights.table.weight}
                    <HelpTip label={sv.help.ariaLabel(sv.constraintWeights.table.weight)}>
                      {sv.help.fields.weightInTable}
                    </HelpTip>
                  </Group>
                </Table.Th>
                <Table.Th>
                  <Group gap={4} wrap="nowrap">
                    {sv.constraintWeights.table.enabled}
                    <HelpTip label={sv.help.ariaLabel(sv.constraintWeights.table.enabled)}>
                      {sv.help.constraintWeights.enabled}
                    </HelpTip>
                  </Group>
                </Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {weights.data.map((constraint) => (
                <ConstraintWeightRow
                  key={constraint.key}
                  constraint={constraint}
                  definition={defaultsByKey.get(constraint.key)}
                  planId={planId}
                />
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
    </Stack>
  );
}

interface ConstraintWeightRowProps {
  constraint: ConstraintWeightView;
  definition: ConstraintDefinition | undefined;
  planId: string;
}

function ConstraintWeightRow({ constraint, definition, planId }: ConstraintWeightRowProps) {
  const updateWeights = useUpdateConstraintWeights(planId);
  const [weightDraft, setWeightDraft] = useState<number | "">(constraint.weight);

  useEffect(() => setWeightDraft(constraint.weight), [constraint.weight]);

  // Reserved system constraint (ADR-006, e.g. the future unassignedPlayer waitlist penalty) - the
  // backend rejects disabling or reclassifying it away from MEDIUM (ConstraintWeightService
  // .validateReclassification), so those two controls are locked with a tooltip; its weight
  // (floor >= 1) stays editable.
  const reserved = constraint.hardOrSoft === "MEDIUM";

  const isModifiedFromDefault =
    definition !== undefined &&
    (constraint.hardOrSoft !== definition.hardOrSoft ||
      constraint.weight !== definition.defaultWeight ||
      constraint.enabled !== definition.enabled);

  const applyOverride = (override: Omit<ConstraintWeightOverrideRequest, "key">) => {
    updateWeights.mutate([{ key: constraint.key, ...override }], {
      onError: (error) => {
        notifications.show({
          color: "red",
          title: sv.common.error,
          message: error instanceof ApiError ? error.message : sv.constraintWeights.updateFailed,
        });
      },
    });
  };

  const commitWeight = () => {
    if (weightDraft !== "" && Number(weightDraft) !== constraint.weight) {
      applyOverride({ weight: Number(weightDraft) });
    }
  };

  const handleReset = () => {
    if (!definition) {
      return;
    }
    // Weight is always sent (not only for SOFT): omitting it would keep a stale override weight
    // from an earlier SOFT phase alive in constraint_weight_config, so the row would still compare
    // as modified-from-default after a reset. For a reserved MEDIUM row this sends
    // hardOrSoft=MEDIUM + default enabled, which the guardrail accepts (no reclassification).
    applyOverride({
      hardOrSoft: definition.hardOrSoft,
      weight: definition.defaultWeight,
      enabled: definition.enabled,
    });
  };

  return (
    <Table.Tr>
      <Table.Td>
        <Text size="sm">{constraint.label}</Text>
        <Text size="xs" c="dimmed">
          {constraint.description}
        </Text>
      </Table.Td>
      <Table.Td>
        <Text size="sm">{constraint.constraintCategory}</Text>
      </Table.Td>
      <Table.Td>
        {reserved ? (
          <Tooltip label={sv.fieldBuilder.mediumReservedTooltip}>
            <Badge color="grape" variant="light">
              {sv.hardOrSoft.MEDIUM}
            </Badge>
          </Tooltip>
        ) : (
          <SegmentedControl
            size="xs"
            disabled={updateWeights.isPending}
            data={[
              { value: "HARD", label: sv.hardOrSoft.HARD },
              { value: "SOFT", label: sv.hardOrSoft.SOFT },
            ]}
            value={constraint.hardOrSoft}
            onChange={(value) =>
              applyOverride({ hardOrSoft: value, weight: value === "SOFT" ? (constraint.weight ?? 50) : undefined })
            }
          />
        )}
      </Table.Td>
      <Table.Td>
        {/* Weight stays editable for reserved MEDIUM rows too: the backend guardrail
            (ConstraintWeightService.validateReclassification) only blocks disabling and
            reclassification - weight >= 1 is allowed. */}
        {constraint.hardOrSoft === "SOFT" || reserved ? (
          <NumberInput
            size="xs"
            w={90}
            min={1}
            value={weightDraft}
            disabled={updateWeights.isPending}
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
        <Tooltip label={sv.fieldBuilder.mediumReservedTooltip} disabled={!reserved}>
          <Switch
            checked={constraint.enabled}
            disabled={reserved || updateWeights.isPending}
            onChange={(event) => applyOverride({ enabled: event.currentTarget.checked })}
          />
        </Tooltip>
      </Table.Td>
      <Table.Td>
        <Group gap="xs" wrap="nowrap">
          {isModifiedFromDefault && <Badge size="xs" variant="light">{sv.constraintWeights.overriddenBadge}</Badge>}
          {isModifiedFromDefault && (
            <Button size="xs" variant="subtle" onClick={handleReset} loading={updateWeights.isPending}>
              {sv.constraintWeights.resetButton}
            </Button>
          )}
        </Group>
      </Table.Td>
    </Table.Tr>
  );
}
