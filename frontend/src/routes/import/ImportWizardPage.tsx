import { useCallback, useState } from "react";
import { Anchor, Breadcrumbs, Button, Group, Stack, Stepper, Text, Title } from "@mantine/core";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { usePlan } from "../../api/plans";
import { useDeleteImportSession, type ImportAnalysis } from "../../api/import";
import { sv } from "../../i18n/sv";
import { DeleteConfirmModal } from "../../components/DeleteConfirmModal";
import { FileStep } from "./steps/FileStep";
import { SheetStep } from "./steps/SheetStep";
import { MappingStep } from "./steps/MappingStep";
import { ValidateStep } from "./steps/ValidateStep";
import { CommitStep } from "./steps/CommitStep";
import { ReviewStep } from "./steps/ReviewStep";
import { SessionExpiredPanel } from "./SessionExpiredPanel";

const STEP_KEYS = ["file", "review", "sheet", "map", "validate", "commit"] as const;
type StepKey = (typeof STEP_KEYS)[number];

function isStepKey(value: string | null): value is StepKey {
  return value !== null && (STEP_KEYS as readonly string[]).includes(value);
}

/**
 * The import wizard (spec §8.3/§19.3), reachable from Startvy's "Importera ny fil" (via
 * ImportEntryModal, which picks the season/plan first) and from a plan's Deltagare tab.
 *
 * When upload auto-analysis is confident, the flow skips straight to a single "Granska och
 * importera" review step. Otherwise (or when the user clicks "Justera") the classic five-step
 * wizard runs with sheet/header/mapping already pre-filled on the session.
 *
 * Wizard state lives server-side on the ImportSession (backend/docs/m3-notes.md); the client only
 * keeps `session` + `step` — as URL search params, so a page reload survives (re-fetching
 * preview/columns/validate/analysis fresh from the backend on every step). See importSessionStorage.ts for
 * the one exception (the sheet list, which the backend has no endpoint to re-list).
 */
export function ImportWizardPage() {
  const { planId } = useParams<{ planId: string }>();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [cancelOpen, setCancelOpen] = useState(false);

  const plan = usePlan(planId);
  const sessionId = searchParams.get("session");
  const step: StepKey = isStepKey(searchParams.get("step")) ? (searchParams.get("step") as StepKey) : "file";
  // Only "review" is known to be on the one-click (2-step) path - readyToCommit isn't known yet
  // while still on "file" (before upload analysis resolves), so showing the 2-step stepper there
  // would visibly rewrite itself to 5 steps the instant a non-confident upload lands on "sheet".
  // Render the classic 5-step stepper on "file" and switch to the 2-step variant only once "review"
  // is actually reached.
  const showOneClickStepper = step === "review";

  const deleteSession = useDeleteImportSession(planId ?? "");

  const goToStep = useCallback(
    (next: StepKey) => {
      const params = new URLSearchParams(searchParams);
      params.set("step", next);
      setSearchParams(params);
    },
    [searchParams, setSearchParams],
  );

  const handleUploaded = useCallback(
    (newSessionId: string, analysis: ImportAnalysis) => {
      if (analysis.readyToCommit) {
        setSearchParams({ session: newSessionId, step: "review" });
      } else {
        setSearchParams({ session: newSessionId, step: "sheet" });
      }
    },
    [setSearchParams],
  );

  const handleRestart = useCallback(() => {
    setSearchParams({});
  }, [setSearchParams]);

  const handleCancelConfirmed = () => {
    if (sessionId) {
      deleteSession.mutate(sessionId, {
        onSettled: () => {
          setCancelOpen(false);
          navigate(`/plans/${planId}/deltagare`);
        },
      });
    } else {
      setCancelOpen(false);
      navigate(`/plans/${planId}/deltagare`);
    }
  };

  if (!planId) {
    return null;
  }

  const wizardStepIndex =
    step === "sheet" ? 1 : step === "map" ? 2 : step === "validate" ? 3 : step === "commit" ? 4 : 0;

  return (
    <Stack gap="lg" py="md">
      <Breadcrumbs>
        <Anchor onClick={() => navigate("/")}>{sv.nav.home}</Anchor>
        <Anchor onClick={() => navigate(`/plans/${planId}/deltagare`)}>{plan.data?.name ?? planId}</Anchor>
        <Text>{sv.importWizard.title}</Text>
      </Breadcrumbs>

      <Group justify="space-between">
        <Title order={2}>{sv.importWizard.title}</Title>
        <Button variant="default" color="red" onClick={() => setCancelOpen(true)}>
          {sv.importWizard.cancelButton}
        </Button>
      </Group>

      {showOneClickStepper ? (
        <Stepper active={step === "review" ? 1 : 0} allowNextStepsSelect={false}>
          <Stepper.Step label={sv.importWizard.steps.file} />
          <Stepper.Step label={sv.importWizard.steps.review} />
        </Stepper>
      ) : (
        <Stepper active={wizardStepIndex} allowNextStepsSelect={false}>
          <Stepper.Step label={sv.importWizard.steps.file} />
          <Stepper.Step label={sv.importWizard.steps.sheet} />
          <Stepper.Step label={sv.importWizard.steps.map} />
          <Stepper.Step label={sv.importWizard.steps.validate} />
          <Stepper.Step label={sv.importWizard.steps.commit} />
        </Stepper>
      )}

      {step === "file" && <FileStep planId={planId} onUploaded={handleUploaded} />}
      {step !== "file" && !sessionId && <SessionExpiredPanel onRestart={handleRestart} />}
      {step === "review" && sessionId && (
        <ReviewStep
          planId={planId}
          sessionId={sessionId}
          onAdjust={() => goToStep("sheet")}
          onExpired={handleRestart}
        />
      )}
      {step === "sheet" && sessionId && (
        <SheetStep planId={planId} sessionId={sessionId} onNext={() => goToStep("map")} onExpired={handleRestart} />
      )}
      {step === "map" && sessionId && (
        <MappingStep
          planId={planId}
          sessionId={sessionId}
          onNext={() => goToStep("validate")}
          onExpired={handleRestart}
        />
      )}
      {step === "validate" && sessionId && (
        <ValidateStep
          planId={planId}
          sessionId={sessionId}
          onNext={() => goToStep("commit")}
          onExpired={handleRestart}
        />
      )}
      {step === "commit" && sessionId && (
        <CommitStep planId={planId} sessionId={sessionId} onExpired={handleRestart} />
      )}

      <DeleteConfirmModal
        opened={cancelOpen}
        title={sv.importWizard.cancelConfirmTitle}
        message={sv.importWizard.cancelConfirmMessage}
        confirmLabel={sv.importWizard.cancelButton}
        loading={deleteSession.isPending}
        onClose={() => setCancelOpen(false)}
        onConfirm={handleCancelConfirmed}
      />
    </Stack>
  );
}
