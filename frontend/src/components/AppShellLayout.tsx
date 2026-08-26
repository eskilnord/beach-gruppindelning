import { ActionIcon, AppShell, Badge, Group, NavLink, Text, Title, Tooltip } from "@mantine/core";
import { IconCalendarWeek, IconClipboardList, IconHome2 } from "@tabler/icons-react";
import { Outlet, useParams } from "react-router-dom";
import { Link } from "react-router-dom";
import { useSeason } from "../api/seasons";
import { usePlan } from "../api/plans";
import { sv } from "../i18n/sv";
import { useUiMode } from "../lib/uiMode/useUiMode";
import { BackendStatusFooter } from "./BackendStatusFooter";
import { BrandMark } from "./BrandMark";
import { ReconnectOverlay } from "./ReconnectOverlay";
import { PlayerSearchSpotlight } from "./playersearch/PlayerSearchSpotlight";
import { TutorialModal } from "./tutorial/TutorialModal";
import { useTutorialStore } from "./tutorial/tutorialStore";
import { UiModeSwitch } from "./uimode/UiModeSwitch";
import { UiModeSync } from "./uimode/UiModeSync";

/** Shared icon sizing for every navbar NavLink (v0.3.0 WI-6) - kept as a constant so Hem/Säsong/Plan
 *  visually line up regardless of which entries are present. */
const NAV_ICON_PROPS = { size: 18, stroke: 1.75 } as const;

/**
 * App shell: sidebar navigation (Hem, aktiv säsong, aktiv plan) + footer backend-status indicator +
 * main content outlet. `useParams` here picks up seasonId/planId from whichever nested route is
 * currently active, since child route params merge upward to the whole matched branch. Also hosts
 * the two app-wide singletons that need to live above every route: the "?" kom-igång-guiden
 * affordance + its <TutorialModal> (feature: available everywhere, not just on Startvy), and the
 * Ctrl/Cmd+F <PlayerSearchSpotlight>, mounted only while a plan is active (planId present).
 */
export function AppShellLayout() {
  const { seasonId, planId } = useParams<{ seasonId?: string; planId?: string }>();
  const { data: season } = useSeason(seasonId);
  const { data: plan } = usePlan(planId);
  const tutorialOpened = useTutorialStore((state) => state.opened);
  const openTutorial = useTutorialStore((state) => state.open);
  const closeTutorial = useTutorialStore((state) => state.close);
  const { isAdvanced, setMode } = useUiMode();

  return (
    <AppShell header={{ height: 56 }} navbar={{ width: 240, breakpoint: "sm" }} padding="md">
      <AppShell.Header
        style={{ backgroundColor: "var(--mantine-color-white)", borderBottomColor: "var(--mantine-color-gray-3)" }}
      >
        <Group h="100%" px="md" justify="space-between">
          <Group gap="xs">
            <BrandMark />
            <Title order={4}>{sv.app.title}</Title>
          </Group>
          <Group gap="xs">
            {isAdvanced && (
              // v0.6.0 audit-fix A7: variant="outline" (not the pill-like "light") so this reads as
              // a clickable button, not a passive status label - text now names the action it
              // performs ("Till enkelt läge"), matching. aria-label unchanged.
              <Tooltip label={sv.uiMode.backToSimpleTooltip}>
                <Badge
                  component="button"
                  type="button"
                  size="sm"
                  variant="outline"
                  color="gray"
                  style={{ cursor: "pointer" }}
                  onClick={() => setMode("SIMPLE")}
                  aria-label={sv.uiMode.backToSimpleTooltip}
                  data-testid="ui-mode-advanced-badge"
                >
                  {sv.uiMode.advancedBadge}
                </Badge>
              </Tooltip>
            )}
            <Tooltip label={sv.tutorial.headerButtonTooltip}>
              <ActionIcon
                variant="default"
                radius="xl"
                size="lg"
                aria-label={sv.tutorial.headerButtonTooltip}
                onClick={openTutorial}
                data-testid="tutorial-open-button"
              >
                ?
              </ActionIcon>
            </Tooltip>
          </Group>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar
        p="md"
        style={{ backgroundColor: "var(--mantine-color-gray-0)", borderRightColor: "var(--mantine-color-gray-3)" }}
      >
        <NavLink component={Link} to="/" label={sv.nav.home} leftSection={<IconHome2 {...NAV_ICON_PROPS} />} />
        {season && (
          <NavLink
            component={Link}
            to={`/seasons/${season.id}`}
            label={season.name}
            description={sv.nav.season}
            active={!planId}
            leftSection={<IconCalendarWeek {...NAV_ICON_PROPS} />}
          />
        )}
        {plan && season && (
          <NavLink
            component={Link}
            to={`/plans/${plan.id}`}
            label={plan.name}
            description={sv.nav.plan}
            active
            leftSection={<IconClipboardList {...NAV_ICON_PROPS} />}
          />
        )}
        <AppShell.Section grow />
        <AppShell.Section>
          <UiModeSwitch />
        </AppShell.Section>
      </AppShell.Navbar>

      <AppShell.Main style={{ display: "flex", flexDirection: "column", minHeight: "100vh" }}>
        <div style={{ flex: 1 }}>
          <Outlet />
        </div>
        <Text component="div" style={{ borderTop: "1px solid var(--mantine-color-gray-3)" }}>
          <BackendStatusFooter />
        </Text>
      </AppShell.Main>

      <ReconnectOverlay />

      <UiModeSync />
      <TutorialModal opened={tutorialOpened} planId={planId} onClose={closeTutorial} />
      {planId && <PlayerSearchSpotlight planId={planId} />}
    </AppShell>
  );
}
