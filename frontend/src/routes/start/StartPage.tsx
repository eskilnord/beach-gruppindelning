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
import { useSeasons } from "../../api/seasons";
import { useRecentPlans } from "../../api/plans";
import { ApiError } from "../../api/client";
import { sv } from "../../i18n/sv";
import { TutorialBanner } from "../../components/tutorial/TutorialBanner";
import { CreateSeasonModal } from "./CreateSeasonModal";
import { ImportEntryModal } from "./ImportEntryModal";

export function StartPage() {
  const navigate = useNavigate();
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [importModalOpen, setImportModalOpen] = useState(false);
  const seasons = useSeasons();
  const recentPlans = useRecentPlans((seasons.data ?? []).map((season) => season.id));

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
          <Text c="dimmed">{sv.start.noSeasons}</Text>
        )}
        {seasons.data && seasons.data.length > 0 && (
          <Table verticalSpacing="xs">
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
          <Text c="dimmed">{sv.start.noPlans}</Text>
        )}
        {recentPlans.plans.length > 0 && (
          <Table verticalSpacing="xs">
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
