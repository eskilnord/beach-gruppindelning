import { useCallback, useState } from "react";
import { Anchor, Breadcrumbs, Button, Group, Stack, Stepper, Text, Title } from "@mantine/core";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { usePlan } from "../../api/plans";
import { useDeleteImportSession } from "../../api/import";
import { sv } from "../../i18n/sv";
import { DeleteConfirmModal } from "../../components/DeleteConfirmModal";
import { FileStep } from "./steps/FileStep";
import { SheetStep } from "./steps/SheetStep";
import { MappingStep } from "./steps/MappingStep";
import { ValidateStep } from "./steps/ValidateStep";
import { CommitStep } from "./steps/CommitStep";
import { SessionExpiredPanel } from "./SessionExpiredPanel";

const STEP_KEYS = ["file", "sheet", "map", "validate", "commit"] as const;
type StepKey = (typeof STEP_KEYS)[number];

function isStepKey(value: string | null): value is StepKey {
  return value !== null && (STEP_KEYS as readonly string[]).includes(value);
}

/**
 * The import wizard (spec §8.3/§19.3), reachable from Startvy's "Importera ny fil" (via
 * ImportEntryModal, which picks the season/plan first) and from a plan's Deltagare tab.
 *
 * Wizard state lives server-side on the ImportSession (backend/docs/m3-notes.md); the client only
 * keeps `session` + `step` — as URL search params, so a page reload survives (re-fetching
 * preview/columns/validate fresh from the backend on every step). See importSessionStorage.ts for
 * the one exception (the step-2 sheet list, which the backend has no endpoint to re-list).
 */
export function ImportWizardPage() {
  const { planId } = useParams<{ planId: string }>();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [cancelOpen, setCancelOpen] = useState(false);

  const plan = usePlan(planId);
  const sessionId = searchParams.get("session");
  const step: StepKey = isStepKey(searchParams.get("step")) ? (searchParams.get("step") as StepKey) : "file";
  const activeIndex = STEP_KEYS.indexOf(step);

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
    (newSessionId: string) => {
      setSearchParams({ session: newSessionId, step: "sheet" });
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

      <Stepper active={activeIndex} allowNextStepsSelect={false}>
        <Stepper.Step label={sv.importWizard.steps.file} />
        <Stepper.Step label={sv.importWizard.steps.sheet} />
        <Stepper.Step label={sv.importWizard.steps.map} />
        <Stepper.Step label={sv.importWizard.steps.validate} />
        <Stepper.Step label={sv.importWizard.steps.commit} />
      </Stepper>

      {step === "file" && <FileStep planId={planId} onUploaded={handleUploaded} />}
      {step !== "file" && !sessionId && <SessionExpiredPanel onRestart={handleRestart} />}
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
