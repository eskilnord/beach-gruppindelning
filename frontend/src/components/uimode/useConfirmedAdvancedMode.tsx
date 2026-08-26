import { useRef, useState } from "react";
import { Button, Group, Modal, Text } from "@mantine/core";
import { useUiMode } from "../../lib/uiMode/useUiMode";
import { sv } from "../../i18n/sv";

export interface UseConfirmedAdvancedModeResult {
  /**
   * Call this instead of `setMode("ADVANCED")` directly whenever a SIMPLE-mode screen offers an
   * "Öppna avancerat läge" affordance. `UiModeSwitch.tsx`'s own navbar toggle now ALSO routes through
   * this same hook (v0.6.0 audit-fix A2/A6 - it used to inline its own separate confirm modal), so
   * every entry point into ADVANCED mode, the navbar switch included, shares this one confirm gate.
   * If the app is already in ADVANCED mode this is a same-mode no-op that
   * just runs `onConfirmed` straight away (no point confirming a mode the admin is already in - see
   * `priorities.spec.ts`'s own "already ADVANCED here... so no confirm modal" expectation); otherwise
   * it opens {@link confirmModal} and only switches mode (then runs `onConfirmed`) once the admin
   * explicitly confirms.
   */
  requestAdvancedMode: (onConfirmed?: () => void) => void;
  /** Render this once, anywhere in the tree alongside the trigger that calls `requestAdvancedMode`. */
  confirmModal: React.ReactElement;
}

/**
 * v0.6.0 audit batch D (D2): a shared confirm gate for every "Öppna avancerat läge" affordance,
 * INCLUDING the navbar's own `UiModeSwitch` (v0.6.0 audit-fix A2/A6: it used to inline its own
 * separate copy of this same confirm modal on its own toggle; it now routes through this hook too,
 * same as every other caller) - same copy (`sv.uiMode.enableConfirm`), same "you can always go back"
 * framing, so every entry point into ADVANCED mode asks the same deliberate-opt-in question rather
 * than each screen inventing its own variant (or, worse, skipping the confirm entirely - the bug this
 * hook fixes for PrioritiesPanel.tsx's "Öppna avancerat läge" button).
 */
export function useConfirmedAdvancedMode(): UseConfirmedAdvancedModeResult {
  const { isAdvanced, setMode } = useUiMode();
  const [opened, setOpened] = useState(false);
  const pendingRef = useRef<(() => void) | null>(null);

  const requestAdvancedMode = (onConfirmed?: () => void) => {
    if (isAdvanced) {
      onConfirmed?.();
      return;
    }
    pendingRef.current = onConfirmed ?? null;
    setOpened(true);
  };

  const close = () => {
    setOpened(false);
    pendingRef.current = null;
  };

  const confirmModal = (
    <Modal opened={opened} onClose={close} title={sv.uiMode.enableConfirm.title}>
      <Text size="sm">{sv.uiMode.enableConfirm.body}</Text>
      <Group justify="flex-end" mt="lg">
        <Button variant="default" onClick={close}>
          {sv.uiMode.enableConfirm.cancel}
        </Button>
        <Button
          data-testid="confirmed-advanced-mode-confirm"
          onClick={() => {
            const onConfirmed = pendingRef.current;
            setMode("ADVANCED");
            setOpened(false);
            pendingRef.current = null;
            onConfirmed?.();
          }}
        >
          {sv.uiMode.enableConfirm.confirm}
        </Button>
      </Group>
    </Modal>
  );

  return { requestAdvancedMode, confirmModal };
}
