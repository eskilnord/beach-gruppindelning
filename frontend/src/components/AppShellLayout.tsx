import { AppShell, Group, NavLink, Text, Title } from "@mantine/core";
import { Outlet, useParams } from "react-router-dom";
import { Link } from "react-router-dom";
import { useSeason } from "../api/seasons";
import { usePlan } from "../api/plans";
import { sv } from "../i18n/sv";
import { BackendStatusFooter } from "./BackendStatusFooter";
import { ReconnectOverlay } from "./ReconnectOverlay";

/**
 * App shell: sidebar navigation (Hem, aktiv säsong, aktiv plan) + footer backend-status indicator +
 * main content outlet. `useParams` here picks up seasonId/planId from whichever nested route is
 * currently active, since child route params merge upward to the whole matched branch.
 */
export function AppShellLayout() {
  const { seasonId, planId } = useParams<{ seasonId?: string; planId?: string }>();
  const { data: season } = useSeason(seasonId);
  const { data: plan } = usePlan(planId);

  return (
    <AppShell header={{ height: 56 }} navbar={{ width: 240, breakpoint: "sm" }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md">
          <Title order={4}>{sv.app.title}</Title>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="md">
        <NavLink component={Link} to="/" label={sv.nav.home} />
        {season && (
          <NavLink
            component={Link}
            to={`/seasons/${season.id}`}
            label={season.name}
            description={sv.nav.season}
            active={!planId}
          />
        )}
        {plan && season && (
          <NavLink
            component={Link}
            to={`/plans/${plan.id}`}
            label={plan.name}
            description={sv.nav.plan}
            active
          />
        )}
        <AppShell.Section grow />
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
    </AppShell>
  );
}
