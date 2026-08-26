import type { ReactNode } from "react";
import { Button, Card, Stack, Title } from "@mantine/core";
import { IconAdjustmentsHorizontal } from "@tabler/icons-react";
import { EmptyState } from "../EmptyState";
import { useUiMode } from "../../lib/uiMode/useUiMode";
import { sv } from "../../i18n/sv";

interface AdvancedRouteGateProps {
  children: ReactNode;
  /** Swedish tab label interpolated into sv.uiMode.routeGate.body(tabLabel) - e.g. sv.plan.tabs.fields. */
  tabLabel: string;
}

/**
 * Wraps one of the four advanced-only plan routes (falt/tranare/kapacitet/planer, see router.tsx).
 * In ADVANCED mode, renders `children` unchanged - the route is otherwise untouched, still fully
 * mounted/registered. In SIMPLE mode, renders a calm card (styled via the shared EmptyState.tsx,
 * v0.3.0 WI-6) instead, with a single button that flips the global mode to ADVANCED and stays on
 * the current route (no navigation) so the now-visible panel appears in place.
 */
export function AdvancedRouteGate({ children, tabLabel }: AdvancedRouteGateProps) {
  const { isAdvanced, setMode } = useUiMode();

  // Deliberate: `children` is only ever rendered under the `isAdvanced` branch below, never kept
  // mounted-but-hidden behind the gate card. Switching modes therefore fully unmounts/remounts the
  // wrapped panel (losing any of its own local component state) rather than toggling a CSS
  // visibility - simpler and cheaper than keeping four advanced-only panels alive at all times for a
  // gate most sessions never cross.
  if (isAdvanced) {
    return <>{children}</>;
  }

  return (
    <Card withBorder radius="md" p="xl" data-testid="ui-mode-route-gate">
      <Stack align="center" gap={4}>
        <Title order={4} ta="center">
          {sv.uiMode.routeGate.title}
        </Title>
        <EmptyState
          icon={<IconAdjustmentsHorizontal size={22} stroke={1.5} />}
          message={sv.uiMode.routeGate.body(tabLabel)}
          action={
            <Button onClick={() => setMode("ADVANCED")} data-testid="ui-mode-gate-open-advanced">
              {sv.uiMode.routeGate.openAdvancedButton}
            </Button>
          }
        />
      </Stack>
    </Card>
  );
}
