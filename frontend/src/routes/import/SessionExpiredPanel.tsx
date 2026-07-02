import { Alert, Button, Stack, Text } from "@mantine/core";
import { sv } from "../../i18n/sv";

interface SessionExpiredPanelProps {
  onRestart: () => void;
}

/** Shown whenever an import-wizard call 404s (expired/unknown ImportSession — sessions purge after
 *  1h of inactivity, backend/docs/m3-notes.md) instead of a generic error screen. */
export function SessionExpiredPanel({ onRestart }: SessionExpiredPanelProps) {
  return (
    <Alert color="red" title={sv.importWizard.sessionExpired.title}>
      <Stack gap="sm">
        <Text>{sv.importWizard.sessionExpired.message}</Text>
        <Button onClick={onRestart} w="fit-content">
          {sv.importWizard.sessionExpired.restartButton}
        </Button>
      </Stack>
    </Alert>
  );
}
