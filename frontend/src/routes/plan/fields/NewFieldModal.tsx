import {
  Button,
  Group,
  Modal,
  NumberInput,
  SegmentedControl,
  Select,
  Stack,
  Switch,
  Text,
  Textarea,
  TextInput,
} from "@mantine/core";
import { useForm } from "@mantine/form";
import { notifications } from "@mantine/notifications";
import { useCreateFieldDefinition } from "../../../api/fieldDefinitions";
import { ApiError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { HelpTip } from "../../../components/HelpTip";
import { generateFieldKey } from "./fieldKey";
import {
  FIELD_TYPES,
  canAffectOptimization,
  compatibleConstraintFamilies,
  constraintFamilyLabel,
  fieldTypeLabel,
} from "./constraintCompatibility";

interface NewFieldModalProps {
  planId: string;
  opened: boolean;
  onClose: () => void;
}

interface FormValues {
  label: string;
  fieldType: string;
  optionsText: string;
  affectsOptimization: boolean;
  constraintType: string;
  hardOrSoft: "HARD" | "SOFT";
  weight: number | "";
  explanationText: string;
}

const SELECT_FIELD_TYPES = new Set(["singleSelect", "multiSelect"]);

const initialValues: FormValues = {
  label: "",
  fieldType: "text",
  optionsText: "",
  affectsOptimization: false,
  constraintType: "NONE",
  hardOrSoft: "SOFT",
  weight: "",
  explanationText: "",
};

/**
 * Fältbyggaren's "Nytt fält" modal (spec §9.1/§19.5): creates a plan-scoped custom field. The label
 * drives an auto-generated camelCase key (fieldKey.ts, shown as a live preview - the backend never
 * allows changing it after creation); the constraint select is filtered live by the chosen field
 * type (constraintCompatibility.ts mirrors the backend's FieldDefinitionValidator/ConstraintTypes
 * exactly, so nothing offered here can be rejected as incompatible); hard/soft has no MEDIUM option
 * in the MVP UI (spec §9.5/ADR-006 - MEDIUM is reserved for the solver's own waitlist constraint);
 * the weight input only appears for SOFT.
 */
export function NewFieldModal({ planId, opened, onClose }: NewFieldModalProps) {
  const createField = useCreateFieldDefinition(planId);

  const form = useForm<FormValues>({
    initialValues,
    validate: {
      label: (value) => (value.trim().length === 0 ? sv.common.nameRequired : null),
      optionsText: (value, values) =>
        SELECT_FIELD_TYPES.has(values.fieldType) &&
        value
          .split(",")
          .map((option) => option.trim())
          .filter(Boolean).length === 0
          ? sv.fieldBuilder.newFieldModal.optionsRequired
          : null,
      weight: (value, values) =>
        values.affectsOptimization && values.hardOrSoft === "SOFT" && (value === "" || Number(value) < 1)
          ? sv.fieldBuilder.newFieldModal.weightRequired
          : null,
    },
  });

  const key = generateFieldKey(form.values.label);
  const canOptimize = canAffectOptimization(form.values.fieldType);
  const compatibleFamilies = compatibleConstraintFamilies(form.values.fieldType).filter(
    (family) => family !== "NONE",
  );
  const showOptions = SELECT_FIELD_TYPES.has(form.values.fieldType);
  const showConstraintFields = form.values.affectsOptimization && canOptimize;
  const showWeight = showConstraintFields && form.values.hardOrSoft === "SOFT";

  const handleTypeChange = (value: string | null) => {
    if (!value) {
      return;
    }
    form.setFieldValue("fieldType", value);
    if (!canAffectOptimization(value)) {
      form.setFieldValue("affectsOptimization", false);
      form.setFieldValue("constraintType", "NONE");
      return;
    }
    const families: string[] = compatibleConstraintFamilies(value).filter((family) => family !== "NONE");
    if (!families.includes(form.values.constraintType)) {
      form.setFieldValue("constraintType", families[0] ?? "NONE");
    }
  };

  const handleAffectsOptimizationChange = (checked: boolean) => {
    form.setFieldValue("affectsOptimization", checked);
    if (checked) {
      const families = compatibleConstraintFamilies(form.values.fieldType).filter((family) => family !== "NONE");
      form.setFieldValue("constraintType", families[0] ?? "NONE");
    } else {
      form.setFieldValue("constraintType", "NONE");
    }
  };

  const handleClose = () => {
    form.reset();
    onClose();
  };

  const handleSubmit = form.onSubmit(async (values) => {
    const optionsJson = showOptions
      ? JSON.stringify(
          values.optionsText
            .split(",")
            .map((option) => option.trim())
            .filter(Boolean),
        )
      : undefined;

    try {
      await createField.mutateAsync({
        key: generateFieldKey(values.label),
        label: values.label.trim(),
        fieldType: values.fieldType,
        affectsOptimization: values.affectsOptimization,
        constraintType: values.affectsOptimization ? values.constraintType : "NONE",
        hardOrSoft: values.affectsOptimization ? values.hardOrSoft : undefined,
        weight: values.affectsOptimization && values.hardOrSoft === "SOFT" ? Number(values.weight) : undefined,
        explanationText: values.explanationText.trim() || undefined,
        optionsJson,
      });
      handleClose();
    } catch (error) {
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: error instanceof ApiError ? error.message : sv.fieldBuilder.newFieldModal.createFailed,
      });
    }
  });

  return (
    <Modal opened={opened} onClose={handleClose} title={sv.fieldBuilder.newFieldModal.title} centered size="md">
      <form onSubmit={handleSubmit}>
        <Stack gap="sm">
          <TextInput
            label={sv.fieldBuilder.newFieldModal.labelLabel}
            placeholder={sv.fieldBuilder.newFieldModal.labelPlaceholder}
            withAsterisk
            data-autofocus
            {...form.getInputProps("label")}
          />
          {form.values.label.trim().length > 0 && (
            <Text size="xs" c="dimmed">
              {sv.fieldBuilder.newFieldModal.keyPreview(key)}
            </Text>
          )}

          <Select
            label={sv.fieldBuilder.newFieldModal.typeLabel}
            data={FIELD_TYPES.map((type) => ({ value: type, label: fieldTypeLabel(type) }))}
            value={form.values.fieldType}
            onChange={handleTypeChange}
            allowDeselect={false}
            comboboxProps={{ withinPortal: false }}
          />

          {showOptions && (
            <TextInput
              label={sv.fieldBuilder.newFieldModal.optionsLabel}
              placeholder={sv.fieldBuilder.newFieldModal.optionsPlaceholder}
              withAsterisk
              {...form.getInputProps("optionsText")}
            />
          )}

          <Switch
            label={sv.fieldBuilder.newFieldModal.affectsOptimizationLabel}
            description={
              <HelpTip label={sv.help.ariaLabel(sv.fieldBuilder.newFieldModal.affectsOptimizationLabel)}>
                {sv.help.fields.affectsOptimization}
              </HelpTip>
            }
            checked={form.values.affectsOptimization}
            onChange={(event) => handleAffectsOptimizationChange(event.currentTarget.checked)}
            disabled={!canOptimize}
          />
          {!canOptimize && (
            <Text size="xs" c="dimmed">
              {sv.fieldBuilder.newFieldModal.noCompatibleConstraint}
            </Text>
          )}

          {showConstraintFields && (
            <>
              <Select
                label={sv.fieldBuilder.newFieldModal.constraintLabel}
                description={
                  <HelpTip label={sv.help.ariaLabel(sv.fieldBuilder.newFieldModal.constraintLabel)}>
                    {sv.help.fields.constraintType}
                  </HelpTip>
                }
                data={compatibleFamilies.map((family) => ({ value: family, label: constraintFamilyLabel(family) }))}
                value={form.values.constraintType}
                onChange={(value) => value && form.setFieldValue("constraintType", value)}
                allowDeselect={false}
                comboboxProps={{ withinPortal: false }}
              />
              <div>
                <Group gap={4} mb={4}>
                  <Text size="sm" fw={500}>
                    {sv.fieldBuilder.newFieldModal.hardOrSoftLabel}
                  </Text>
                  <HelpTip label={sv.help.ariaLabel(sv.fieldBuilder.newFieldModal.hardOrSoftLabel)}>
                    {sv.help.fields.hardOrSoft}
                  </HelpTip>
                </Group>
                <SegmentedControl
                  fullWidth
                  data={[
                    { value: "HARD", label: sv.hardOrSoft.HARD },
                    { value: "SOFT", label: sv.hardOrSoft.SOFT },
                  ]}
                  value={form.values.hardOrSoft}
                  onChange={(value) => form.setFieldValue("hardOrSoft", value as "HARD" | "SOFT")}
                />
              </div>
              {showWeight && (
                <NumberInput
                  label={sv.fieldBuilder.newFieldModal.weightLabel}
                  description={<HelpTip label={sv.help.fields.weightAriaLabelInModal}>{sv.help.fields.weight}</HelpTip>}
                  min={1}
                  withAsterisk
                  {...form.getInputProps("weight")}
                />
              )}
            </>
          )}

          <Textarea
            label={sv.fieldBuilder.newFieldModal.explanationLabel}
            description={
              <HelpTip label={sv.help.ariaLabel(sv.fieldBuilder.newFieldModal.explanationLabel)}>
                {sv.help.fields.explanation}
              </HelpTip>
            }
            autosize
            minRows={2}
            {...form.getInputProps("explanationText")}
          />

          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={handleClose}>
              {sv.common.cancel}
            </Button>
            <Button type="submit" loading={createField.isPending}>
              {sv.fieldBuilder.newFieldModal.submit}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
