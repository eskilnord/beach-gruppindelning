import { useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  ActionIcon,
  Alert,
  Anchor,
  Badge,
  Box,
  Breadcrumbs,
  Button,
  Card,
  Group,
  Loader,
  Menu,
  Stack,
  Table,
  Text,
  Title,
} from "@mantine/core";
import { IconDots, IconListDetails } from "@tabler/icons-react";
import { useSeasonConflicts } from "../../api/conflicts";
import { useDeleteSeason, useSeason } from "../../api/seasons";
import { usePlanCounts, usePlansForSeason } from "../../api/plans";
import { userErrorText, technicalErrorDetail } from "../../lib/errorText";
import { ConflictList } from "../../components/ConflictList";
import { EmptyState } from "../../components/EmptyState";
import { AdvancedOnly } from "../../components/uimode/AdvancedOnly";
import { useIsSimpleMode } from "../../lib/uiMode/useUiMode";
import { sv } from "../../i18n/sv";
import { CreatePlanModal } from "./CreatePlanModal";
import { EditSeasonModal } from "./EditSeasonModal";
import { DeleteConfirmModal } from "../../components/DeleteConfirmModal";

export function SeasonPage() {
  const { seasonId } = useParams<{ seasonId: string }>();
  const navigate = useNavigate();
  const isSimple = useIsSimpleMode();
  const season = useSeason(seasonId);
  const plans = usePlansForSeason(seasonId);
  const deleteSeason = useDeleteSeason();
  const conflicts = useSeasonConflicts(seasonId);

  const planIds = useMemo(() => (plans.data ?? []).map((plan) => plan.id), [plans.data]);
  const { counts } = usePlanCounts(planIds);

  const [createPlanOpen, setCreatePlanOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);

  if (season.isLoading) {
    return <Loader />;
  }

  if (season.isError || !season.data) {
    // v0.6.0 audit-fix A4: keeps the Hem breadcrumb rendered even in the error branch (it used to
    // return a bare Alert, dropping all navigation chrome) and adds a "Försök igen" retry button.
    const technical = season.isError ? technicalErrorDetail(season.error) : undefined;
    return (
      <Stack gap="lg" py="md">
        <Breadcrumbs>
          <Anchor onClick={() => navigate("/")}>{sv.nav.home}</Anchor>
        </Breadcrumbs>
        <Alert color="red">
          <Text size="sm">{userErrorText(season.error, sv.season.notFound)}</Text>
          {technical && (
            <Text size="xs" c="dimmed" mt={4}>
              {sv.common.technicalInfo(technical)}
            </Text>
          )}
          <Button size="xs" variant="light" mt="sm" onClick={() => season.refetch()}>
            {sv.common.retryButton}
          </Button>
        </Alert>
      </Stack>
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
          {/* v0.6.0 audit-fix A5: raw status text is ADVANCED-only, matching PlanLayout's own status
              badge gating. */}
          <AdvancedOnly>
            <Text c="dimmed">{data.status}</Text>
          </AdvancedOnly>
        </Box>
        {/* v0.6.0 audit-fix A5: SIMPLE collapses Redigera/Ta bort into one Menu behind an IconDots
            ActionIcon - mirrors PlanLayout.tsx's exact pattern. */}
        <AdvancedOnly>
          <Group>
            <Button variant="default" onClick={() => setEditOpen(true)}>
              {sv.season.editSeasonButton}
            </Button>
            <Button variant="default" color="red" onClick={() => setDeleteOpen(true)}>
              {sv.season.deleteSeasonButton}
            </Button>
          </Group>
        </AdvancedOnly>
        {isSimple && (
          <Menu withinPortal position="bottom-end">
            <Menu.Target>
              <ActionIcon
                variant="default"
                size="lg"
                aria-label={sv.season.menu.ariaLabel}
                data-testid="season-header-menu-button"
              >
                <IconDots size={18} />
              </ActionIcon>
            </Menu.Target>
            <Menu.Dropdown>
              <Menu.Item onClick={() => setEditOpen(true)}>{sv.season.menu.edit}</Menu.Item>
              <Menu.Item color="red" onClick={() => setDeleteOpen(true)}>
                {sv.season.menu.delete}
              </Menu.Item>
            </Menu.Dropdown>
          </Menu>
        )}
      </Group>

      <Card withBorder>
        <Group justify="space-between" mb="sm">
          <Box>
            <Title order={4}>{sv.season.plansHeading}</Title>
            {isSimple && (
              <Text size="sm" c="dimmed">
                {sv.season.plansHeadingDefinitionSimple}
              </Text>
            )}
          </Box>
          <Button onClick={() => setCreatePlanOpen(true)}>{sv.season.createPlanButton}</Button>
        </Group>

        {plans.isLoading && <Loader size="sm" />}
        {plans.isError && (
          <Alert color="red">
            <Text size="sm">{userErrorText(plans.error, sv.season.loadFailed)}</Text>
            {technicalErrorDetail(plans.error) && (
              <Text size="xs" c="dimmed" mt={4}>
                {sv.common.technicalInfo(technicalErrorDetail(plans.error)!)}
              </Text>
            )}
          </Alert>
        )}
        {plans.data && plans.data.length === 0 && (
          <EmptyState icon={<IconListDetails size={22} stroke={1.75} />} message={sv.season.noPlans} />
        )}
        {plans.data && plans.data.length > 0 && (
          <Table verticalSpacing="xs" striped highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{sv.season.columns.name}</Table.Th>
                <Table.Th>{sv.season.columns.category}</Table.Th>
                {/* v0.6.0 audit-fix A5: Status column is ADVANCED-only, same render-filter-only
                    treatment as the pre-existing Tränare column below - the query is unchanged. */}
                {!isSimple && <Table.Th>{sv.season.columns.status}</Table.Th>}
                <Table.Th>{sv.season.columns.participants}</Table.Th>
                <Table.Th>{sv.season.columns.groups}</Table.Th>
                {!isSimple && <Table.Th>{sv.season.columns.coaches}</Table.Th>}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {plans.data.map((plan) => {
                const planCounts = counts[plan.id];
                return (
                  <Table.Tr key={plan.id}>
                    <Table.Td>
                      <Anchor onClick={() => navigate(`/plans/${plan.id}`)}>{plan.name}</Anchor>
                    </Table.Td>
                    <Table.Td>{plan.category ?? sv.season.participantsPlaceholder}</Table.Td>
                    {!isSimple && <Table.Td>{plan.status}</Table.Td>}
                    <Table.Td>{planCounts ? planCounts.participants : sv.season.participantsPlaceholder}</Table.Td>
                    <Table.Td>{planCounts ? planCounts.groups : sv.season.participantsPlaceholder}</Table.Td>
                    {!isSimple && (
                      <Table.Td>{planCounts ? planCounts.coaches : sv.season.participantsPlaceholder}</Table.Td>
                    )}
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        )}
      </Card>

      {/* v0.6.0 audit-fix A5: raw conflict jargon is ADVANCED-only. */}
      <AdvancedOnly>
        <Card withBorder>
          <Group gap="xs" mb="sm">
            <Title order={4}>{sv.season.conflicts.heading}</Title>
            <Badge color={conflicts.data && conflicts.data.length > 0 ? "red" : "gray"} data-testid="conflicts-count-badge">
              {conflicts.data?.length ?? 0}
            </Badge>
          </Group>

          {conflicts.isLoading && <Loader size="sm" />}
          {conflicts.isError && (
            <Alert color="red">
              <Text size="sm">{userErrorText(conflicts.error, sv.season.conflicts.loadFailed)}</Text>
              {technicalErrorDetail(conflicts.error) && (
                <Text size="xs" c="dimmed" mt={4}>
                  {sv.common.technicalInfo(technicalErrorDetail(conflicts.error)!)}
                </Text>
              )}
            </Alert>
          )}
          {conflicts.data && conflicts.data.length === 0 && <Text c="dimmed">{sv.season.conflicts.empty}</Text>}
          <ConflictList conflicts={conflicts.data ?? []} />
        </Card>
      </AdvancedOnly>

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
        detailsSv={sv.deleteSeasonModal.detailsAllPlans}
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
