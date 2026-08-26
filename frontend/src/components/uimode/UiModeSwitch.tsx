import { useState } from "react";
import { Button, Group, Modal, Switch, Text } from "@mantine/core";
import { useUiMode } from "../../lib/uiMode/useUiMode";
import { sv } from "../../i18n/sv";

/**
 * The discreet navbar toggle (bottom of the 240px navbar, mounted from AppShellLayout). Turning it
 * ON (SIMPLE -> ADVANCED) opens a confirm modal first - advanced mode surfaces every setting
 * (weights, coaches, field builder, export options), so it's a deliberate opt-in. Turning it OFF
 * (ADVANCED -> SIMPLE) is friction-free, matching the confirm copy's own "you can always go back"
 * promise.
 */
export function UiModeSwitch() {
  const { isAdvanced, setMode } = useUiMode();
  const [confirmOpened, setConfirmOpened] = useState(false);

  return (
    <>
      <Group justify="space-between" wrap="nowrap" gap="xs">
        <Text size="xs" c="dimmed">
          {sv.uiMode.navLabel}
        </Text>
        <Switch
          size="xs"
          checked={isAdvanced}
          aria-label={sv.uiMode.switchAriaLabel}
          data-testid="ui-mode-switch"
          onChange={(event) => {
            if (event.currentTarget.checked) {
              setConfirmOpened(true);
            } else {
              setMode("SIMPLE");
            }
          }}
        />
      </Group>

      <Modal
        opened={confirmOpened}
        onClose={() => setConfirmOpened(false)}
        title={sv.uiMode.enableConfirm.title}
      >
        <Text size="sm">{sv.uiMode.enableConfirm.body}</Text>
        <Group justify="flex-end" mt="lg">
          <Button variant="default" onClick={() => setConfirmOpened(false)}>
            {sv.uiMode.enableConfirm.cancel}
          </Button>
          <Button
            data-testid="ui-mode-confirm-enable"
            onClick={() => {
              setMode("ADVANCED");
              setConfirmOpened(false);
            }}
          >
            {sv.uiMode.enableConfirm.confirm}
          </Button>
        </Group>
      </Modal>
    </>
  );
}
