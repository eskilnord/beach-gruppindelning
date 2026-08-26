import { Card, Text } from "@mantine/core";
import { sv } from "../../../i18n/sv";

/**
 * v0.6.0 F2 (M-S2): placeholder for the "Prioriteringar" simple-mode step (planSimpleSteps.ts's
 * third step). Registered in router.tsx WITHOUT <AdvancedRouteGate> - unlike falt/tranare/kapacitet/
 * planer, this is a simple-mode-FIRST screen (it doesn't exist as a plan tab today at all), so
 * there's no ADVANCED-only content to gate. F3 replaces this calm placeholder card with the real
 * panel.
 */
export function PrioritiesPanel() {
  return (
    <Card withBorder padding="xl" data-testid="priorities-panel-placeholder">
      <Text c="dimmed">{sv.simple.priorities.placeholder}</Text>
    </Card>
  );
}
