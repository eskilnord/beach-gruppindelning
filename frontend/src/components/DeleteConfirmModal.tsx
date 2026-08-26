import { Alert, Button, Group, Modal, Text } from "@mantine/core";
import { sv } from "../i18n/sv";

interface DeleteConfirmModalProps {
  opened: boolean;
  title: string;
  message: string;
  confirmLabel: string;
  loading: boolean;
  onConfirm: () => void;
  onClose: () => void;
  /** v0.6.0 F3 review fix (FIX 10, MINOR): a failed confirm's error, rendered as its own red Alert
   *  ABOVE `message` - previously PrioritiesPanel's reset flow swapped this straight into `message`
   *  (`message={resetError ?? sv.simple.priorities.resetConfirm.message}`), which silently replaced
   *  the "here's what this action does" explanation with the error text instead of showing both.
   *  Optional/omittable so every other caller (season/plan delete, WhatIfDialog, etc.) is unaffected. */
  errorMessage?: string | null;
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
  errorMessage,
}: DeleteConfirmModalProps) {
  return (
    <Modal opened={opened} onClose={onClose} title={title} centered>
      {errorMessage && (
        <Alert color="red" mb="sm">
          {errorMessage}
        </Alert>
      )}
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
