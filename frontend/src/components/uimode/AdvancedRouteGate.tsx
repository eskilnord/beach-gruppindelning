import type { ReactNode } from "react";
import { Button, Card, Group, Stack, Title } from "@mantine/core";
import { IconAdjustmentsHorizontal } from "@tabler/icons-react";
import { useNavigate, useParams } from "react-router-dom";
import { EmptyState } from "../EmptyState";
import { useUiMode } from "../../lib/uiMode/useUiMode";
import { sv } from "../../i18n/sv";
import { useConfirmedAdvancedMode } from "./useConfirmedAdvancedMode";

interface AdvancedRouteGateProps {
  children: ReactNode;
  /** Swedish tab label interpolated into sv.uiMode.routeGate.body(tabLabel) - e.g. sv.plan.tabs.fields. */
  tabLabel: string;
}

/**
 * Wraps one of the four advanced-only plan routes (falt/tranare/kapacitet/planer, see router.tsx).
 * In ADVANCED mode, renders `children` unchanged - the route is otherwise untouched, still fully
 * mounted/registered. In SIMPLE mode, renders a calm card (styled via the shared EmptyState.tsx,
 * v0.3.0 WI-6) instead, with two actions: "Öppna avancerat läge" (v0.6.0 audit-fix A6: now routed
 * through the same confirm as UiModeSwitch/UiModeIntroBanner, via useConfirmedAdvancedMode, instead
 * of flipping the mode directly) and "Tillbaka till mina steg" (a default-weight escape hatch back
 * to the plan's SIMPLE-mode step flow - a gated tab used to be a one-way street to either "confirm
 * advanced mode" or the browser back button).
 */
export function AdvancedRouteGate({ children, tabLabel }: AdvancedRouteGateProps) {
  const { isAdvanced } = useUiMode();
  const { requestAdvancedMode, confirmModal } = useConfirmedAdvancedMode();
  const navigate = useNavigate();
  const { planId } = useParams<{ planId: string }>();

  // Deliberate: `children` is only ever rendered under the `isAdvanced` branch below, never kept
  // mounted-but-hidden behind the gate card. Switching modes therefore fully unmounts/remounts the
  // wrapped panel (losing any of its own local component state) rather than toggling a CSS
  // visibility - simpler and cheaper than keeping four advanced-only panels alive at all times for a
  // gate most sessions never cross.
  if (isAdvanced) {
    return <>{children}</>;
  }

  return (
    <>
      <Card withBorder radius="md" p="xl" data-testid="ui-mode-route-gate">
        <Stack align="center" gap={4}>
          <Title order={4} ta="center">
            {sv.uiMode.routeGate.title}
          </Title>
          <EmptyState
            icon={<IconAdjustmentsHorizontal size={22} stroke={1.5} />}
            message={sv.uiMode.routeGate.body(tabLabel)}
            action={
              <Group gap="sm">
                <Button onClick={() => requestAdvancedMode()} data-testid="ui-mode-gate-open-advanced">
                  {sv.uiMode.routeGate.openAdvancedButton}
                </Button>
                {planId && (
                  // Restores the SIMPLE-mode rail (PlanSimpleStepper/PlanSimpleStepFooter, which
                  // both render only on the six SIMPLE_STEPS routes - see planSimpleSteps.ts) by
                  // navigating to a real step route, "deltagare".
                  <Button
                    variant="default"
                    onClick={() => navigate(`/plans/${planId}/deltagare`)}
                    data-testid="ui-mode-gate-back-to-steps"
                  >
                    {sv.uiMode.routeGate.backToStepsButton}
                  </Button>
                )}
              </Group>
            }
          />
        </Stack>
      </Card>
      {confirmModal}
    </>
  );
}
