import { useEffect } from "react";
import { Button, Group, Modal, Select, Stack, TextInput } from "@mantine/core";
import { TimeInput } from "@mantine/dates";
import { useForm } from "@mantine/form";
import { notifications } from "@mantine/notifications";
import { useCreateTimeSlot, useUpdateTimeSlot } from "../../../api/timeSlots";
import { ApiError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { HelpTip } from "../../../components/HelpTip";
import type { TimeSlot } from "../../../api/types";

interface TimeSlotModalProps {
  planId: string;
  opened: boolean;
  /** null -> create mode; a TimeSlot -> edit mode, prefilled. */
  slot: TimeSlot | null;
  onClose: () => void;
}

interface FormValues {
  dayOfWeek: string;
  startTime: string;
  endTime: string;
  label: string;
}

const DAY_OPTIONS = [
  { value: "MONDAY", label: sv.days.MONDAY },
  { value: "TUESDAY", label: sv.days.TUESDAY },
  { value: "WEDNESDAY", label: sv.days.WEDNESDAY },
  { value: "THURSDAY", label: sv.days.THURSDAY },
  { value: "FRIDAY", label: sv.days.FRIDAY },
  { value: "SATURDAY", label: sv.days.SATURDAY },
  { value: "SUNDAY", label: sv.days.SUNDAY },
];

function valuesFor(slot: TimeSlot | null): FormValues {
  return {
    dayOfWeek: slot?.dayOfWeek ?? "",
    startTime: slot?.startTime ?? "",
    endTime: slot?.endTime ?? "",
    label: slot?.label ?? "",
  };
}

/**
 * "Ny tid"/edit modal for the Resursvy (spec §12.1/§19.6): day-of-week select (Swedish labels,
 * recurring weekly slots only - the spec's own examples are all "Torsdag 18.00–19.30" style, and
 * the M5 brief scopes the UI to dayOfWeek, not the dated-one-off variant) + start/end time inputs +
 * an optional label (auto-generated server-side when left blank, TimeSlotLabelFormatter).
 */
export function TimeSlotModal({ planId, opened, slot, onClose }: TimeSlotModalProps) {
  const createSlot = useCreateTimeSlot(planId);
  const updateSlot = useUpdateTimeSlot(planId);
  const isEdit = slot !== null;
  const pending = createSlot.isPending || updateSlot.isPending;

  const form = useForm<FormValues>({
    initialValues: valuesFor(slot),
    validate: {
      dayOfWeek: (value) => (value.trim().length === 0 ? sv.resources.slotModal.dayRequired : null),
    },
  });

  useEffect(() => {
    if (opened) {
      form.setValues(valuesFor(slot));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened, slot?.id]);

  const handleSubmit = form.onSubmit(async (values) => {
    const label = values.label.trim().length > 0 ? values.label.trim() : undefined;
    try {
      if (isEdit && slot) {
        await updateSlot.mutateAsync({
          id: slot.id,
          body: { dayOfWeek: values.dayOfWeek, startTime: values.startTime, endTime: values.endTime, label },
        });
      } else {
        await createSlot.mutateAsync({
          dayOfWeek: values.dayOfWeek,
          startTime: values.startTime,
          endTime: values.endTime,
          label,
        });
      }
      onClose();
    } catch (error) {
      notifications.show({
        color: "red",
        title: sv.common.error,
        message:
          error instanceof ApiError
            ? error.message
            : isEdit
              ? sv.resources.slotModal.updateFailed
              : sv.resources.slotModal.createFailed,
      });
    }
  });

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={isEdit ? sv.resources.slotModal.editTitle : sv.resources.slotModal.createTitle}
      centered
    >
      <form onSubmit={handleSubmit}>
        <Stack gap="sm">
          <Select
            label={sv.resources.slotModal.dayLabel}
            description={
              <HelpTip label={sv.help.ariaLabel(sv.resources.slotModal.dayLabel)}>{sv.help.resources.slotRecurrence}</HelpTip>
            }
            placeholder={sv.resources.slotModal.dayPlaceholder}
            data={DAY_OPTIONS}
            withAsterisk
            data-autofocus
            comboboxProps={{ withinPortal: false }}
            {...form.getInputProps("dayOfWeek")}
          />
          <Group grow>
            <TimeInput label={sv.resources.slotModal.startTimeLabel} withAsterisk {...form.getInputProps("startTime")} />
            <TimeInput label={sv.resources.slotModal.endTimeLabel} withAsterisk {...form.getInputProps("endTime")} />
          </Group>
          <TextInput
            label={sv.resources.slotModal.labelLabel}
            description={<HelpTip label={sv.help.ariaLabel(sv.resources.slotModal.labelLabel)}>{sv.help.resources.slotLabel}</HelpTip>}
            placeholder={sv.resources.slotModal.labelPlaceholder}
            {...form.getInputProps("label")}
          />
          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={onClose}>
              {sv.common.cancel}
            </Button>
            <Button type="submit" loading={pending}>
              {sv.resources.slotModal.submit}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
