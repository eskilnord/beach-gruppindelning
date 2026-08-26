import { useEffect } from "react";
import { Button, Group, Modal, NumberInput, Select, Stack, TextInput } from "@mantine/core";
import { TimeInput } from "@mantine/dates";
import { useForm } from "@mantine/form";
import { notifications } from "@mantine/notifications";
import { useCreateTimeSlot, useUpdateTimeSlot } from "../../../api/timeSlots";
import { useSetCourts } from "../../../api/trainingBlocks";
import { sv } from "../../../i18n/sv";
import { HelpTip } from "../../../components/HelpTip";
import { userErrorText } from "./errorText";
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
  /** v0.6.0 audit-fix B12: CREATE-only ("" everywhere else, and never rendered/read outside the
   *  create path - see the NumberInput below and handleSubmit). */
  courts: number | "";
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
    courts: "",
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
  const setCourts = useSetCourts(planId);
  const isEdit = slot !== null;
  const pending = createSlot.isPending || updateSlot.isPending;

  const form = useForm<FormValues>({
    initialValues: valuesFor(slot),
    validate: {
      dayOfWeek: (value) => (value.trim().length === 0 ? sv.resources.slotModal.dayRequired : null),
      // v0.6.0 audit-fix B12: required fields + "starttid måste vara före sluttid", inline Swedish
      // field-level errors (Mantine's own per-field error rendering) instead of only discovering
      // either problem via a 400 toast after submit.
      startTime: (value) => (value.trim().length === 0 ? sv.resources.slotModal.startTimeRequired : null),
      endTime: (value, values) => {
        if (value.trim().length === 0) {
          return sv.resources.slotModal.endTimeRequired;
        }
        // "HH:MM" 24h strings compare correctly lexicographically - same assumption TimeInput's
        // own value format already relies on elsewhere in this file.
        if (values.startTime.trim().length > 0 && value <= values.startTime) {
          return sv.resources.slotModal.startBeforeEndError;
        }
        return null;
      },
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
        const created = await createSlot.mutateAsync({
          dayOfWeek: values.dayOfWeek,
          startTime: values.startTime,
          endTime: values.endTime,
          label,
        });
        // v0.6.0 audit-fix B12: CREATE-only "Antal banor" - a second, sequential useSetCourts call
        // (the SAME mutation SlotRow's own NumberInput uses) once the slot itself exists; no
        // combined transaction needed. Left blank ("") -> no call at all, same as today (a brand
        // new slot starts with 0 courts either way). Its failure is reported separately below and
        // must NOT undo/retry the already-succeeded slot creation.
        if (values.courts !== "") {
          try {
            await setCourts.mutateAsync({ slotId: created.id, count: Number(values.courts) });
          } catch (courtsError) {
            notifications.show({
              color: "red",
              title: sv.common.error,
              message: userErrorText(courtsError, sv.resources.slotModal.courtsAfterCreateFailed),
            });
          }
        }
      }
      onClose();
    } catch (error) {
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: userErrorText(error, isEdit ? sv.resources.slotModal.updateFailed : sv.resources.slotModal.createFailed),
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
          {/* v0.6.0 audit-fix B12: CREATE path only - editing an existing slot's court count already
              has its own dedicated control (ResourcesPanel.tsx's SlotRow NumberInput), so this stays
              unmounted (not merely disabled) once a slot has been created. */}
          {!isEdit && (
            <NumberInput
              label={sv.resources.slotModal.courtsLabel}
              description={sv.help.resources.courts}
              placeholder={sv.resources.slotModal.courtsPlaceholder}
              min={0}
              max={60}
              {...form.getInputProps("courts")}
            />
          )}
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
