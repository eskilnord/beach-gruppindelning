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
  /** v0.6.0 audit-fix A11 (additive): an optional extra line naming what actually gets deleted
   *  along with the record itself - e.g. "12 deltagare, 3 grupper och 2 sparade versioner tas
   *  bort." (PlanLayout.tsx) or the static "Alla planer i säsongen tas bort." (SeasonPage.tsx).
   *  Omitted entirely when there's nothing more specific to say than `message` already does. */
  detailsSv?: string;
  /** v0.6.0 audit-fix B7 (import wizard's cancel dialog): overrides the close button's default
   *  `sv.common.cancel` label - e.g. "Fortsätt importen" reads far less ambiguously than a bare
   *  "Avbryt" next to a red "Kasta importen" confirm button. Every other caller is unaffected
   *  (defaults to the previous behavior). */
  cancelLabel?: string;
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
  detailsSv,
  cancelLabel,
}: DeleteConfirmModalProps) {
  return (
    <Modal opened={opened} onClose={onClose} title={title} centered>
      <Text mb={detailsSv ? 4 : "lg"}>{message}</Text>
      {detailsSv && (
        <Text size="sm" c="dimmed" mb="lg">
          {detailsSv}
        </Text>
      )}
      <Group justify="flex-end">
        <Button variant="default" onClick={onClose} disabled={loading}>
          {cancelLabel ?? sv.common.cancel}
        </Button>
        <Button color="red" onClick={onConfirm} loading={loading}>
          {confirmLabel}
        </Button>
      </Group>
    </Modal>
  );
}
