import { Button, Group, Modal, Stack, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { notifications } from "@mantine/notifications";
import { useEffect } from "react";
import { useUpdateSeason } from "../../api/seasons";
import { ApiError } from "../../api/client";
import { sv } from "../../i18n/sv";
import type { SeasonPlan } from "../../api/types";

interface EditSeasonModalProps {
  opened: boolean;
  season: SeasonPlan;
  onClose: () => void;
}

interface FormValues {
  name: string;
  status: string;
}

export function EditSeasonModal({ opened, season, onClose }: EditSeasonModalProps) {
  const updateSeason = useUpdateSeason(season.id);

  const form = useForm<FormValues>({
    initialValues: { name: season.name, status: season.status },
    validate: {
      name: (value) => (value.trim().length === 0 ? sv.common.nameRequired : null),
    },
  });

  // Keep the form in sync if the underlying season data changes (e.g. re-opening the modal later).
  useEffect(() => {
    if (opened) {
      form.setValues({ name: season.name, status: season.status });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened, season.id]);

  const handleSubmit = form.onSubmit(async (values) => {
    try {
      await updateSeason.mutateAsync({
        name: values.name.trim(),
        status: values.status,
        startDate: undefined,
        endDate: undefined,
      });
      onClose();
    } catch (error) {
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: error instanceof ApiError ? error.message : sv.editSeasonModal.updateFailed,
      });
    }
  });

  return (
    <Modal opened={opened} onClose={onClose} title={sv.editSeasonModal.title} centered>
      <form onSubmit={handleSubmit}>
        <Stack gap="sm">
          <TextInput
            label={sv.common.name}
            withAsterisk
            data-autofocus
            {...form.getInputProps("name")}
          />
          <TextInput label={sv.editSeasonModal.statusLabel} {...form.getInputProps("status")} />
          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={onClose}>
              {sv.common.cancel}
            </Button>
            <Button type="submit" loading={updateSeason.isPending}>
              {sv.editSeasonModal.submit}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
