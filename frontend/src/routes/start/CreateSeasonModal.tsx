import { Button, Group, Modal, Stack, TextInput } from "@mantine/core";
import { DateInput } from "@mantine/dates";
import { useForm } from "@mantine/form";
import { notifications } from "@mantine/notifications";
import dayjs from "dayjs";
import { useCreateSeason } from "../../api/seasons";
import { ApiError } from "../../api/client";
import { sv } from "../../i18n/sv";

interface CreateSeasonModalProps {
  opened: boolean;
  onClose: () => void;
  onCreated: (seasonId: string) => void;
}

interface FormValues {
  name: string;
  startDate: Date | null;
  endDate: Date | null;
}

const DATE_FORMAT = "YYYY-MM-DD";

export function CreateSeasonModal({ opened, onClose, onCreated }: CreateSeasonModalProps) {
  const createSeason = useCreateSeason();

  const form = useForm<FormValues>({
    initialValues: { name: "", startDate: null, endDate: null },
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
      const created = await createSeason.mutateAsync({
        name: values.name.trim(),
        startDate: values.startDate ? dayjs(values.startDate).format(DATE_FORMAT) : undefined,
        endDate: values.endDate ? dayjs(values.endDate).format(DATE_FORMAT) : undefined,
        status: undefined,
      });
      form.reset();
      onCreated(created.id);
    } catch (error) {
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: error instanceof ApiError ? error.message : sv.createSeasonModal.createFailed,
      });
    }
  });

  return (
    <Modal opened={opened} onClose={handleClose} title={sv.createSeasonModal.title} centered>
      <form onSubmit={handleSubmit}>
        <Stack gap="sm">
          <TextInput
            label={sv.createSeasonModal.nameLabel}
            placeholder={sv.createSeasonModal.namePlaceholder}
            withAsterisk
            data-autofocus
            {...form.getInputProps("name")}
          />
          <DateInput
            label={sv.createSeasonModal.startDateLabel}
            valueFormat="YYYY-MM-DD"
            clearable
            {...form.getInputProps("startDate")}
          />
          <DateInput
            label={sv.createSeasonModal.endDateLabel}
            valueFormat="YYYY-MM-DD"
            clearable
            {...form.getInputProps("endDate")}
          />
          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={handleClose}>
              {sv.common.cancel}
            </Button>
            <Button type="submit" loading={createSeason.isPending}>
              {sv.createSeasonModal.submit}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
