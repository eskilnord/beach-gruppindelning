import { Fragment, useEffect, useMemo, useState } from "react";
import {
  Alert,
  Anchor,
  Badge,
  Button,
  Group,
  Loader,
  NumberInput,
  Progress,
  SegmentedControl,
  Select,
  Stack,
  Switch,
  Table,
  Text,
  Tooltip,
} from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { useNavigate } from "react-router-dom";
import { useConstraintDefinitions } from "../../../api/constraintDefinitions";
import { useConstraintWeights, useUpdateConstraintWeights } from "../../../api/constraintWeights";
import { ApiError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { HelpTip } from "../../../components/HelpTip";
import type { ConstraintDefinition, ConstraintWeightOverrideRequest, ConstraintWeightView } from "../../../api/types";

interface ConstraintWeightsTableProps {
  planId: string;
}

/** The four "Betydelse" presets for a SOFT/MEDIUM row, computed from the constraint's DEFAULT
 *  weight (never the current weight - the presets are stable landmarks, not a moving target).
 *  Values are capped at WeightLimits.MAX_WEIGHT (10 000, mirrored client-side) and deduped
 *  (candidates are walked Normal-first so a tie always keeps "Normal" over the others), then
 *  sorted ascending by weight for DISPLAY - the dedup tie-break order and the display order are
 *  deliberately different concerns. */
const MAX_WEIGHT = 10_000;

function buildImportancePresets(defaultWeight: number): { value: string; label: string }[] {
  const candidates: { weight: number; label: (w: number) => string }[] = [
    { weight: defaultWeight, label: sv.constraintWeights.importance.normal },
    { weight: Math.max(1, Math.round(defaultWeight / 2)), label: sv.constraintWeights.importance.lessImportant },
    { weight: Math.min(MAX_WEIGHT, defaultWeight * 2), label: sv.constraintWeights.importance.important },
    { weight: Math.min(MAX_WEIGHT, defaultWeight * 4), label: sv.constraintWeights.importance.muchMoreImportant },
  ];
  const seenWeights = new Set<number>();
  const presets: { value: string; label: string }[] = [];
  for (const candidate of candidates) {
    if (seenWeights.has(candidate.weight)) {
      continue;
    }
    seenWeights.add(candidate.weight);
    presets.push({ value: String(candidate.weight), label: candidate.label(candidate.weight) });
  }
  return presets.sort((a, b) => Number(a.value) - Number(b.value));
}

/** Per-row plain-language "what does this weight mean" sentence. Priority: a per-key override
 *  (currently only lateTimeForLowerGroups, whose two real constraints pull in opposite directions)
 *  beats the MEDIUM-dedicated sentence, which beats the generic unit+direction template. */
function meaningSentenceFor(constraint: ConstraintWeightView): string | undefined {
  const byKey = sv.constraintWeights.meaningByKey[constraint.key];
  if (byKey) {
    return byKey(constraint.weight);
  }
  if (constraint.hardOrSoft === "MEDIUM") {
    return sv.constraintWeights.meaning.MEDIUM(constraint.weight);
  }
  const meaningKey = `${constraint.unit}_${constraint.direction}` as keyof typeof sv.constraintWeights.meaning;
  const template = sv.constraintWeights.meaning[meaningKey];
  return typeof template === "function" ? template(constraint.weight) : undefined;
}

/**
 * "Konfiguration" section of Fältbyggaren (spec §9.4/§7.16): the standard constraints, merged with
 * this plan's overrides. Reclassification is restricted to HARD/SOFT (no MEDIUM in the MVP UI,
 * ADR-006) - a MEDIUM-classified row (e.g. the `unassignedPlayer` waitlist constraint) is rendered
 * read-only, mirroring the guardrail already enforced by ConstraintWeightService.
 *
 * WP4: raw weight numbers are pedagogically opaque ("50", "100") - only their RELATIVE ratios
 * matter to the solver. This adds, on top of the unchanged underlying weight/enabled semantics:
 * an intro card explaining that framing, Swedish category grouping, a "Betydelse" preset picker
 * (Mindre viktig/Normal/Viktigare/Mycket viktigare/Egen…) computed from each constraint's default
 * weight, a per-row plain-language sentence of what the weight means (unit+direction from
 * ConstraintMetadata via the backend), and a relative-importance bar restricted to enabled SOFT
 * rows (MEDIUM and HARD aren't on the same scale, so they're excluded - see the intro card).
 */
export function ConstraintWeightsTable({ planId }: ConstraintWeightsTableProps) {
  const navigate = useNavigate();
  const weights = useConstraintWeights(planId);
  const definitions = useConstraintDefinitions();

  const defaultsByKey = new Map<string, ConstraintDefinition>((definitions.data ?? []).map((def) => [def.key, def]));

  // Relative-importance bar denominator: the largest weight among ENABLED SOFT rows only (MEDIUM and
  // HARD are excluded - see this component's own doc comment). Computed once here (not per-row) so
  // every bar in the tab shares the same scale. Guarded against 0/empty - callers hide the bar
  // entirely when this is 0.
  const maxEnabledSoftWeight = useMemo(() => {
    if (!weights.data) {
      return 0;
    }
    let max = 0;
    for (const constraint of weights.data) {
      if (constraint.hardOrSoft === "SOFT" && constraint.enabled && constraint.weight > max) {
        max = constraint.weight;
      }
    }
    return max;
  }, [weights.data]);

  // Stable group-by-category preserving the API's existing row order within each group.
  const groups = useMemo(() => {
    if (!weights.data) {
      return [];
    }
    const order: string[] = [];
    const byCategory = new Map<string, ConstraintWeightView[]>();
    for (const constraint of weights.data) {
      const category = constraint.constraintCategory;
      if (!byCategory.has(category)) {
        byCategory.set(category, []);
        order.push(category);
      }
      byCategory.get(category)!.push(constraint);
    }
    return order.map((category) => ({ category, rows: byCategory.get(category)! }));
  }, [weights.data]);

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

      {/* v0.6.0 F3 (M-S3): the six bucket constraints' weights are now normally OWNED by the
          Prioriteringar screen's ranking (PrioritiesPanel.tsx) - this points an admin editing weights
          here straight at it, rather than leaving the relationship between the two screens implicit. */}
      <Anchor
        component="button"
        type="button"
        size="sm"
        onClick={() => navigate(`/plans/${planId}/prioriteringar`)}
        data-testid="constraint-weights-priority-order-link"
        style={{ alignSelf: "flex-start" }}
      >
        {sv.constraintWeights.priorityOrderLink}
      </Anchor>

      <Alert variant="light" color="blue">
        {sv.constraintWeights.intro}
      </Alert>

      {(weights.isLoading || definitions.isLoading) && <Loader size="sm" />}
      {weights.isError && (
        <Alert color="red">
          {weights.error instanceof ApiError ? weights.error.message : sv.constraintWeights.loadFailed}
        </Alert>
      )}

      {weights.data && (
        <Table.ScrollContainer minWidth={820}>
          <Table verticalSpacing="xs" withTableBorder striped highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{sv.constraintWeights.table.label}</Table.Th>
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
              {groups.map(({ category, rows }) => (
                <Fragment key={`group-${category}`}>
                  <Table.Tr bg="var(--mantine-color-blue-light)">
                    <Table.Td colSpan={5}>
                      <Text size="xs" fw={700} tt="uppercase" c="dimmed">
                        {sv.constraintWeights.categories[category] ?? category}
                      </Text>
                    </Table.Td>
                  </Table.Tr>
                  {rows.map((constraint) => (
                    <ConstraintWeightRow
                      key={constraint.key}
                      constraint={constraint}
                      definition={defaultsByKey.get(constraint.key)}
                      planId={planId}
                      maxEnabledSoftWeight={maxEnabledSoftWeight}
                    />
                  ))}
                </Fragment>
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
  maxEnabledSoftWeight: number;
}

function ConstraintWeightRow({ constraint, definition, planId, maxEnabledSoftWeight }: ConstraintWeightRowProps) {
  const updateWeights = useUpdateConstraintWeights(planId);
  const [weightDraft, setWeightDraft] = useState<number | "">(constraint.weight);
  // Explicit "the user picked Egen…" flag (BLOCKER fix): without this, a row sitting exactly on a
  // preset (the common case - every row starts there) could never open the NumberInput, because the
  // Select's value was derived purely from whether the CURRENT weight snaps onto a preset. Reset to
  // false whenever the server-confirmed weight lands back on a preset (e.g. after "Återställ till
  // standard") so the Select doesn't stay stuck showing "Egen…" for a now-standard value.
  const [customMode, setCustomMode] = useState(false);

  useEffect(() => setWeightDraft(constraint.weight), [constraint.weight]);

  const presets = definition ? buildImportancePresets(definition.defaultWeight) : [];
  const matchingPreset = presets.find((preset) => Number(preset.value) === constraint.weight);

  useEffect(() => {
    if (matchingPreset) {
      setCustomMode(false);
    }
    // Only react to the server-confirmed weight changing, not to `presets`/`matchingPreset`
    // (recomputed every render) or local Select interaction - see the state's own comment above.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [constraint.weight]);

  // Reserved system constraint (ADR-006, e.g. the future unassignedPlayer waitlist penalty) - the
  // backend rejects disabling or reclassifying it away from MEDIUM (ConstraintWeightService
  // .validateReclassification), so those two controls are locked with a tooltip; its weight
  // (floor >= 1) stays editable.
  const reserved = constraint.hardOrSoft === "MEDIUM";
  const isSoftOrReserved = constraint.hardOrSoft === "SOFT" || reserved;

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
    if (weightDraft === "") {
      return;
    }
    // Clamp defensively to WeightLimits.MAX_WEIGHT (mirrored client-side as MAX_WEIGHT above): the
    // NumberInput's own `max` prop only clamps the DISPLAYED value on blur, but this handler also
    // runs on blur and would otherwise read the pre-clamp typed value (e.g. 99999) into the PUT.
    const clamped = Math.min(MAX_WEIGHT, Math.max(1, Number(weightDraft)));
    if (clamped !== Number(weightDraft)) {
      setWeightDraft(clamped);
    }
    if (clamped !== constraint.weight) {
      applyOverride({ weight: clamped });
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
    setCustomMode(false);
    applyOverride({
      hardOrSoft: definition.hardOrSoft,
      weight: definition.defaultWeight,
      enabled: definition.enabled,
    });
  };

  // "Egen…" is shown whenever the user explicitly picked it (customMode) OR the current weight
  // doesn't snap onto any preset (or there's no definition to compute presets from at all) - the
  // NumberInput then carries the actual/draft value, prefilled with the current weight.
  const selectValue = customMode || !matchingPreset ? "custom" : matchingPreset.value;
  const selectData = [...presets, { value: "custom", label: sv.constraintWeights.importance.custom }];

  const meaningSentence = meaningSentenceFor(constraint);

  const barValue =
    constraint.hardOrSoft === "SOFT" && constraint.enabled && maxEnabledSoftWeight > 0
      ? Math.min(100, (constraint.weight / maxEnabledSoftWeight) * 100)
      : 0;

  return (
    <Table.Tr>
      <Table.Td>
        <Text size="sm">{constraint.label}</Text>
        <Text size="xs" c="dimmed">
          {constraint.description}
        </Text>
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
        {isSoftOrReserved ? (
          <Stack gap={4}>
            <Group gap={4} wrap="nowrap">
              {definition && (
                <Select
                  aria-label={sv.constraintWeights.importance.ariaLabel(constraint.label)}
                  size="xs"
                  w={180}
                  allowDeselect={false}
                  data={selectData}
                  value={selectValue}
                  disabled={updateWeights.isPending}
                  onChange={(value) => {
                    if (value === "custom") {
                      setCustomMode(true);
                      return;
                    }
                    if (value) {
                      setCustomMode(false);
                      setWeightDraft(Number(value));
                      applyOverride({ weight: Number(value) });
                    }
                  }}
                />
              )}
              {(selectValue === "custom" || !definition) && (
                <NumberInput
                  size="xs"
                  w={90}
                  min={1}
                  max={10000}
                  value={weightDraft}
                  disabled={updateWeights.isPending}
                  onChange={(value) => setWeightDraft(value === "" ? "" : Number(value))}
                  onBlur={commitWeight}
                />
              )}
            </Group>
            {!constraint.enabled ? (
              <Text size="xs" c="dimmed">
                {sv.constraintWeights.disabledMeaning}
              </Text>
            ) : (
              <>
                {meaningSentence && (
                  <Text size="xs" c="dimmed">
                    {meaningSentence}
                  </Text>
                )}
                {constraint.hardOrSoft === "SOFT" && maxEnabledSoftWeight > 0 && (
                  <Progress size="xs" w={120} value={barValue} />
                )}
              </>
            )}
          </Stack>
        ) : !constraint.enabled ? (
          <Text size="xs" c="dimmed">
            {sv.constraintWeights.disabledMeaning}
          </Text>
        ) : (
          <Text size="xs" c="dimmed">
            {sv.constraintWeights.hardMeaning}
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
