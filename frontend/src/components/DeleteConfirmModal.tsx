import { Button, Group, Modal, Text } from "@mantine/core";
import { sv } from "../i18n/sv";

interface DeleteConfirmModalProps {
  opened: boolean;
  title: string;
  message: string;
  confirmLabel: string;
  loading: boolean;
  onConfirm: () => void;
  onClose: () => void;
}

/** Generic Ta bort-confirmation dialog, reused for season and activity-plan deletion. */
export function DeleteConfirmModal({
  opened,
  title,
  message,
  confirmLabel,
  loading,
  onConfirm,
  onClose,
}: DeleteConfirmModalProps) {
  return (
    <Modal opened={opened} onClose={onClose} title={title} centered>
      <Text mb="lg">{message}</Text>
      <Group justify="flex-end">
        <Button variant="default" onClick={onClose} disabled={loading}>
          {sv.common.cancel}
        </Button>
        <Button color="red" onClick={onConfirm} loading={loading}>
          {confirmLabel}
        </Button>
      </Group>
    </Modal>
  );
}
