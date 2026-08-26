import { useEffect, useState } from "react";
import { Outlet, useLocation, useNavigate, useParams } from "react-router-dom";
import {
  ActionIcon,
  Alert,
  Anchor,
  Badge,
  Box,
  Breadcrumbs,
  Button,
  Group,
  Loader,
  Menu,
  Stack,
  Tabs,
  Text,
  Title,
  Tooltip,
} from "@mantine/core";
import { spotlight } from "@mantine/spotlight";
import { IconDots } from "@tabler/icons-react";
import { useDeletePlan, usePlan } from "../../api/plans";
import { useSeason } from "../../api/seasons";
import { ApiError } from "../../api/client";
import { sv } from "../../i18n/sv";
import { AdvancedOnly, SimpleOnly } from "../../components/uimode/AdvancedOnly";
import { useIsSimpleMode } from "../../lib/uiMode/useUiMode";
import { EditPlanModal } from "./EditPlanModal";
import { useEditPlanModalStore } from "./editPlanModalStore";
import { DeleteConfirmModal } from "../../components/DeleteConfirmModal";
import { PlanSimpleStepper } from "./PlanSimpleStepper";
import { PlanSimpleStepFooter } from "./PlanSimpleStepFooter";

const TABS = [
  { path: "deltagare", label: sv.plan.tabs.participants },
  { path: "falt", label: sv.plan.tabs.fields },
  { path: "resurser", label: sv.plan.tabs.resources },
  { path: "tranare", label: sv.plan.tabs.coaches },
  { path: "kapacitet", label: sv.plan.tabs.capacity },
  { path: "optimering", label: sv.plan.tabs.optimize },
  { path: "resultat", label: sv.plan.tabs.results },
  { path: "planer", label: sv.plan.tabs.savedPlans },
  { path: "export", label: sv.plan.tabs.export },
] as const;

export function PlanLayout() {
  const { planId } = useParams<{ planId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const plan = usePlan(planId);
  const season = useSeason(plan.data?.seasonPlanId);
  const deletePlan = useDeletePlan(plan.data?.seasonPlanId ?? "");
  const isSimple = useIsSimpleMode();

  const editOpen = useEditPlanModalStore((state) => state.opened);
  const [deleteOpen, setDeleteOpen] = useState(false);

  // v0.3.0 review fix: the modal-open state lives in a global zustand store (so OptimizePanel's
  // "Ändra…" link can open it) - reset it whenever this layout unmounts or switches to another
  // plan, so an open modal never leaks opened=true into the next plan's layout.
  useEffect(() => () => useEditPlanModalStore.getState().close(), [planId]);

  if (plan.isLoading) {
    return <Loader />;
  }

  if (plan.isError || !plan.data) {
    return (
      <Alert color="red">{plan.error instanceof ApiError ? plan.error.message : sv.plan.notFound}</Alert>
    );
  }

  const data = plan.data;
  const activeTab = TABS.find((tab) => location.pathname.endsWith(`/${tab.path}`))?.path ?? TABS[0].path;

  return (
    <Stack gap="lg" py="md">
      <Breadcrumbs>
        <Anchor onClick={() => navigate("/")}>{sv.nav.home}</Anchor>
        {season.data && (
          <Anchor onClick={() => navigate(`/seasons/${season.data.id}`)}>{season.data.name}</Anchor>
        )}
        <Text>{data.name}</Text>
      </Breadcrumbs>

      <Group justify="space-between">
        <Box>
          <Group gap="sm">
            <Title order={2}>{data.name}</Title>
            {/* v0.6.0 F2 (M-S2): raw status badge is ADVANCED-only - AdvancedOnly renders `children`
                unchanged (a Fragment, no extra DOM node) in ADVANCED, so this stays pixel-identical. */}
            <AdvancedOnly>
              <Badge>{data.status}</Badge>
            </AdvancedOnly>
          </Group>
          {data.category && (
            <Text c="dimmed" size="sm">
              {data.category}
            </Text>
          )}
        </Box>
        <Group>
          <Tooltip label={sv.playerSearch.actionIconTooltip}>
            <ActionIcon
              variant="default"
              size="lg"
              aria-label={sv.playerSearch.actionIconTooltip}
              onClick={() => spotlight.open()}
              data-testid="player-search-open-button"
            >
              🔍
            </ActionIcon>
          </Tooltip>
          {/* v0.6.0 F2 (M-S2): ADVANCED keeps the two separate buttons unchanged (AdvancedOnly ==
              Fragment, no extra DOM); SIMPLE collapses them into one Menu behind an IconDots
              ActionIcon (sv.plan.menu). */}
          <AdvancedOnly>
            <Button variant="default" onClick={() => useEditPlanModalStore.getState().open()}>
              {sv.plan.editButton}
            </Button>
            <Button variant="default" color="red" onClick={() => setDeleteOpen(true)}>
              {sv.plan.deleteButton}
            </Button>
          </AdvancedOnly>
          <SimpleOnly>
            <Menu withinPortal position="bottom-end">
              <Menu.Target>
                <ActionIcon
                  variant="default"
                  size="lg"
                  aria-label={sv.plan.menu.ariaLabel}
                  data-testid="plan-header-menu-button"
                >
                  <IconDots size={18} />
                </ActionIcon>
              </Menu.Target>
              <Menu.Dropdown>
                <Menu.Item onClick={() => useEditPlanModalStore.getState().open()}>
                  {sv.plan.menu.edit}
                </Menu.Item>
                <Menu.Item color="red" onClick={() => setDeleteOpen(true)}>
                  {sv.plan.menu.delete}
                </Menu.Item>
              </Menu.Dropdown>
            </Menu>
          </SimpleOnly>
        </Group>
      </Group>

      {isSimple ? (
        <PlanSimpleStepper planId={data.id} />
      ) : (
        <Tabs
          value={activeTab}
          onChange={(value) => {
            if (value) {
              navigate(`/plans/${data.id}/${value}`);
            }
          }}
        >
          <Tabs.List>
            {TABS.map((tab) => (
              <Tabs.Tab key={tab.path} value={tab.path}>
                {tab.label}
              </Tabs.Tab>
            ))}
          </Tabs.List>
        </Tabs>
      )}

      <Outlet />

      {isSimple && <PlanSimpleStepFooter planId={data.id} />}

      <EditPlanModal opened={editOpen} plan={data} onClose={() => useEditPlanModalStore.getState().close()} />

      <DeleteConfirmModal
        opened={deleteOpen}
        title={sv.deletePlanModal.title}
        message={sv.deletePlanModal.message(data.name)}
        confirmLabel={sv.deletePlanModal.confirm}
        loading={deletePlan.isPending}
        onClose={() => setDeleteOpen(false)}
        onConfirm={() => {
          deletePlan.mutate(data.id, {
            onSuccess: () => navigate(`/seasons/${data.seasonPlanId}`),
          });
        }}
      />
    </Stack>
  );
}
