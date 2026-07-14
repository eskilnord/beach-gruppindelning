import { Button, Group, Modal, Select, Stack, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { notifications } from "@mantine/notifications";
import { useCreateFieldDefinition } from "../../api/fieldDefinitions";
import { ApiError } from "../../api/client";
import type { FieldDefinition } from "../../api/types";
import { sv } from "../../i18n/sv";
import { generateFieldKey } from "../plan/fields/fieldKey";

interface NewCustomFieldModalProps {
  planId: string;
  opened: boolean;
  onClose: () => void;
  /** Called with the newly created field on success, before the modal closes — lets the mapping
   *  step select it for the column that triggered "Skapa nytt fält…". */
  onCreated?: (field: FieldDefinition) => void;
}

interface FormValues {
  name: string;
  type: string;
  optionsText: string;
}

const SELECT_TYPE = "singleSelect";

/**
 * "Skapa nytt fält…" from the mapping step's target dropdown (spec §8.4/§9.3): a quick-create
 * subset of Fältbyggaren's full "Nytt fält" modal (routes/plan/fields/NewFieldModal.tsx) — just
 * name/type (+ options for `singleSelect`), no optimization/constraint wiring, since the import
 * wizard only needs a place to land raw cell values. Posts to the same
 * `POST /api/plans/{planId}/field-definitions` endpoint via {@link useCreateFieldDefinition}, whose
 * `onSuccess` already invalidates the field-definitions query the mapping step's dropdown reads
 * from — the field is immediately mappable without an extra refetch here.
 */
export function NewCustomFieldModal({ planId, opened, onClose, onCreated }: NewCustomFieldModalProps) {
  const createField = useCreateFieldDefinition(planId);

  const form = useForm<FormValues>({
    initialValues: { name: "", type: "text", optionsText: "" },
    validate: {
      name: (value) => (value.trim().length === 0 ? sv.common.nameRequired : null),
      optionsText: (value, values) =>
        values.type === SELECT_TYPE &&
        value
          .split(",")
          .map((option) => option.trim())
          .filter(Boolean).length === 0
          ? sv.fieldBuilder.newFieldModal.optionsRequired
          : null,
    },
  });

  const handleClose = () => {
    form.reset();
    onClose();
  };

  const handleSubmit = form.onSubmit(async (values) => {
    const optionsJson =
      values.type === SELECT_TYPE
        ? JSON.stringify(
            values.optionsText
              .split(",")
              .map((option) => option.trim())
              .filter(Boolean),
          )
        : undefined;

    try {
      const field = await createField.mutateAsync({
        key: generateFieldKey(values.name),
        label: values.name.trim(),
        fieldType: values.type,
        optionsJson,
      });
      notifications.show({
        color: "green",
        title: sv.importWizard.mapping.createFieldOption,
        message: sv.importWizard.newFieldModal.createSuccess(field.label),
      });
      onCreated?.(field);
      handleClose();
    } catch (error) {
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: error instanceof ApiError ? error.message : sv.importWizard.newFieldModal.createFailed,
      });
    }
  });

  return (
    <Modal opened={opened} onClose={handleClose} title={sv.importWizard.newFieldModal.title} centered>
      <form onSubmit={handleSubmit}>
        <Stack gap="sm">
          <TextInput
            label={sv.importWizard.newFieldModal.nameLabel}
            withAsterisk
            data-autofocus
            {...form.getInputProps("name")}
          />
          <Select
            label={sv.importWizard.newFieldModal.typeLabel}
            data={[
              { value: "text", label: sv.importWizard.newFieldModal.types.text },
              { value: "number", label: sv.importWizard.newFieldModal.types.number },
              { value: "boolean", label: sv.importWizard.newFieldModal.types.boolean },
              { value: "singleSelect", label: sv.importWizard.newFieldModal.types.singleSelect },
            ]}
            allowDeselect={false}
            comboboxProps={{ withinPortal: false }}
            {...form.getInputProps("type")}
          />
          {form.values.type === SELECT_TYPE && (
            <TextInput
              label={sv.fieldBuilder.newFieldModal.optionsLabel}
              placeholder={sv.fieldBuilder.newFieldModal.optionsPlaceholder}
              withAsterisk
              {...form.getInputProps("optionsText")}
            />
          )}
          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={handleClose}>
              {sv.common.cancel}
            </Button>
            <Button type="submit" loading={createField.isPending}>
              {sv.importWizard.newFieldModal.submit}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
