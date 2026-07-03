import { useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Alert,
  Anchor,
  Box,
  Button,
  Card,
  Group,
  Loader,
  Stack,
  Table,
  Text,
  Title,
} from "@mantine/core";
import { IconListDetails } from "@tabler/icons-react";
import { notifications } from "@mantine/notifications";
import { useSeasons } from "../../api/seasons";
import { useRecentPlans } from "../../api/plans";
import { useCreateDemoData } from "../../api/demo";
import { ApiError } from "../../api/client";
import { sv } from "../../i18n/sv";
import { EmptyState } from "../../components/EmptyState";
import { TutorialBanner } from "../../components/tutorial/TutorialBanner";
import { CreateSeasonModal } from "./CreateSeasonModal";
import { ImportEntryModal } from "./ImportEntryModal";

export function StartPage() {
  const navigate = useNavigate();
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [importModalOpen, setImportModalOpen] = useState(false);
  const seasons = useSeasons();
  const recentPlans = useRecentPlans((seasons.data ?? []).map((season) => season.id));
  const createDemoData = useCreateDemoData();

  const handleCreateDemoData = async () => {
    try {
      const result = await createDemoData.mutateAsync();
      notifications.show({ color: "green", message: sv.start.demoDataSuccess });
      navigate(`/plans/${result.planId}/deltagare`);
    } catch (error) {
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: error instanceof ApiError ? error.message : sv.start.demoDataFailed,
      });
    }
  };

  return (
    <Stack gap="xl" py="md">
      <Box>
        <Title order={2}>{sv.start.heading}</Title>
        <Text c="dimmed">{sv.start.subheading}</Text>
      </Box>

      <TutorialBanner />

      <Group>
        <Button onClick={() => setCreateModalOpen(true)}>{sv.start.createSeasonButton}</Button>
        <Button variant="default" onClick={() => setImportModalOpen(true)}>
          {sv.start.importButton}
        </Button>
        <Button
          variant="subtle"
          loading={createDemoData.isPending}
          onClick={handleCreateDemoData}
          data-testid="load-demo-data"
        >
          {sv.start.demoDataButton}
        </Button>
      </Group>

      <Card withBorder>
        <Title order={4} mb="sm">
          {sv.start.openSeasonHeading}
        </Title>
        {seasons.isLoading && <Loader size="sm" />}
        {seasons.isError && (
          <Alert color="red">
            {seasons.error instanceof ApiError ? seasons.error.message : sv.start.loadFailed}
          </Alert>
        )}
        {seasons.data && seasons.data.length === 0 && (
          <Alert color="blue" mb="md">
            <Text size="sm" mb="xs">
              {sv.start.noSeasons}
            </Text>
            <Text size="sm" mb="xs">
              {sv.start.demoDataEmptyStateBody}
            </Text>
            <Button
              size="xs"
              variant="light"
              loading={createDemoData.isPending}
              onClick={handleCreateDemoData}
              data-testid="load-demo-data-empty-state"
            >
              {sv.start.demoDataButton}
            </Button>
          </Alert>
        )}
        {seasons.data && seasons.data.length > 0 && (
          <Table verticalSpacing="xs" striped highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{sv.common.name}</Table.Th>
                <Table.Th>{sv.common.status}</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {seasons.data.map((season) => (
                <Table.Tr key={season.id}>
                  <Table.Td>
                    <Anchor onClick={() => navigate(`/seasons/${season.id}`)}>{season.name}</Anchor>
                  </Table.Td>
                  <Table.Td>{season.status}</Table.Td>
                  <Table.Td>
                    <Button
                      size="xs"
                      variant="subtle"
                      onClick={() => navigate(`/seasons/${season.id}`)}
                    >
                      {sv.start.openButton}
                    </Button>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Card>

      <Card withBorder>
        <Title order={4} mb="sm">
          {sv.start.recentPlansHeading}
        </Title>
        {recentPlans.isLoading && <Loader size="sm" />}
        {!recentPlans.isLoading && recentPlans.plans.length === 0 && (
          <EmptyState icon={<IconListDetails size={22} stroke={1.75} />} message={sv.start.noPlans} />
        )}
        {recentPlans.plans.length > 0 && (
          <Table verticalSpacing="xs" striped highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{sv.common.name}</Table.Th>
                <Table.Th>{sv.common.category}</Table.Th>
                <Table.Th>{sv.common.status}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {recentPlans.plans.map((plan) => (
                <Table.Tr key={plan.id}>
                  <Table.Td>
                    <Anchor onClick={() => navigate(`/plans/${plan.id}`)}>{plan.name}</Anchor>
                  </Table.Td>
                  <Table.Td>{plan.category}</Table.Td>
                  <Table.Td>{plan.status}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Card>

      <CreateSeasonModal
        opened={createModalOpen}
        onClose={() => setCreateModalOpen(false)}
        onCreated={(seasonId) => {
          setCreateModalOpen(false);
          navigate(`/seasons/${seasonId}`);
        }}
      />

      <ImportEntryModal
        opened={importModalOpen}
        onClose={() => setImportModalOpen(false)}
        onContinue={(planId) => navigate(`/plans/${planId}/import`)}
      />
    </Stack>
  );
}
