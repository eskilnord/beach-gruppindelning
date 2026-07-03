import { useEffect, useState } from "react";
import {
  Alert,
  Button,
  Divider,
  Drawer,
  Group,
  Loader,
  NumberInput,
  ScrollArea,
  SimpleGrid,
  Stack,
  Switch,
  Text,
  Textarea,
  Title,
} from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { useFieldDefinitions } from "../../../api/fieldDefinitions";
import { useCoachFieldValues, useUpdateCoachFieldValues } from "../../../api/coachFieldValues";
import { useCoachAvailability, useDeleteCoach, useSetCoachAvailability, useUpdateCoach } from "../../../api/coaches";
import { useTimeSlots } from "../../../api/timeSlots";
import { ApiError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { DeleteConfirmModal } from "../../../components/DeleteConfirmModal";
import { HelpTip } from "../../../components/HelpTip";
import { CustomFieldEditor, type CoachOption } from "../participants/CustomFieldEditor";
import type { ParticipantRow } from "../participants/participantRow";
import { AvailabilityMatrix } from "./AvailabilityMatrix";
import { applyAvailabilityEntries, availabilityDraftsEqual, initialAvailabilityDraft, toAvailabilityEntries } from "./availability";
import type { AvailabilityDraft } from "./availability";
import type { CoachRow } from "./coachRow";

interface CoachDrawerProps {
  planId: string;
  coach: CoachRow | null;
  allParticipants: ParticipantRow[];
  allCoaches: CoachOption[];
  onClose: () => void;
}

/**
 * Tränarvy detail drawer (spec §13.1/§19.7): profile fields (level/max groups/also-plays/notes),
 * the tri-state availability matrix (one row per plan time slot, full-replace PUT), and this coach's
 * custom-field values - reusing the same CustomFieldEditor the Deltagarvy drawer uses.
 */
export function CoachDrawer({ planId, coach, allParticipants, allCoaches, onClose }: CoachDrawerProps) {
  return (
    <Drawer opened={coach !== null} onClose={onClose} position="right" size="xl" title={coach?.name ?? ""}>
      {coach && (
        <CoachDrawerBody
          key={coach.id}
          planId={planId}
          coach={coach}
          allParticipants={allParticipants}
          allCoaches={allCoaches}
          onClose={onClose}
        />
      )}
    </Drawer>
  );
}

interface ProfileDraft {
  coachLevel: number | null;
  canCoachMinLevel: number | null;
  canCoachMaxLevel: number | null;
  maxGroupsPerDay: number | null;
  maxGroupsPerWeek: number | null;
  canAlsoTrainAsParticipant: boolean;
  notes: string;
}

function profileDraftFrom(coach: CoachRow): ProfileDraft {
  return {
    coachLevel: coach.coachLevel ?? null,
    canCoachMinLevel: coach.canCoachMinLevel ?? null,
    canCoachMaxLevel: coach.canCoachMaxLevel ?? null,
    maxGroupsPerDay: coach.maxGroupsPerDay ?? null,
    maxGroupsPerWeek: coach.maxGroupsPerWeek ?? null,
    canAlsoTrainAsParticipant: coach.canAlsoTrainAsParticipant,
    notes: coach.notes ?? "",
  };
}

function diff<T extends object>(draft: T, original: T): Partial<T> {
  const changed: Partial<T> = {};
  for (const key of Object.keys(draft) as (keyof T)[]) {
    if (JSON.stringify(draft[key]) !== JSON.stringify(original[key])) {
      changed[key] = draft[key];
    }
  }
  return changed;
}

interface CoachDrawerBodyProps {
  planId: string;
  coach: CoachRow;
  allParticipants: ParticipantRow[];
  allCoaches: CoachOption[];
  onClose: () => void;
}

function CoachDrawerBody({ planId, coach, allParticipants, allCoaches, onClose }: CoachDrawerBodyProps) {
  const timeSlots = useTimeSlots(planId);
  const availability = useCoachAvailability(planId, coach.id);
  const fieldDefinitions = useFieldDefinitions(planId);
  const fieldValues = useCoachFieldValues(planId, coach.id);
  const updateCoach = useUpdateCoach(planId);
  const setAvailability = useSetCoachAvailability(planId, coach.id);
  const updateFieldValues = useUpdateCoachFieldValues(planId, coach.id);
  const deleteCoach = useDeleteCoach(planId);

  const [profileDraft, setProfileDraft] = useState<ProfileDraft>(() => profileDraftFrom(coach));
  const [originalProfile, setOriginalProfile] = useState<ProfileDraft>(() => profileDraftFrom(coach));
  const [availabilityDraft, setAvailabilityDraft] = useState<AvailabilityDraft>({});
  const [originalAvailability, setOriginalAvailability] = useState<AvailabilityDraft>({});
  const [customDraft, setCustomDraft] = useState<Record<string, unknown>>({});
  const [originalCustom, setOriginalCustom] = useState<Record<string, unknown>>({});
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (timeSlots.data && availability.data) {
      const base = initialAvailabilityDraft(timeSlots.data.map((slot) => slot.id));
      const applied = applyAvailabilityEntries(base, availability.data);
      setAvailabilityDraft(applied);
      setOriginalAvailability(applied);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [timeSlots.data, availability.data]);

  useEffect(() => {
    if (fieldValues.data) {
      const values: Record<string, unknown> = {};
      for (const fv of fieldValues.data) {
        values[fv.key] = fv.value ?? null;
      }
      setCustomDraft(values);
      setOriginalCustom(values);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fieldValues.data]);

  const profileChanges = diff(profileDraft, originalProfile);
  const customChanges = diff(customDraft, originalCustom);
  const availabilityChanged = !availabilityDraftsEqual(availabilityDraft, originalAvailability);
  const isDirty = Object.keys(profileChanges).length > 0 || Object.keys(customChanges).length > 0 || availabilityChanged;

  const handleSave = async () => {
    setSaving(true);
    try {
      if (Object.keys(profileChanges).length > 0) {
        await updateCoach.mutateAsync({ id: coach.id, body: profileChanges });
      }
      if (availabilityChanged) {
        await setAvailability.mutateAsync(toAvailabilityEntries(availabilityDraft));
        setOriginalAvailability(availabilityDraft);
      }
      if (Object.keys(customChanges).length > 0) {
        await updateFieldValues.mutateAsync(customChanges);
      }
      setOriginalProfile(profileDraft);
      setOriginalCustom(customDraft);
      notifications.show({ color: "green", message: sv.coaches.drawer.saveSuccess });
    } catch (error) {
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: error instanceof ApiError ? error.message : sv.coaches.drawer.saveFailed,
      });
    } finally {
      setSaving(false);
    }
  };

  return (
    <Stack gap="md">
      <Title order={5}>{sv.coaches.drawer.profileHeading}</Title>
      <SimpleGrid cols={2} spacing="md">
        <NumberInput
          label={sv.coaches.drawer.coachLevelLabel}
          description={<HelpTip label={sv.help.ariaLabel(sv.coaches.drawer.coachLevelLabel)}>{sv.help.coaches.coachLevel}</HelpTip>}
          min={0}
          max={1000}
          value={profileDraft.coachLevel ?? ""}
          onChange={(value) => setProfileDraft((prev) => ({ ...prev, coachLevel: value === "" ? null : Number(value) }))}
        />
        <Switch
          label={sv.coaches.drawer.alsoParticipantLabel}
          description={
            <HelpTip label={sv.help.ariaLabel(sv.coaches.drawer.alsoParticipantLabel)}>{sv.help.coaches.alsoParticipant}</HelpTip>
          }
          mt="xl"
          checked={profileDraft.canAlsoTrainAsParticipant}
          onChange={(event) =>
            setProfileDraft((prev) => ({ ...prev, canAlsoTrainAsParticipant: event.currentTarget.checked }))
          }
        />
        <NumberInput
          label={sv.coaches.drawer.canCoachMinLabel}
          description={
            <HelpTip label={sv.help.ariaLabel(sv.coaches.drawer.canCoachMinLabel)}>{sv.help.coaches.canCoachRange}</HelpTip>
          }
          min={0}
          max={1000}
          value={profileDraft.canCoachMinLevel ?? ""}
          onChange={(value) =>
            setProfileDraft((prev) => ({ ...prev, canCoachMinLevel: value === "" ? null : Number(value) }))
          }
        />
        <NumberInput
          label={sv.coaches.drawer.canCoachMaxLabel}
          min={0}
          max={1000}
          value={profileDraft.canCoachMaxLevel ?? ""}
          onChange={(value) =>
            setProfileDraft((prev) => ({ ...prev, canCoachMaxLevel: value === "" ? null : Number(value) }))
          }
        />
        <NumberInput
          label={sv.coaches.drawer.maxGroupsPerDayLabel}
          description={
            <HelpTip label={sv.help.ariaLabel(sv.coaches.drawer.maxGroupsPerDayLabel)}>{sv.help.coaches.maxGroupsPerDay}</HelpTip>
          }
          min={1}
          value={profileDraft.maxGroupsPerDay ?? ""}
          onChange={(value) =>
            setProfileDraft((prev) => ({ ...prev, maxGroupsPerDay: value === "" ? null : Number(value) }))
          }
        />
        <NumberInput
          label={sv.coaches.drawer.maxGroupsPerWeekLabel}
          description={
            <HelpTip label={sv.help.ariaLabel(sv.coaches.drawer.maxGroupsPerWeekLabel)}>{sv.help.coaches.maxGroupsPerWeek}</HelpTip>
          }
          min={1}
          value={profileDraft.maxGroupsPerWeek ?? ""}
          onChange={(value) =>
            setProfileDraft((prev) => ({ ...prev, maxGroupsPerWeek: value === "" ? null : Number(value) }))
          }
        />
      </SimpleGrid>
      <Textarea
        label={sv.coaches.drawer.notesLabel}
        autosize
        minRows={2}
        value={profileDraft.notes}
        onChange={(event) => setProfileDraft((prev) => ({ ...prev, notes: event.currentTarget.value }))}
      />

      <Divider />

      <div>
        <Group gap={4}>
          <Title order={5}>{sv.coaches.drawer.availabilityHeading}</Title>
          <HelpTip label={sv.help.ariaLabel(sv.coaches.drawer.availabilityHeading)}>{sv.help.coaches.availability}</HelpTip>
        </Group>
        <Text size="sm" c="dimmed" mb="sm">
          {sv.coaches.drawer.availabilityHint}
        </Text>
        {timeSlots.isLoading || availability.isLoading ? (
          <Loader size="sm" />
        ) : (
          <AvailabilityMatrix
            timeSlots={timeSlots.data ?? []}
            draft={availabilityDraft}
            onChange={(timeSlotId, kind) => setAvailabilityDraft((prev) => ({ ...prev, [timeSlotId]: kind }))}
            disabled={setAvailability.isPending}
          />
        )}
      </div>

      <Divider />

      <Title order={5}>{sv.coaches.drawer.customFieldsHeading}</Title>
      {fieldValues.isLoading && <Loader size="sm" />}
      {fieldValues.isError && (
        <Alert color="red">
          {fieldValues.error instanceof ApiError ? fieldValues.error.message : sv.coaches.drawer.fieldValuesSaveFailed}
        </Alert>
      )}
      {fieldValues.data && (
        <ScrollArea.Autosize mah={320}>
          <SimpleGrid cols={2} spacing="md">
            {fieldValues.data.map((fv) => (
              <CustomFieldEditor
                key={fv.key}
                fieldValue={fv}
                definition={fieldDefinitions.data?.find((def) => def.id === fv.fieldDefinitionId)}
                value={customDraft[fv.key] ?? null}
                onChange={(value) => setCustomDraft((prev) => ({ ...prev, [fv.key]: value }))}
                participants={allParticipants}
                coaches={allCoaches}
                timeSlots={timeSlots.data ?? []}
                selfId={coach.id}
              />
            ))}
          </SimpleGrid>
        </ScrollArea.Autosize>
      )}

      <Group justify="space-between" mt="md">
        <Button color="red" variant="outline" size="xs" onClick={() => setDeleteOpen(true)}>
          {sv.coaches.drawer.deleteButton}
        </Button>
        <Group>
          <Button variant="default" onClick={onClose}>
            {sv.coaches.drawer.closeButton}
          </Button>
          <Button onClick={handleSave} disabled={!isDirty} loading={saving}>
            {sv.coaches.drawer.saveButton}
          </Button>
        </Group>
      </Group>

      <DeleteConfirmModal
        opened={deleteOpen}
        title={sv.coaches.deleteModal.title}
        message={sv.coaches.deleteModal.message(coach.name)}
        confirmLabel={sv.coaches.deleteModal.confirm}
        loading={deleteCoach.isPending}
        onClose={() => setDeleteOpen(false)}
        onConfirm={() =>
          deleteCoach.mutate(coach.id, {
            onSuccess: () => {
              setDeleteOpen(false);
              onClose();
            },
            onError: (error) => {
              notifications.show({
                color: "red",
                title: sv.common.error,
                message: error instanceof ApiError ? error.message : sv.coaches.deleteModal.failed,
              });
            },
          })
        }
      />
    </Stack>
  );
}
