import { useState } from "react";
import { Overlay, Stack, Text, Loader, Button, Center } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { useBackendHealth } from "../api/health";
import { restartBackend } from "../api/client";
import { sv } from "../i18n/sv";

/**
 * Full-screen reconnect overlay for a mid-session backend crash (docs/design/01-architecture.md §4
 * failure UX — browser-mode variant of the Tauri "backend-crashed" event + health-poll flip). Only
 * renders once we've confirmed the backend is unreachable; a plain loading state during first paint
 * is left to the footer indicator so the app doesn't flash an overlay on every cold start.
 *
 * The retry button actively recovers the backend via `restartBackend()` (Tauri: kills+respawns the
 * child and re-handshakes on a fresh port/token via the shell's `retry_backend` command; browser:
 * resets the cached backend-info promise) rather than just refetching the health check against a
 * connection that may never come back — previously a crashed backend required restarting the whole
 * app. All queries are invalidated afterwards so the rest of the app picks up the new port/token.
 */
export function ReconnectOverlay() {
  const queryClient = useQueryClient();
  const { data, isError, isLoading, refetch, isFetching } = useBackendHealth();
  const [isRestarting, setIsRestarting] = useState(false);
  const [restartError, setRestartError] = useState(false);

  const unreachable = !isLoading && (isError || data?.status !== "UP");
  if (!unreachable) {
    return null;
  }

  const handleRetry = async () => {
    setIsRestarting(true);
    setRestartError(false);
    try {
      await restartBackend();
      await queryClient.invalidateQueries();
      await refetch();
    } catch {
      setRestartError(true);
    } finally {
      setIsRestarting(false);
    }
  };

  return (
    <Overlay fixed blur={2} zIndex={1000}>
      <Center h="100%">
        <Stack align="center" gap="md">
          <Loader />
          <Text c="white" fw={500}>
            {sv.backendStatus.reconnecting}
          </Text>
          <Button variant="white" loading={isRestarting || isFetching} onClick={() => void handleRetry()}>
            {sv.backendStatus.retryButton}
          </Button>
          {restartError && (
            <Text c="red.3" size="sm">
              {sv.backendStatus.restartFailed}
            </Text>
          )}
        </Stack>
      </Center>
    </Overlay>
  );
}
