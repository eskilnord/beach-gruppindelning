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
import { userErrorText, technicalErrorDetail } from "../../lib/errorText";
import { sv } from "../../i18n/sv";
import { EmptyState } from "../../components/EmptyState";
import { TutorialBanner } from "../../components/tutorial/TutorialBanner";
import { hasSeenTutorial } from "../../components/tutorial/tutorialSeenStore";
import { UiModeIntroBanner } from "../../components/uimode/UiModeIntroBanner";
import { useIsSimpleMode } from "../../lib/uiMode/useUiMode";
import { CreateSeasonModal } from "./CreateSeasonModal";
import { ImportEntryModal } from "./ImportEntryModal";

export function StartPage() {
  const navigate = useNavigate();
  const isSimple = useIsSimpleMode();
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [importModalOpen, setImportModalOpen] = useState(false);
  const seasons = useSeasons();
  const recentPlans = useRecentPlans((seasons.data ?? []).map((season) => season.id));
  const createDemoData = useCreateDemoData();

  // v0.6.0 audit-fix A2(d): captured once, synchronously, during THIS component's own render phase
  // (a lazy useState initializer runs before any effect, including TutorialBanner's own) - so this
  // reliably reflects whether TutorialBanner is ABOUT to show itself this mount, independent of
  // render order/effect-timing races with TutorialBanner's own internal `visible` state. Read-only:
  // never calls markTutorialSeen itself, that stays TutorialBanner's job alone.
  const [tutorialBannerShowing] = useState(() => !hasSeenTutorial());

  const handleCreateDemoData = async () => {
    try {
      const result = await createDemoData.mutateAsync();
      notifications.show({ color: "green", message: sv.start.demoDataSuccess });
      navigate(`/plans/${result.planId}/deltagare`);
    } catch (error) {
      const technical = technicalErrorDetail(error);
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: (
          <Stack gap={2}>
            <Text size="sm">{userErrorText(error, sv.start.demoDataFailed)}</Text>
            {technical && (
              <Text size="xs" c="dimmed">
                {sv.common.technicalInfo(technical)}
              </Text>
            )}
          </Stack>
        ),
      });
    }
  };

  const seasonsLoadFailedTechnical = seasons.isError ? technicalErrorDetail(seasons.error) : undefined;
  // v0.6.0 audit-fix A5: in SIMPLE mode, the empty-state alert already offers its own demo-data CTA
  // - don't also show the redundant "Prova med demodata" header button while it's showing (once
  // seasons exist, the empty-state is gone and the header button is the only demo affordance again).
  const hideHeaderDemoButton = isSimple && seasons.data && seasons.data.length === 0;

  return (
    <Stack gap="xl" py="md">
      <Box>
        <Title order={2}>{sv.start.heading}</Title>
        <Text c="dimmed">{sv.start.subheading}</Text>
      </Box>

      <TutorialBanner />
      <UiModeIntroBanner
        hasSeasons={seasons.data ? seasons.data.length > 0 : undefined}
        deferForTutorial={tutorialBannerShowing}
      />

      <Group>
        <Button onClick={() => setCreateModalOpen(true)}>{sv.start.createSeasonButton}</Button>
        <Button variant="default" onClick={() => setImportModalOpen(true)}>
          {sv.start.importButton}
        </Button>
        {!hideHeaderDemoButton && (
          <Button
            variant="subtle"
            loading={createDemoData.isPending}
            onClick={handleCreateDemoData}
            data-testid="load-demo-data"
          >
            {sv.start.demoDataButton}
          </Button>
        )}
      </Group>

      <Card withBorder>
        <Title order={4} mb="sm">
          {sv.start.openSeasonHeading}
        </Title>
        {seasons.isLoading && <Loader size="sm" />}
        {seasons.isError && (
          <Alert color="red" mb="md">
            <Text size="sm">{userErrorText(seasons.error, sv.start.loadFailed)}</Text>
            {seasonsLoadFailedTechnical && (
              <Text size="xs" c="dimmed" mt={4}>
                {sv.common.technicalInfo(seasonsLoadFailedTechnical)}
              </Text>
            )}
            <Button size="xs" variant="light" mt="sm" onClick={() => seasons.refetch()}>
              {sv.common.retryButton}
            </Button>
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
                {/* v0.6.0 audit-fix A5: SIMPLE-mode tables show just Namn (+Öppna) - render-filter
                    only, the underlying query is unchanged. */}
                {!isSimple && <Table.Th>{sv.common.status}</Table.Th>}
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {seasons.data.map((season) => (
                <Table.Tr key={season.id}>
                  <Table.Td>
                    <Anchor onClick={() => navigate(`/seasons/${season.id}`)}>{season.name}</Anchor>
                  </Table.Td>
                  {!isSimple && <Table.Td>{season.status}</Table.Td>}
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
                {!isSimple && <Table.Th>{sv.common.category}</Table.Th>}
                {!isSimple && <Table.Th>{sv.common.status}</Table.Th>}
                {isSimple && <Table.Th />}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {recentPlans.plans.map((plan) => (
                <Table.Tr key={plan.id}>
                  <Table.Td>
                    <Anchor onClick={() => navigate(`/plans/${plan.id}`)}>{plan.name}</Anchor>
                  </Table.Td>
                  {!isSimple && <Table.Td>{plan.category}</Table.Td>}
                  {!isSimple && <Table.Td>{plan.status}</Table.Td>}
                  {isSimple && (
                    <Table.Td>
                      <Button size="xs" variant="subtle" onClick={() => navigate(`/plans/${plan.id}`)}>
                        {sv.start.openButton}
                      </Button>
                    </Table.Td>
                  )}
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
