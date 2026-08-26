import { useEffect, useMemo, useState } from "react";
import { Alert, Button, Group, Loader, NumberInput, Stack, Table, Tabs, Text, Title } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { useImportPreview, useImportAnalysis, useSetImportHeader } from "../../../api/import";
import { ApiError, isNotFoundError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { readCachedImportSheets } from "../importSessionStorage";
import { SessionExpiredPanel } from "../SessionExpiredPanel";

interface SheetStepProps {
  planId: string;
  sessionId: string;
  onNext: (sheet: string) => void;
  onBack: () => void;
  onExpired: () => void;
  /** v0.6.0 audit-fix B3: true only on the FIRST arrival here right after a non-confident one-click
   *  upload (ImportWizardPage.handleUploaded routing to step="sheet") - never when the user reached
   *  this step via "Justera" from the confident review card, and cleared again once the user
   *  proceeds past this step (see ImportWizardPage's onNext wiring). */
  showAnalysisFailedNotice?: boolean;
}

/** Wizard step 2 (spec §8.3): choose a sheet, preview ~30 rows, confirm/override the detected header
 *  row. Confirming (even leaving the detected default) calls PUT .../header, which is also what
 *  registers "the selected sheet" server-side for every later step (see ImportSession javadoc). */
export function SheetStep({ planId, sessionId, onNext, onBack, onExpired, showAnalysisFailedNotice }: SheetStepProps) {
  const sheets = useMemo(() => readCachedImportSheets(sessionId), [sessionId]);
  const analysis = useImportAnalysis(planId, sessionId);
  const [selectedSheet, setSelectedSheet] = useState<string | null>(null);
  const [headerRowOverride, setHeaderRowOverride] = useState<number | null>(null);

  useEffect(() => {
    if (selectedSheet !== null) {
      return;
    }
    // Wait for the analysis query to settle before falling back to sheets[0] - otherwise, on a slow
    // analysis response, this effect re-runs on every render while analysis.data is still undefined,
    // picks sheets[0], and the `selectedSheet !== null` guard above then permanently blocks the
    // analyzed sheet (once it arrives) from ever being selected.
    if (analysis.isPending) {
      return;
    }
    const fromAnalysis = analysis.data?.selectedSheet;
    if (fromAnalysis && sheets.some((sheet) => sheet.name === fromAnalysis)) {
      setSelectedSheet(fromAnalysis);
      return;
    }
    if (sheets[0]) {
      setSelectedSheet(sheets[0].name);
    }
  }, [analysis.data, analysis.isPending, sheets, selectedSheet]);

  const preview = useImportPreview(planId, sessionId, selectedSheet);
  const setHeader = useSetImportHeader(planId, sessionId);

  useEffect(() => {
    setHeaderRowOverride(null);
  }, [selectedSheet]);

  if (sheets.length === 0) {
    return (
      <Alert color="yellow" title={sv.importWizard.sheet.restoreFailedTitle}>
        <Stack gap="sm">
          <Text>{sv.importWizard.sheet.restoreFailedMessage}</Text>
          <Button onClick={onExpired} w="fit-content">
            {sv.importWizard.sessionExpired.restartButton}
          </Button>
        </Stack>
      </Alert>
    );
  }

  if (preview.isError && isNotFoundError(preview.error)) {
    return <SessionExpiredPanel onRestart={onExpired} />;
  }

  const templateName = sheets.find((s) => s.name === selectedSheet)?.suggestedTemplateName ?? null;
  const effectiveHeaderRow = headerRowOverride ?? preview.data?.headerRowIndex ?? 0;

  const handleNext = async () => {
    if (!selectedSheet) {
      return;
    }
    try {
      await setHeader.mutateAsync({ sheet: selectedSheet, headerRowIndex: effectiveHeaderRow });
      onNext(selectedSheet);
    } catch (error) {
      if (isNotFoundError(error)) {
        onExpired();
        return;
      }
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: error instanceof ApiError ? error.message : sv.importWizard.sheet.loadFailed,
      });
    }
  };

  return (
    <Stack gap="md">
      <Title order={4}>{sv.importWizard.sheet.heading}</Title>

      {showAnalysisFailedNotice && <Alert color="blue">{sv.importWizard.sheet.analysisFailedNotice}</Alert>}

      {sheets.length > 1 && (
        <Tabs value={selectedSheet} onChange={setSelectedSheet}>
          <Tabs.List>
            {sheets.map((sheet) => (
              <Tabs.Tab key={sheet.name} value={sheet.name}>
                {sheet.name}
              </Tabs.Tab>
            ))}
          </Tabs.List>
        </Tabs>
      )}

      {templateName && <Alert color="blue">{sv.importWizard.sheet.templateSuggested(templateName)}</Alert>}

      {preview.isLoading && <Loader size="sm" />}
      {preview.isError && !isNotFoundError(preview.error) && (
        <Alert color="red">
          {preview.error instanceof ApiError ? preview.error.message : sv.importWizard.sheet.loadFailed}
        </Alert>
      )}

      {preview.data && (
        <>
          {/* v0.6.0 audit-fix B2: displayed/entered as the 1-based Excel row number (matching the
              headerRowHint's "samma radnummer som i Excel" promise) while `effectiveHeaderRow`/
              `headerRowOverride` stay 0-based internally - that's the shape PUT .../header (and
              `preview.data.headerRowIndex`) actually expects. */}
          <NumberInput
            label={sv.importWizard.sheet.headerRowLabel}
            description={sv.importWizard.sheet.headerRowHint}
            min={1}
            max={Math.max(1, preview.data.rowCount)}
            value={effectiveHeaderRow + 1}
            onChange={(value) => setHeaderRowOverride(Math.max(0, (Number(value) || 1) - 1))}
            w={220}
          />

          <Table.ScrollContainer minWidth={480}>
            <Table striped withTableBorder>
              <Table.Tbody>
                {preview.data.rows.map((row, rowIndex) => (
                  <Table.Tr key={rowIndex} bg={rowIndex === effectiveHeaderRow ? "blue.1" : undefined}>
                    <Table.Td>
                      {rowIndex + 1}
                      {rowIndex === effectiveHeaderRow && (
                        <Text span size="xs" c="blue" ml={4}>
                          ({sv.importWizard.sheet.headerRowLabel})
                        </Text>
                      )}
                    </Table.Td>
                    {row.map((cell, cellIndex) => (
                      <Table.Td key={cellIndex}>{cell}</Table.Td>
                    ))}
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
        </>
      )}

      <Group justify="flex-end">
        <Button variant="default" onClick={onBack}>
          {sv.common.back}
        </Button>
        <Button onClick={handleNext} loading={setHeader.isPending} disabled={!selectedSheet || !preview.data}>
          {sv.importWizard.sheet.nextButton}
        </Button>
      </Group>
    </Stack>
  );
}
