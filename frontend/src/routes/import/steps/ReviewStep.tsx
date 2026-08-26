import { useEffect, useState } from "react";
import {
  Alert,
  Badge,
  Button,
  Card,
  Group,
  Loader,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from "@mantine/core";
import { notifications } from "@mantine/notifications";
import {
  useCommitImport,
  useImportAnalysis,
  type ImportCommitResult,
} from "../../../api/import";
import { isNotFoundError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { AdvancedOnly, SimpleOnly } from "../../../components/uimode/AdvancedOnly";
import { useIsSimpleMode } from "../../../lib/uiMode/useUiMode";
import { SessionExpiredPanel } from "../SessionExpiredPanel";
import { ImportResultView } from "../ImportResultView";
import { userErrorText } from "../userErrorText";
import { COACH_TARGETS } from "./MappingStep";

interface ReviewStepProps {
  planId: string;
  sessionId: string;
  onAdjust: () => void;
  /** v0.6.0 audit-fix B4: "Välj en annan fil" - restarts the wizard at step 1, always available
   *  regardless of ui-mode (unlike "Justera", which is ADVANCED-only). */
  onRestart: () => void;
  onExpired: () => void;
}

/**
 * One-click import review: shows every automatic decision (sheet, mapping, row counts) with
 * plain-Swedish reasons, then commits on a single "Importera" click. "Justera" drops into the
 * existing step-by-step wizard with the session already pre-filled.
 */
export function ReviewStep({ planId, sessionId, onAdjust, onRestart, onExpired }: ReviewStepProps) {
  const analysisQuery = useImportAnalysis(planId, sessionId);
  const commit = useCommitImport(planId, sessionId);
  const isSimple = useIsSimpleMode();
  const [templateName, setTemplateName] = useState("");
  const [result, setResult] = useState<ImportCommitResult | null>(null);

  // A hand-edited `?step=review` URL (or a stale link) must not bypass the confidence gate: once
  // analysis resolves and turns out NOT ready, drop straight into the step-by-step wizard (same
  // mechanism as clicking "Justera") instead of ever rendering the one-click card below.
  useEffect(() => {
    if (analysisQuery.data && !analysisQuery.data.readyToCommit) {
      onAdjust();
    }
  }, [analysisQuery.data, onAdjust]);

  if (analysisQuery.isError && isNotFoundError(analysisQuery.error)) {
    return <SessionExpiredPanel onRestart={onExpired} />;
  }
  if (analysisQuery.isError) {
    return (
      <Alert color="red" title={sv.common.error}>
        <Stack gap="sm">
          <Text>{userErrorText(analysisQuery.error)}</Text>
          <Button onClick={() => void analysisQuery.refetch()} w="fit-content" variant="default">
            {sv.importWizard.retryButton}
          </Button>
        </Stack>
      </Alert>
    );
  }
  if (analysisQuery.isLoading || !analysisQuery.data) {
    return <Loader />;
  }

  const analysis = analysisQuery.data;

  if (!analysis.readyToCommit) {
    // The effect above is already redirecting via onAdjust(); render nothing meanwhile.
    return <Loader />;
  }

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
        message: userErrorText(error),
      });
    }
  };

  if (result) {
    return <ImportResultView planId={planId} result={result} />;
  }

  const targetLabel = (target: string): string => {
    if (target === "ignore") {
      return sv.importWizard.mapping.ignoreOption;
    }
    const labels = sv.importWizard.mapping.targets as Record<string, string>;
    return labels[target] ?? target;
  };

  // v0.6.0 F4 review fix (FIX 6, MAJOR, decided): SIMPLE mode hides coach-target rows from this
  // decisions table entirely (never renders the column header, target label, or reason for a
  // coachName/isCoach mapping) - same COACH_TARGETS vocabulary MappingStep's own disabled-row
  // filtering uses, exported from there for exactly this reuse. Deliberately UNCHANGED: the commit
  // payload/mapping submission below (handleCommit) - simple mode hides, it never changes semantics,
  // so imported coach data still lands in the DB and stays visible in ADVANCED mode.
  const visibleColumns = isSimple ? analysis.columns.filter((column) => !COACH_TARGETS.has(column.target)) : analysis.columns;
  // v0.6.0 audit-fix B4: the "Kolumner" card must match what the table below actually shows - in
  // SIMPLE mode that's `visibleColumns`, not the backend's own (coach-inclusive) mappedCount/
  // ignoredCount, plus a footer note naming how many columns are hidden in this mode.
  const hiddenColumnCount = analysis.columns.length - visibleColumns.length;
  const mappedCount = isSimple
    ? visibleColumns.filter((column) => column.target !== "ignore").length
    : analysis.mappedCount;
  const ignoredCount = isSimple
    ? visibleColumns.filter((column) => column.target === "ignore").length
    : analysis.ignoredCount;

  return (
    <Stack gap="md">
      <Title order={4}>{sv.importWizard.review.heading}</Title>
      <Text c="dimmed">{isSimple ? sv.importWizard.review.introSimple : sv.importWizard.review.intro}</Text>

      {analysis.usedTemplate && analysis.templateName && (
        <Alert color="blue" title={sv.importWizard.review.templateBannerTitle}>
          {sv.importWizard.review.templateBanner(analysis.templateName)}
        </Alert>
      )}

      <Group align="stretch" grow>
        <Card withBorder padding="md">
          <Text size="sm" c="dimmed">
            {sv.importWizard.review.sheetCardLabel}
          </Text>
          <Text fw={600} data-testid="import-review-sheet">
            {analysis.selectedSheet}
          </Text>
          <Text size="sm">{analysis.sheetReason}</Text>
        </Card>
        <Card withBorder padding="md">
          <Text size="sm" c="dimmed">
            {sv.importWizard.review.mappingCardLabel}
          </Text>
          <Text fw={600}>{sv.importWizard.review.mappingSummary(mappedCount, ignoredCount)}</Text>
          {isSimple && hiddenColumnCount > 0 && (
            <Text size="xs" c="dimmed">
              {sv.importWizard.review.hiddenColumnNote(hiddenColumnCount)}
            </Text>
          )}
        </Card>
        <Card withBorder padding="md">
          <Text size="sm" c="dimmed">
            {sv.importWizard.review.rowsCardLabel}
          </Text>
          <Text fw={600}>
            {sv.importWizard.review.rowsSummary(
              analysis.playerRowCount,
              analysis.warnRowCount,
              analysis.skipRowCount,
            )}
          </Text>
        </Card>
      </Group>

      {analysis.warnings.length > 0 && (
        <Alert color="yellow" title={sv.importWizard.review.warningsHeading}>
          <Stack gap={2}>
            {analysis.warnings.map((warning, index) => (
              <Text size="sm" key={index}>
                {warning}
              </Text>
            ))}
          </Stack>
        </Alert>
      )}

      <Table striped highlightOnHover withTableBorder>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{sv.importWizard.mapping.columnHeader}</Table.Th>
            <Table.Th>{sv.importWizard.mapping.targetHeader}</Table.Th>
            <Table.Th>{sv.importWizard.review.reasonHeader}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {visibleColumns.map((column) => (
            <Table.Tr key={column.columnIndex}>
              <Table.Td>
                <Group gap="xs">
                  <Text size="sm">{column.headerText || "—"}</Text>
                  {column.synthetic && (
                    <Badge size="xs" variant="light">
                      {sv.importWizard.review.derivedBadge}
                    </Badge>
                  )}
                </Group>
                {column.synthetic && (
                  <Text size="xs" c="dimmed" fs="italic">
                    {sv.importWizard.review.derivedHint}
                  </Text>
                )}
              </Table.Td>
              <Table.Td>
                <Text size="sm">{targetLabel(column.target)}</Text>
              </Table.Td>
              <Table.Td>
                <Text size="sm" c="dimmed">
                  {column.reason}
                </Text>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>

      {/* v0.6.0 audit-fix B4: "Spara mappning som mall" is an advanced power-user affordance (naming
          and reusing a mapping template) - hidden in SIMPLE mode rather than left on a card whose
          whole point there is "one click, no decisions to make". */}
      <AdvancedOnly>
        <TextInput
          label={sv.importWizard.commit.templateNameLabel}
          placeholder={sv.importWizard.commit.templateNamePlaceholder}
          value={templateName}
          onChange={(event) => setTemplateName(event.currentTarget.value)}
        />
      </AdvancedOnly>

      <Group>
        <Button loading={commit.isPending} onClick={() => void handleCommit()}>
          {sv.importWizard.review.importButton}
        </Button>
        {/* v0.6.0 F4 (M-S4): "Justera" is the escape hatch into the classic step-by-step wizard on
            the CONFIDENT path only - this component only ever renders once analysis.readyToCommit is
            true (the effect above redirects via onAdjust() otherwise), so the non-confident fallback
            wizard stays reachable regardless of uiMode; only this shortcut is ADVANCED-only. */}
        <AdvancedOnly>
          <Button variant="default" onClick={onAdjust} disabled={commit.isPending}>
            {sv.importWizard.review.adjustButton}
          </Button>
        </AdvancedOnly>
        {/* v0.6.0 audit-fix B4: available in BOTH modes - the honest "this is the wrong file"
            escape hatch, unlike "Justera" which assumes the file is right and only the mapping
            needs fixing. */}
        <Button variant="default" onClick={onRestart} disabled={commit.isPending}>
          {sv.importWizard.review.chooseAnotherFileButton}
        </Button>
      </Group>

      {/* v0.6.0 F4 review fix (minor): SIMPLE mode drops the "Justera" escape hatch above - this
          dimmed hint tells an admin who spots a mapping mistake in the table how to actually fix it,
          instead of leaving no path at all. */}
      <SimpleOnly>
        <Text size="xs" c="dimmed" data-testid="review-simple-adjust-hint">
          {sv.importWizard.review.simpleAdjustHint}
        </Text>
      </SimpleOnly>
    </Stack>
  );
}
