import { Button, Group, Modal, Stack, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { notifications } from "@mantine/notifications";
import { useEffect } from "react";
import { useUpdatePlan } from "../../api/plans";
import { ApiError } from "../../api/client";
import { sv } from "../../i18n/sv";
import type { ActivityPlan } from "../../api/types";

interface EditPlanModalProps {
  opened: boolean;
  plan: ActivityPlan;
  onClose: () => void;
}

interface FormValues {
  name: string;
  category: string;
  status: string;
}

export function EditPlanModal({ opened, plan, onClose }: EditPlanModalProps) {
  const updatePlan = useUpdatePlan(plan.id, plan.seasonPlanId);

  const form = useForm<FormValues>({
    initialValues: { name: plan.name, category: plan.category ?? "", status: plan.status },
    validate: {
      name: (value) => (value.trim().length === 0 ? sv.common.nameRequired : null),
    },
  });

  useEffect(() => {
    if (opened) {
      form.setValues({ name: plan.name, category: plan.category ?? "", status: plan.status });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened, plan.id]);

  const handleSubmit = form.onSubmit(async (values) => {
    try {
      await updatePlan.mutateAsync({
        name: values.name.trim(),
        category: values.category.trim().length > 0 ? values.category.trim() : undefined,
        status: values.status,
        defaultGroupTargetSize: undefined,
        defaultGroupMinSize: undefined,
        defaultGroupMaxSize: undefined,
      });
      onClose();
    } catch (error) {
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: error instanceof ApiError ? error.message : sv.editPlanModal.updateFailed,
      });
    }
  });

  return (
    <Modal opened={opened} onClose={onClose} title={sv.editPlanModal.title} centered>
      <form onSubmit={handleSubmit}>
        <Stack gap="sm">
          <TextInput
            label={sv.common.name}
            withAsterisk
            data-autofocus
            {...form.getInputProps("name")}
          />
          <TextInput label={sv.common.category} {...form.getInputProps("category")} />
          <TextInput label={sv.editPlanModal.statusLabel} {...form.getInputProps("status")} />
          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={onClose}>
              {sv.common.cancel}
            </Button>
            <Button type="submit" loading={updatePlan.isPending}>
              {sv.editPlanModal.submit}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
