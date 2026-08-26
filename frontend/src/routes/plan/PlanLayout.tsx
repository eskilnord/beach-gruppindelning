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
import { useParticipants } from "../../api/participants";
import { useGroups } from "../../api/groups";
import { useSavedPlans } from "../../api/savedPlans";
import { userErrorText, technicalErrorDetail } from "../../lib/errorText";
import { pluralize } from "../../lib/pluralizeSv";
import { sv } from "../../i18n/sv";
import { AdvancedOnly, SimpleOnly } from "../../components/uimode/AdvancedOnly";
import { useIsSimpleMode } from "../../lib/uiMode/useUiMode";
import { EditPlanModal } from "./EditPlanModal";
import { useEditPlanModalStore } from "./editPlanModalStore";
import { DeleteConfirmModal } from "../../components/DeleteConfirmModal";
import { PlanSimpleStepper } from "./PlanSimpleStepper";
import { PlanSimpleStepFooter } from "./PlanSimpleStepFooter";
import { resolveSimpleStepIndex } from "./planSimpleSteps";

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

/** v0.6.0 audit-fix A1: the sticky footer's own rendered height (PlanSimpleStepFooter.tsx: `p="sm"`
 *  padding around one row of default-sized Buttons) - reserved as bottom padding on the content
 *  wrapper whenever the footer is showing, so it can never sit on top of (and swallow clicks on) the
 *  last row of a step's own content. A little taller than the footer's actual ~60px to leave a
 *  visible gap, not just a flush edge. */
const SIMPLE_FOOTER_RESERVED_HEIGHT = 84;

/**
 * Joins Swedish list items with "och" before the last one ("A, B och C") - used by the delete-plan
 * confirm's detailsSv below (v0.6.0 audit-fix A11).
 */
function joinSvList(parts: string[]): string {
  if (parts.length === 0) {
    return "";
  }
  if (parts.length === 1) {
    return parts[0];
  }
  return `${parts.slice(0, -1).join(", ")} och ${parts[parts.length - 1]}`;
}

export function PlanLayout() {
  const { planId } = useParams<{ planId: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const plan = usePlan(planId);
  const season = useSeason(plan.data?.seasonPlanId);
  const deletePlan = useDeletePlan(plan.data?.seasonPlanId ?? "");
  const isSimple = useIsSimpleMode();

  // v0.6.0 audit-fix A11: reuses whichever of these three counts are already warm in the query
  // cache from ParticipantsPanel/results/SavedPlansPanel (same query keys) - cheap even when cold,
  // and drives the delete-plan confirm's "N deltagare, M grupper och K sparade versioner tas bort."
  // line below. Hooks must run unconditionally before the loading/error early-returns further down.
  const participants = useParticipants(plan.data?.id);
  const groups = useGroups(plan.data?.id);
  const savedPlans = useSavedPlans(plan.data?.id);

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
    const technical = plan.isError ? technicalErrorDetail(plan.error) : undefined;
    return (
      <Alert color="red">
        <Text size="sm">{userErrorText(plan.error, sv.plan.notFound)}</Text>
        {technical && (
          <Text size="xs" c="dimmed" mt={4}>
            {sv.common.technicalInfo(technical)}
          </Text>
        )}
      </Alert>
    );
  }

  const data = plan.data;
  const activeTab = TABS.find((tab) => location.pathname.endsWith(`/${tab.path}`))?.path ?? TABS[0].path;
  // v0.6.0 audit-fix A1: mirrors PlanSimpleStepFooter's own null-render condition exactly, so the
  // content wrapper's reserved bottom padding and the footer's actual presence never disagree.
  const showFooter = isSimple && resolveSimpleStepIndex(location.pathname) !== -1;

  const deleteDetailParts: string[] = [];
  if (participants.data) {
    deleteDetailParts.push(pluralize(participants.data.length, "deltagare", "deltagare"));
  }
  if (groups.data) {
    deleteDetailParts.push(pluralize(groups.data.length, "grupp", "grupper"));
  }
  if (savedPlans.data) {
    deleteDetailParts.push(pluralize(savedPlans.data.length, "sparad version", "sparade versioner"));
  }
  const deleteDetailsSv =
    deleteDetailParts.length > 0 ? sv.deletePlanModal.detailsSuffix(joinSvList(deleteDetailParts)) : undefined;

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

      {/* v0.6.0 audit-fix A1: reserves the sticky footer's own height as bottom padding whenever it
          renders, so it can never overlay (and swallow clicks on) the last row of a step's content -
          see SIMPLE_FOOTER_RESERVED_HEIGHT's doc comment. */}
      <div style={showFooter ? { paddingBottom: SIMPLE_FOOTER_RESERVED_HEIGHT } : undefined}>
        <Outlet />
      </div>

      {showFooter && <PlanSimpleStepFooter planId={data.id} />}

      <EditPlanModal opened={editOpen} plan={data} onClose={() => useEditPlanModalStore.getState().close()} />

      <DeleteConfirmModal
        opened={deleteOpen}
        title={sv.deletePlanModal.title}
        message={sv.deletePlanModal.message(data.name)}
        detailsSv={deleteDetailsSv}
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
