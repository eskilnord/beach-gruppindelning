import { useState } from "react";
import { Button, Group, Modal, NumberInput, SegmentedControl, Select, Stack, Switch, Textarea, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { notifications } from "@mantine/notifications";
import { useCreateCoach } from "../../../api/coaches";
import { usePersons } from "../../../api/persons";
import { ApiError } from "../../../api/client";
import { sv } from "../../../i18n/sv";

interface NewCoachModalProps {
  planId: string;
  opened: boolean;
  onClose: () => void;
}

interface FormValues {
  personId: string | null;
  firstName: string;
  lastName: string;
  email: string;
  coachLevel: number | "";
  canCoachMinLevel: number | "";
  canCoachMaxLevel: number | "";
  maxGroupsPerDay: number | "";
  maxGroupsPerWeek: number | "";
  canAlsoTrainAsParticipant: boolean;
  notes: string;
}

const EMPTY_VALUES: FormValues = {
  personId: null,
  firstName: "",
  lastName: "",
  email: "",
  coachLevel: "",
  canCoachMinLevel: "",
  canCoachMaxLevel: "",
  maxGroupsPerDay: "",
  maxGroupsPerWeek: "",
  canAlsoTrainAsParticipant: false,
  notes: "",
};

/**
 * "Ny tränare" modal (spec §13.1/§19.7): either links an existing Person (spec §13.2 - the same
 * person can be both a participant and a coach) via a searchable select, or creates a brand new
 * person inline. Mirrors CoachController.CreateCoachRequest's two branches exactly.
 */
export function NewCoachModal({ planId, opened, onClose }: NewCoachModalProps) {
  const createCoach = useCreateCoach(planId);
  const persons = usePersons();
  const [source, setSource] = useState<"existing" | "new">("existing");

  const form = useForm<FormValues>({
    initialValues: EMPTY_VALUES,
    validate: {
      personId: (value) => (source === "existing" && !value ? sv.coaches.newCoachModal.personRequired : null),
      firstName: (value) => (source === "new" && value.trim().length === 0 ? sv.coaches.newCoachModal.firstNameRequired : null),
      lastName: (value) => (source === "new" && value.trim().length === 0 ? sv.coaches.newCoachModal.lastNameRequired : null),
    },
  });

  const personOptions = (persons.data ?? []).map((person) => ({
    value: person.id,
    label: person.displayName || `${person.firstName} ${person.lastName}`.trim(),
  }));

  const handleClose = () => {
    form.reset();
    setSource("existing");
    onClose();
  };

  const handleSubmit = form.onSubmit(async (values) => {
    try {
      await createCoach.mutateAsync({
        personId: source === "existing" ? (values.personId ?? undefined) : undefined,
        firstName: source === "new" ? values.firstName.trim() : undefined,
        lastName: source === "new" ? values.lastName.trim() : undefined,
        email: source === "new" && values.email.trim().length > 0 ? values.email.trim() : undefined,
        coachLevel: values.coachLevel === "" ? undefined : Number(values.coachLevel),
        canCoachMinLevel: values.canCoachMinLevel === "" ? undefined : Number(values.canCoachMinLevel),
        canCoachMaxLevel: values.canCoachMaxLevel === "" ? undefined : Number(values.canCoachMaxLevel),
        maxGroupsPerDay: values.maxGroupsPerDay === "" ? undefined : Number(values.maxGroupsPerDay),
        maxGroupsPerWeek: values.maxGroupsPerWeek === "" ? undefined : Number(values.maxGroupsPerWeek),
        canAlsoTrainAsParticipant: values.canAlsoTrainAsParticipant,
        notes: values.notes.trim().length > 0 ? values.notes.trim() : undefined,
      });
      handleClose();
    } catch (error) {
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: error instanceof ApiError ? error.message : sv.coaches.newCoachModal.createFailed,
      });
    }
  });

  return (
    <Modal opened={opened} onClose={handleClose} title={sv.coaches.newCoachModal.title} centered size="lg">
      <form onSubmit={handleSubmit}>
        <Stack gap="sm">
          <SegmentedControl
            fullWidth
            value={source}
            onChange={(value) => setSource(value as "existing" | "new")}
            data={[
              { value: "existing", label: sv.coaches.newCoachModal.sourceExisting },
              { value: "new", label: sv.coaches.newCoachModal.sourceNew },
            ]}
          />

          {source === "existing" ? (
            <Select
              label={sv.coaches.newCoachModal.personLabel}
              placeholder={sv.coaches.newCoachModal.personPlaceholder}
              data={personOptions}
              searchable
              data-autofocus
              comboboxProps={{ withinPortal: false }}
              {...form.getInputProps("personId")}
            />
          ) : (
            <Group grow>
              <TextInput
                label={sv.coaches.newCoachModal.firstNameLabel}
                withAsterisk
                data-autofocus
                {...form.getInputProps("firstName")}
              />
              <TextInput label={sv.coaches.newCoachModal.lastNameLabel} withAsterisk {...form.getInputProps("lastName")} />
            </Group>
          )}
          {source === "new" && <TextInput label={sv.coaches.newCoachModal.emailLabel} {...form.getInputProps("email")} />}

          <Group grow>
            <NumberInput label={sv.coaches.newCoachModal.coachLevelLabel} min={0} max={1000} {...form.getInputProps("coachLevel")} />
            <NumberInput
              label={sv.coaches.newCoachModal.canCoachMinLabel}
              min={0}
              max={1000}
              {...form.getInputProps("canCoachMinLevel")}
            />
            <NumberInput
              label={sv.coaches.newCoachModal.canCoachMaxLabel}
              min={0}
              max={1000}
              {...form.getInputProps("canCoachMaxLevel")}
            />
          </Group>

          <Group grow>
            <NumberInput
              label={sv.coaches.newCoachModal.maxGroupsPerDayLabel}
              min={1}
              {...form.getInputProps("maxGroupsPerDay")}
            />
            <NumberInput
              label={sv.coaches.newCoachModal.maxGroupsPerWeekLabel}
              min={1}
              {...form.getInputProps("maxGroupsPerWeek")}
            />
          </Group>

          <Switch
            label={sv.coaches.newCoachModal.alsoParticipantLabel}
            checked={form.values.canAlsoTrainAsParticipant}
            onChange={(event) => form.setFieldValue("canAlsoTrainAsParticipant", event.currentTarget.checked)}
          />

          <Textarea label={sv.coaches.newCoachModal.notesLabel} autosize minRows={2} {...form.getInputProps("notes")} />

          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={handleClose}>
              {sv.common.cancel}
            </Button>
            <Button type="submit" loading={createCoach.isPending}>
              {sv.coaches.newCoachModal.submit}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
