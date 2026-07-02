import { Button, Group, Modal, NumberInput, Stack, Text, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { notifications } from "@mantine/notifications";
import { useCreatePlan } from "../../api/plans";
import { ApiError } from "../../api/client";
import { sv } from "../../i18n/sv";
import {
  PLAN_DEFAULTS_EMPTY_VALUES,
  type PlanDefaultsFormValues,
  planDefaultsToCreateRequest,
  planDefaultsValidation,
} from "../../lib/planDefaults";

interface CreatePlanModalProps {
  opened: boolean;
  seasonId: string;
  onClose: () => void;
  onCreated: (planId: string) => void;
}

interface FormValues extends PlanDefaultsFormValues {
  name: string;
  category: string;
}

export function CreatePlanModal({ opened, seasonId, onClose, onCreated }: CreatePlanModalProps) {
  const createPlan = useCreatePlan(seasonId);

  const form = useForm<FormValues>({
    initialValues: { name: "", category: "", ...PLAN_DEFAULTS_EMPTY_VALUES },
    validate: {
      name: (value) => (value.trim().length === 0 ? sv.common.nameRequired : null),
      ...planDefaultsValidation,
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
        ...planDefaultsToCreateRequest(values),
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

          <Text fw={500} size="sm" mt="xs">
            {sv.planDefaults.heading}
          </Text>
          <Text size="xs" c="dimmed" mt={-8}>
            {sv.planDefaults.subheading}
          </Text>
          <Group grow>
            <NumberInput
              label={sv.planDefaults.targetLabel}
              description={sv.planDefaults.targetDescription}
              placeholder="10"
              min={1}
              {...form.getInputProps("defaultGroupTargetSize")}
            />
            <NumberInput
              label={sv.planDefaults.minLabel}
              description={sv.planDefaults.minDescription}
              placeholder="8"
              min={1}
              {...form.getInputProps("defaultGroupMinSize")}
            />
          </Group>
          <Group grow>
            <NumberInput
              label={sv.planDefaults.maxLabel}
              description={sv.planDefaults.maxDescription}
              placeholder="12"
              min={1}
              {...form.getInputProps("defaultGroupMaxSize")}
            />
            <NumberInput
              label={sv.planDefaults.levelMinLabel}
              description={sv.planDefaults.levelMinDescription}
              placeholder={sv.planDefaults.levelMinPlaceholder}
              min={0}
              max={1000}
              clampBehavior="none"
              {...form.getInputProps("defaultLevelMin")}
            />
          </Group>

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
