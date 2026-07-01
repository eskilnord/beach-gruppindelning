import { Button, Group, Modal, Stack, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { notifications } from "@mantine/notifications";
import { useCreatePlan } from "../../api/plans";
import { ApiError } from "../../api/client";
import { sv } from "../../i18n/sv";

interface CreatePlanModalProps {
  opened: boolean;
  seasonId: string;
  onClose: () => void;
  onCreated: (planId: string) => void;
}

interface FormValues {
  name: string;
  category: string;
}

export function CreatePlanModal({ opened, seasonId, onClose, onCreated }: CreatePlanModalProps) {
  const createPlan = useCreatePlan(seasonId);

  const form = useForm<FormValues>({
    initialValues: { name: "", category: "" },
    validate: {
      name: (value) => (value.trim().length === 0 ? sv.common.nameRequired : null),
    },
  });

  const handleClose = () => {
    form.reset();
    onClose();
  };

  const handleSubmit = form.onSubmit(async (values) => {
    try {
      const created = await createPlan.mutateAsync({
        name: values.name.trim(),
        category: values.category.trim().length > 0 ? values.category.trim() : undefined,
        status: undefined,
        defaultGroupTargetSize: undefined,
        defaultGroupMinSize: undefined,
        defaultGroupMaxSize: undefined,
      });
      form.reset();
      onCreated(created.id);
    } catch (error) {
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: error instanceof ApiError ? error.message : sv.createPlanModal.createFailed,
      });
    }
  });

  return (
    <Modal opened={opened} onClose={handleClose} title={sv.createPlanModal.title} centered>
      <form onSubmit={handleSubmit}>
        <Stack gap="sm">
          <TextInput
            label={sv.createPlanModal.nameLabel}
            placeholder={sv.createPlanModal.namePlaceholder}
            withAsterisk
            data-autofocus
            {...form.getInputProps("name")}
          />
          <TextInput
            label={sv.createPlanModal.categoryLabel}
            placeholder={sv.createPlanModal.categoryPlaceholder}
            {...form.getInputProps("category")}
          />
          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={handleClose}>
              {sv.common.cancel}
            </Button>
            <Button type="submit" loading={createPlan.isPending}>
              {sv.createPlanModal.submit}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
