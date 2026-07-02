import { Button, Group, Modal, Select, Stack, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { notifications } from "@mantine/notifications";
import { sv } from "../../i18n/sv";

interface NewCustomFieldModalProps {
  opened: boolean;
  onClose: () => void;
}

interface FormValues {
  name: string;
  type: string;
}

/**
 * "Skapa nytt fält…" from the mapping step's target dropdown (spec §8.4/§9.3). The backend's
 * per-plan custom-field CRUD (Fältbyggaren) is M4 scope — as of M3 there is no
 * `POST /api/plans/{planId}/field-definitions` to actually persist a new field, and
 * `PUT .../mapping` 400s on any `customField:<key>` that doesn't already exist
 * (`ImportController.setMapping`). Rather than silently drop the spec requirement, this captures
 * the name/type per §9.3 and explains the limitation, leaving the column mapped to "Ignorera" —
 * documented as a deviation in the M3 frontend completion report.
 */
export function NewCustomFieldModal({ opened, onClose }: NewCustomFieldModalProps) {
  const form = useForm<FormValues>({
    initialValues: { name: "", type: "text" },
    validate: {
      name: (value) => (value.trim().length === 0 ? sv.common.nameRequired : null),
    },
  });

  const handleClose = () => {
    form.reset();
    onClose();
  };

  const handleSubmit = form.onSubmit(() => {
    notifications.show({
      color: "yellow",
      title: sv.importWizard.mapping.createFieldOption,
      message: sv.importWizard.mapping.customFieldUnavailable,
    });
    handleClose();
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
            {...form.getInputProps("type")}
          />
          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={handleClose}>
              {sv.common.cancel}
            </Button>
            <Button type="submit">{sv.importWizard.newFieldModal.submit}</Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
