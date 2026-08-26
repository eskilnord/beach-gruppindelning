import { Button, Group, Paper } from "@mantine/core";
import { useLocation, useNavigate } from "react-router-dom";
import { sv } from "../../i18n/sv";
import { resolveSimpleStepIndex, SIMPLE_STEPS } from "./planSimpleSteps";

interface PlanSimpleStepFooterProps {
  planId: string;
}

/**
 * v0.6.0 F2 (M-S2): sticky footer nav shown alongside PlanSimpleStepper in SIMPLE mode - "Tillbaka"/
 * "Nästa: <label> →" buttons driven by the same SIMPLE_STEPS list the stepper uses, so the two never
 * disagree about step order. Renders nothing on a non-step route (resolveSimpleStepIndex -1) - e.g.
 * a gated tab (falt/tranare/kapacitet/planer) opened via deep link while in SIMPLE mode, where
 * "Tillbaka"/"Nästa" wouldn't have a sensible target anyway.
 *
 * v0.6.0 audit-fix A1 (walkthrough-proven: this footer used to overlay and swallow clicks on the
 * last content row of every step): PlanLayout.tsx now reserves this footer's own height as bottom
 * padding on the Outlet wrapper whenever it renders (mirroring this component's own null-render
 * condition exactly), so the footer can never sit on top of interactive content again. "Nästa" is
 * also demoted from the teal filled primary button to `variant="default"` (matching "Tillbaka") -
 * each STEP's own primary action (e.g. "Importera", "Skapa grupper") should be the visually dominant
 * button on screen, not this cross-cutting nav chrome.
 */
export function PlanSimpleStepFooter({ planId }: PlanSimpleStepFooterProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const activeIndex = resolveSimpleStepIndex(location.pathname);

  if (activeIndex === -1) {
    return null;
  }

  const previousStep = activeIndex > 0 ? SIMPLE_STEPS[activeIndex - 1] : undefined;
  const nextStep = activeIndex < SIMPLE_STEPS.length - 1 ? SIMPLE_STEPS[activeIndex + 1] : undefined;

  return (
    <Paper withBorder p="sm" pos="sticky" bottom={0} style={{ zIndex: 1 }}>
      <Group justify="space-between">
        {previousStep ? (
          <Button
            variant="default"
            onClick={() => navigate(`/plans/${planId}/${previousStep.path}`)}
            data-testid="simple-step-back"
          >
            {sv.simple.nav.back}
          </Button>
        ) : (
          <span />
        )}
        {nextStep && (
          <Button
            variant="default"
            onClick={() => navigate(`/plans/${planId}/${nextStep.path}`)}
            data-testid="simple-step-next"
          >
            {sv.simple.nav.next(sv.simple.steps[nextStep.labelKey])}
          </Button>
        )}
      </Group>
    </Paper>
  );
}
