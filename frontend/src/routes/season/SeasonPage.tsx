import { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  Alert,
  Anchor,
  Box,
  Breadcrumbs,
  Button,
  Card,
  Group,
  Loader,
  Stack,
  Table,
  Text,
  Title,
} from "@mantine/core";
import { useDeleteSeason, useSeason } from "../../api/seasons";
import { usePlansForSeason } from "../../api/plans";
import { ApiError } from "../../api/client";
import { sv } from "../../i18n/sv";
import { CreatePlanModal } from "./CreatePlanModal";
import { EditSeasonModal } from "./EditSeasonModal";
import { DeleteConfirmModal } from "../../components/DeleteConfirmModal";

export function SeasonPage() {
  const { seasonId } = useParams<{ seasonId: string }>();
  const navigate = useNavigate();
  const season = useSeason(seasonId);
  const plans = usePlansForSeason(seasonId);
  const deleteSeason = useDeleteSeason();

  const [createPlanOpen, setCreatePlanOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);

  if (season.isLoading) {
    return <Loader />;
  }

  if (season.isError || !season.data) {
    return (
      <Alert color="red">
        {season.error instanceof ApiError ? season.error.message : sv.season.notFound}
      </Alert>
    );
  }

  const data = season.data;

  return (
    <Stack gap="lg" py="md">
      <Breadcrumbs>
        <Anchor onClick={() => navigate("/")}>{sv.nav.home}</Anchor>
        <Text>{data.name}</Text>
      </Breadcrumbs>

      <Group justify="space-between">
        <Box>
          <Title order={2}>{data.name}</Title>
          <Text c="dimmed">{data.status}</Text>
        </Box>
        <Group>
          <Button variant="default" onClick={() => setEditOpen(true)}>
            {sv.season.editSeasonButton}
          </Button>
          <Button variant="default" color="red" onClick={() => setDeleteOpen(true)}>
            {sv.season.deleteSeasonButton}
          </Button>
        </Group>
      </Group>

      <Card withBorder>
        <Group justify="space-between" mb="sm">
          <Title order={4}>{sv.season.plansHeading}</Title>
          <Button onClick={() => setCreatePlanOpen(true)}>{sv.season.createPlanButton}</Button>
        </Group>

        {plans.isLoading && <Loader size="sm" />}
        {plans.isError && (
          <Alert color="red">
            {plans.error instanceof ApiError ? plans.error.message : sv.season.loadFailed}
          </Alert>
        )}
        {plans.data && plans.data.length === 0 && <Text c="dimmed">{sv.season.noPlans}</Text>}
        {plans.data && plans.data.length > 0 && (
          <Table verticalSpacing="xs">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{sv.season.columns.name}</Table.Th>
                <Table.Th>{sv.season.columns.category}</Table.Th>
                <Table.Th>{sv.season.columns.status}</Table.Th>
                <Table.Th>{sv.season.columns.participants}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {plans.data.map((plan) => (
                <Table.Tr key={plan.id}>
                  <Table.Td>
                    <Anchor onClick={() => navigate(`/plans/${plan.id}`)}>{plan.name}</Anchor>
                  </Table.Td>
                  <Table.Td>{plan.category ?? sv.season.participantsPlaceholder}</Table.Td>
                  <Table.Td>{plan.status}</Table.Td>
                  <Table.Td>{sv.season.participantsPlaceholder}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Card>

      <CreatePlanModal
        opened={createPlanOpen}
        seasonId={data.id}
        onClose={() => setCreatePlanOpen(false)}
        onCreated={(planId) => {
          setCreatePlanOpen(false);
          navigate(`/plans/${planId}`);
        }}
      />

      <EditSeasonModal opened={editOpen} season={data} onClose={() => setEditOpen(false)} />

      <DeleteConfirmModal
        opened={deleteOpen}
        title={sv.deleteSeasonModal.title}
        message={sv.deleteSeasonModal.message(data.name)}
        confirmLabel={sv.deleteSeasonModal.confirm}
        loading={deleteSeason.isPending}
        onClose={() => setDeleteOpen(false)}
        onConfirm={() => {
          deleteSeason.mutate(data.id, {
            onSuccess: () => navigate("/"),
          });
        }}
      />
    </Stack>
  );
}
