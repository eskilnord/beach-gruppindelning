import { Button, Group, Modal, NumberInput, Stack, Text, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { notifications } from "@mantine/notifications";
import { useEffect } from "react";
import { useUpdatePlan } from "../../api/plans";
import { ApiError } from "../../api/client";
import { HelpTip } from "../../components/HelpTip";
import { AdvancedOnly, SimpleOnly } from "../../components/uimode/AdvancedOnly";
import { useIsSimpleMode } from "../../lib/uiMode/useUiMode";
import { sv } from "../../i18n/sv";
import type { ActivityPlan } from "../../api/types";
import {
  type PlanDefaultsFormValues,
  planDefaultsFromPlan,
  planDefaultsToPatchRequest,
  planDefaultsValidationFor,
} from "../../lib/planDefaults";

interface EditPlanModalProps {
  opened: boolean;
  plan: ActivityPlan;
  onClose: () => void;
}

interface FormValues extends PlanDefaultsFormValues {
  name: string;
  category: string;
  status: string;
}

function valuesFromPlan(plan: ActivityPlan): FormValues {
  return { name: plan.name, category: plan.category ?? "", status: plan.status, ...planDefaultsFromPlan(plan) };
}

export function EditPlanModal({ opened, plan, onClose }: EditPlanModalProps) {
  const updatePlan = useUpdatePlan(plan.id, plan.seasonPlanId);
  const isSimple = useIsSimpleMode();

  const form = useForm<FormValues>({
    initialValues: valuesFromPlan(plan),
    // v0.6.0 F2 review fix (FIX 2): SIMPLE mode only validates the one field it actually renders -
    // see planDefaultsValidationFor's doc comment for why the full ADVANCED validator set is unsafe
    // to use while min/max/level-min are unmounted.
    validate: {
      name: (value) => (value.trim().length === 0 ? sv.common.nameRequired : null),
      ...planDefaultsValidationFor(isSimple),
    },
  });

  useEffect(() => {
    if (opened) {
      form.setValues(valuesFromPlan(plan));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened, plan.id]);

  const handleSubmit = form.onSubmit(async (values) => {
    try {
      await updatePlan.mutateAsync({
        name: values.name.trim(),
        category: values.category.trim().length > 0 ? values.category.trim() : undefined,
        status: values.status,
        // Three-state PATCH: empty inputs become explicit nulls so a saved default can be CLEARED
        // (absent means "keep" server-side) - see planDefaultsToPatchRequest.
        ...planDefaultsToPatchRequest(values),
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
          <TextInput
            label={sv.common.category}
            description={<HelpTip label={sv.help.ariaLabel(sv.common.category)}>{sv.help.plan.category}</HelpTip>}
            {...form.getInputProps("category")}
          />
          {/* v0.6.0 F2 (M-S2): status free-text is ADVANCED-only. AdvancedOnly renders `children`
              unchanged in ADVANCED, so this stays pixel-identical there. */}
          <AdvancedOnly>
            <TextInput
              label={sv.editPlanModal.statusLabel}
              description={<HelpTip label={sv.help.ariaLabel(sv.editPlanModal.statusLabel)}>{sv.help.plan.status}</HelpTip>}
              {...form.getInputProps("status")}
            />
          </AdvancedOnly>

          {/* SIMPLE shows just the target size, under plainer wording, standing in for the
              "Standardvärden för grupper" section below (min/max/level-min stay ADVANCED-only). */}
          <SimpleOnly>
            <NumberInput
              label={sv.editPlanModal.targetLabelSimple}
              description={sv.planDefaults.targetDescription}
              placeholder="10"
              min={1}
              {...form.getInputProps("defaultGroupTargetSize")}
            />
          </SimpleOnly>

          <AdvancedOnly>
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
          </AdvancedOnly>

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
