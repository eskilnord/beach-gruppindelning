import { Group, Switch, Text } from "@mantine/core";
import { useUiMode } from "../../lib/uiMode/useUiMode";
import { sv } from "../../i18n/sv";
import { useConfirmedAdvancedMode } from "./useConfirmedAdvancedMode";

/**
 * The discreet navbar toggle (bottom of the 240px navbar, mounted from AppShellLayout). Turning it
 * ON (SIMPLE -> ADVANCED) opens a confirm modal first - advanced mode surfaces every setting
 * (weights, coaches, field builder, export options), so it's a deliberate opt-in. Turning it OFF
 * (ADVANCED -> SIMPLE) is friction-free, matching the confirm copy's own "you can always go back"
 * promise.
 *
 * v0.6.0 audit-fix A2/A6: the confirm modal itself now lives in the shared useConfirmedAdvancedMode
 * hook (components/uimode/useConfirmedAdvancedMode.tsx) - UiModeIntroBanner.tsx and
 * AdvancedRouteGate.tsx route their own "enter advanced mode" actions through the same hook, so all
 * three stay in sync automatically instead of duplicating this modal's copy/behavior three times.
 */
export function UiModeSwitch() {
  const { isAdvanced, setMode } = useUiMode();
  const { requestAdvancedMode, confirmModal } = useConfirmedAdvancedMode();

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
              requestAdvancedMode();
            } else {
              setMode("SIMPLE");
            }
          }}
        />
      </Group>

      {confirmModal}
    </>
  );
}
