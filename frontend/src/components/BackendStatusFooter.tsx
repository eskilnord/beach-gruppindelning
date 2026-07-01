import { Group, Text, Loader, Button } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { useBackendHealth } from "../api/health";
import { sv } from "../i18n/sv";

/**
 * Footer status indicator (M2 brief: "Backend-status indicator in the shell footer"). Shows a
 * simple up/loading/down state; on failure it also exposes a retry button that refetches the health
 * query immediately instead of waiting for the next 5s tick.
 */
export function BackendStatusFooter() {
  const queryClient = useQueryClient();
  const { data, isError, isLoading, refetch, isFetching } = useBackendHealth();

  if (isLoading) {
    return (
      <Group gap="xs" px="md" py="xs">
        <Loader size="xs" />
        <Text size="sm" c="dimmed">
          {sv.common.loading}
        </Text>
      </Group>
    );
  }

  if (isError || data?.status !== "UP") {
    return (
      <Group gap="xs" px="md" py="xs" justify="space-between" bg="red.0">
        <Text size="sm" c="red.8">
          {sv.backendStatus.down}
        </Text>
        <Button
          size="xs"
          variant="light"
          color="red"
          loading={isFetching}
          onClick={() => {
            void refetch();
            void queryClient.invalidateQueries();
          }}
        >
          {sv.backendStatus.retryButton}
        </Button>
      </Group>
    );
  }

  return (
    <Group gap="xs" px="md" py="xs">
      <Text size="sm" c="green.8">
        {sv.backendStatus.up}
      </Text>
    </Group>
  );
}
