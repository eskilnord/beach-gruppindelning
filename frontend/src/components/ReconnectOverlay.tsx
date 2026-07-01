import { Overlay, Stack, Text, Loader, Button, Center } from "@mantine/core";
import { useBackendHealth } from "../api/health";
import { sv } from "../i18n/sv";

/**
 * Full-screen reconnect overlay for a mid-session backend crash (docs/design/01-architecture.md §4
 * failure UX — browser-mode variant of the Tauri "backend-crashed" event + health-poll flip). Only
 * renders once we've confirmed the backend is unreachable; a plain loading state during first paint
 * is left to the footer indicator so the app doesn't flash an overlay on every cold start.
 */
export function ReconnectOverlay() {
  const { data, isError, isLoading, refetch, isFetching } = useBackendHealth();

  const unreachable = !isLoading && (isError || data?.status !== "UP");
  if (!unreachable) {
    return null;
  }

  return (
    <Overlay fixed blur={2} zIndex={1000}>
      <Center h="100%">
        <Stack align="center" gap="md">
          <Loader />
          <Text c="white" fw={500}>
            {sv.backendStatus.reconnecting}
          </Text>
          <Button variant="white" loading={isFetching} onClick={() => void refetch()}>
            {sv.backendStatus.retryButton}
          </Button>
        </Stack>
      </Center>
    </Overlay>
  );
}
