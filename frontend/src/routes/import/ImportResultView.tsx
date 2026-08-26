import { useNavigate } from "react-router-dom";
import { Alert, Button, Group, Stack, Text, Title } from "@mantine/core";
import type { ImportCommitResult } from "../../api/import";
import { sv } from "../../i18n/sv";

interface ImportResultViewProps {
  planId: string;
  result: ImportCommitResult;
}

/**
 * The commit-result screen shared by both the one-click flow (ReviewStep.tsx) and the classic
 * wizard's last step (CommitStep.tsx): heading, imported/skipped summary, any warnings, and a link
 * to the Deltagare tab. Both steps drive their own local `result` state (the backend deletes the
 * ImportSession as part of a successful commit) and only render this once `result` is set.
 */
export function ImportResultView({ planId, result }: ImportResultViewProps) {
  const navigate = useNavigate();

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
