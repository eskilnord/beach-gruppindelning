import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Button, Group, List, Modal, Stepper, Text, Title, Tooltip } from "@mantine/core";
import { sv } from "../../i18n/sv";
import { useIsSimpleMode } from "../../lib/uiMode/useUiMode";
import { resolveTutorialTargetPath, TUTORIAL_STEP_CONFIG, TUTORIAL_STEP_CONFIG_SIMPLE } from "./tutorialSteps";

interface TutorialModalProps {
  opened: boolean;
  /** The currently active plan, if any (AppShellLayout's own useParams) - steps whose "Ta mig dit"
   *  target lives inside a plan are disabled until one exists. */
  planId: string | undefined;
  onClose: () => void;
}

/**
 * Kom-igång-guiden (spec: "en snygg och välinformerande och pedagogisk tutorial"). Hand-rolled with
 * Mantine components only (Modal + Stepper) - no new heavyweight dependency, per the brief. One step
 * per stage of the real product flow (season -> import -> structured fields -> resources -> coaches
 * -> capacity -> optimize -> results -> lock/re-run -> save/export); copy lives in `sv.tutorial.steps`
 * (CLAUDE.md "Language" - Swedish UI copy stays in sv.ts), icon + navigation target live in the
 * parallel `TUTORIAL_STEP_CONFIG` array in tutorialSteps.ts.
 *
 * Each step's "Ta mig dit" button closes the modal and navigates straight to the tab it describes;
 * disabled with a tooltip when no plan is active yet (every step except the first one needs one).
 *
 * v0.6.0 F6 (M-S6): SIMPLE mode gets its own 6-step walkthrough (`sv.tutorial.simpleSteps` +
 * tutorialSteps.ts's `TUTORIAL_STEP_CONFIG_SIMPLE`, mirroring PlanSimpleStepper.tsx's IA) instead
 * of the 10-step ADVANCED one - this is the app-wide singleton mount (AppShellLayout.tsx), so the
 * "?" header button always offers the walkthrough matching whatever mode is currently active.
 */
export function TutorialModal({ opened, planId, onClose }: TutorialModalProps) {
  const [rawActive, setActive] = useState(0);
  const navigate = useNavigate();
  const isSimple = useIsSimpleMode();
  const steps = isSimple ? sv.tutorial.simpleSteps : sv.tutorial.steps;
  const stepConfig = isSimple ? TUTORIAL_STEP_CONFIG_SIMPLE : TUTORIAL_STEP_CONFIG;
  const totalSteps = steps.length;
  // v0.6.0 F6 review fix (FIX 6, MINOR): `steps`/`stepConfig` change LENGTH when `isSimple` flips
  // (10 ADVANCED steps vs. 6 SIMPLE ones) while this modal can stay open across that flip (the
  // header's mode switch/badge are reachable independently of this modal). Without clamping, an
  // `active` index left over from the longer ADVANCED list (e.g. 8) would index past the end of the
  // 6-entry SIMPLE arrays below and crash the render (`step.title` on `undefined`).
  const active = Math.min(rawActive, totalSteps - 1);

  const handleClose = () => {
    onClose();
    setActive(0);
  };

  const goThere = (targetPath: string) => {
    handleClose();
    navigate(targetPath);
  };

  return (
    <Modal
      opened={opened}
      onClose={handleClose}
      title={sv.tutorial.modalTitle}
      size="xl"
      centered
      data-testid="tutorial-modal"
    >
      <Stepper active={active} onStepClick={setActive} size="sm">
        {steps.map((step, index) => {
          const config = stepConfig[index];
          const targetPath = resolveTutorialTargetPath(config.target, planId);
          return (
            <Stepper.Step key={step.title} label={step.title}>
              <Group gap="md" align="flex-start" mt="md" wrap="nowrap">
                <Text fz={36} lh={1} aria-hidden>
                  {config.icon}
                </Text>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <Title order={4} mb={4} data-testid="tutorial-active-step-title">
                    {step.title}
                  </Title>
                  <Text size="sm" mb="sm">
                    {step.body}
                  </Text>
                  <Text size="sm" fw={600} mb={4}>
                    {sv.tutorial.howToHeading}
                  </Text>
                  <List size="sm" spacing={4} mb="md">
                    {step.bullets.map((bullet) => (
                      <List.Item key={bullet}>{bullet}</List.Item>
                    ))}
                  </List>
                  <Tooltip label={sv.tutorial.goThereDisabledTooltip} disabled={targetPath !== null}>
                    <Button
                      variant="light"
                      disabled={targetPath === null}
                      onClick={() => targetPath && goThere(targetPath)}
                      data-testid="tutorial-go-there"
                    >
                      {sv.tutorial.goThereButton}
                    </Button>
                  </Tooltip>
                </div>
              </Group>
            </Stepper.Step>
          );
        })}
      </Stepper>

      <Group justify="space-between" mt="lg">
        <Text size="xs" c="dimmed">
          {sv.tutorial.stepLabel(active + 1, totalSteps)}
        </Text>
        <Group>
          <Button variant="default" disabled={active === 0} onClick={() => setActive((s) => Math.max(0, s - 1))}>
            {sv.tutorial.prevButton}
          </Button>
          {active < totalSteps - 1 ? (
            <Button onClick={() => setActive((s) => Math.min(totalSteps - 1, s + 1))}>{sv.tutorial.nextButton}</Button>
          ) : (
            <Button onClick={handleClose}>{sv.tutorial.doneButton}</Button>
          )}
        </Group>
      </Group>
    </Modal>
  );
}
