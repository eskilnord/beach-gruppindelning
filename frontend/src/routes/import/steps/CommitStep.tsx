import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Alert, Button, Group, Stack, Text, TextInput, Title } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { useCommitImport, useImportValidation, type ImportCommitResult } from "../../../api/import";
import { ApiError, isNotFoundError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { SessionExpiredPanel } from "../SessionExpiredPanel";

interface CommitStepProps {
  planId: string;
  sessionId: string;
  onExpired: () => void;
}

/** Wizard step 5 (spec §8.3 step 8): summary of rows to import/skip, optional "Spara mappning som
 *  mall" name, commit button, then a result screen with counts + a link to the Deltagare tab. The
 *  backend deletes the session as part of a successful commit (ImportController#commit), so this
 *  step's own local `result` state — not the session — drives the result view afterwards. */
export function CommitStep({ planId, sessionId, onExpired }: CommitStepProps) {
  const navigate = useNavigate();
  const validation = useImportValidation(planId, sessionId);
  const commit = useCommitImport(planId, sessionId);
  const [templateName, setTemplateName] = useState("");
  const [result, setResult] = useState<ImportCommitResult | null>(null);

  if (validation.isError && isNotFoundError(validation.error)) {
    return <SessionExpiredPanel onRestart={onExpired} />;
  }

  const totalRows = validation.data?.totalRows ?? 0;
  const skipCount = validation.data?.skipCount ?? 0;
  const importCount = totalRows - skipCount;

  const handleCommit = async () => {
    try {
      const commitResult = await commit.mutateAsync({
        saveAsTemplate: templateName.trim().length > 0,
        templateName: templateName.trim().length > 0 ? templateName.trim() : undefined,
      });
      setResult(commitResult);
    } catch (error) {
      if (isNotFoundError(error)) {
        onExpired();
        return;
      }
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: error instanceof ApiError ? error.message : sv.importWizard.commit.commitFailed,
      });
    }
  };

  if (result) {
    return (
      <Stack gap="md">
        <Title order={4}>{sv.importWizard.commit.resultHeading}</Title>
        <Text>{sv.importWizard.commit.resultSummary(result.imported, result.skipped)}</Text>
        {result.warnings.length > 0 && (
          <Alert color="yellow" title={sv.importWizard.commit.warningsHeading}>
            <Stack gap={2}>
              {result.warnings.map((warning, index) => (
                <Text size="sm" key={index}>
                  {warning}
                </Text>
              ))}
            </Stack>
          </Alert>
        )}
        <Group>
          <Button onClick={() => navigate(`/plans/${planId}/deltagare`)}>
            {sv.importWizard.commit.goToParticipants}
          </Button>
        </Group>
      </Stack>
    );
  }

  return (
    <Stack gap="md">
      <Title order={4}>{sv.importWizard.commit.heading}</Title>
      <Text>{sv.importWizard.commit.summary(importCount, skipCount)}</Text>
      <TextInput
        label={sv.importWizard.commit.templateNameLabel}
        placeholder={sv.importWizard.commit.templateNamePlaceholder}
        value={templateName}
        onChange={(event) => setTemplateName(event.currentTarget.value)}
        w={360}
      />
      <Group justify="flex-end">
        <Button onClick={handleCommit} loading={commit.isPending}>
          {commit.isPending ? sv.importWizard.commit.committing : sv.importWizard.commit.submit}
        </Button>
      </Group>
    </Stack>
  );
}
