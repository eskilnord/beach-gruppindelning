import { Button, Group, Modal, NumberInput, Stack, Text, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { notifications } from "@mantine/notifications";
import { useCreatePlan } from "../../api/plans";
import { userErrorText, technicalErrorDetail } from "../../lib/errorText";
import { HelpTip } from "../../components/HelpTip";
import { AdvancedOnly } from "../../components/uimode/AdvancedOnly";
import { useIsSimpleMode } from "../../lib/uiMode/useUiMode";
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
  const isSimple = useIsSimpleMode();

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
      const technical = technicalErrorDetail(error);
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: (
          <Stack gap={2}>
            <Text size="sm">{userErrorText(error, sv.createPlanModal.createFailed)}</Text>
            {technical && (
              <Text size="xs" c="dimmed">
                {sv.common.technicalInfo(technical)}
              </Text>
            )}
          </Stack>
        ),
      });
    }
  });

  // v0.6.0 audit-fix A10: SIMPLE calls this field "Grupptyp" with a narrower placeholder and a
  // persistent visible description; ADVANCED keeps "Kategori" unchanged.
  const categoryLabelText = isSimple ? sv.createPlanModal.categoryLabelSimple : sv.createPlanModal.categoryLabel;

  return (
    <Modal opened={opened} onClose={handleClose} title={sv.createPlanModal.title} centered>
      <form onSubmit={handleSubmit}>
        <Stack gap="sm">
          <TextInput
            label={sv.createPlanModal.nameLabel}
            placeholder={isSimple ? sv.createPlanModal.namePlaceholderSimple : sv.createPlanModal.namePlaceholder}
            withAsterisk
            data-autofocus
            {...form.getInputProps("name")}
          />
          {/* v0.6.0 audit-fix A9: the HelpTip sits as a plain-text sibling ABOVE the input (not
              inside Mantine's `label` prop, which would nest an interactive <button> inside a real
              <label> - invalid HTML, see HelpTip.tsx's own doc comment) so the icon reads inline
              with the label instead of as an orphan glyph on its own line below. `aria-label` on the
              TextInput keeps its accessible name exactly "Kategori"/"Grupptyp" (ARIA aria-label takes
              precedence over the visually-associated <label> text, and testing-library's
              getByLabelText matches it too), independent of the visible label's own markup. */}
          <Stack gap={4}>
            <Group gap={4} wrap="nowrap">
              <Text size="sm" fw={500}>
                {categoryLabelText}
              </Text>
              <HelpTip label={sv.help.ariaLabel(categoryLabelText)}>{sv.help.plan.category}</HelpTip>
            </Group>
            <TextInput
              aria-label={categoryLabelText}
              placeholder={isSimple ? sv.createPlanModal.categoryPlaceholderSimple : sv.createPlanModal.categoryPlaceholder}
              description={isSimple ? sv.createPlanModal.categoryDescriptionSimple : undefined}
              {...form.getInputProps("category")}
            />
          </Stack>

          {/* v0.6.0 F2 (M-S2): SIMPLE mode creates a plan with just name+kategori - the backend's
              own defaults cover group sizing, so these four optional overrides are ADVANCED-only.
              AdvancedOnly renders `children` unchanged in ADVANCED, so this stays pixel-identical
              there. */}
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
