import { useState } from "react";
import { useParams } from "react-router-dom";
import { Alert, Button, Card, Checkbox, Group, Loader, Radio, Stack, Text, Title } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { ApiError } from "../../../api/client";
import { useExportAnonymized, useExportPlan } from "../../../api/export";
import { useOptimizationRuns } from "../../../api/runs";
import type { ExportFormat, ExportLayout } from "../../../api/types";
import { HelpTip } from "../../../components/HelpTip";
import { sv } from "../../../i18n/sv";
import { isGroupedLayoutDisabled, normalizeLayoutForFormat, showCommentsWarning } from "./exportForm";

function showError(error: unknown, fallback: string) {
  notifications.show({ color: "red", title: sv.common.error, message: error instanceof ApiError ? error.message : fallback });
}

/**
 * Exportvy (spec §20): format (.xlsx/.csv) + layout (Grupperad "som kansliets arbetsblad" / Platt
 * tabell) pickers, with the invalid grouped+csv combination disabled client-side (the backend 400s
 * it - `exporter/ExportService#export`, mirrored in `exportForm.ts`); "Inkludera kommentarer i
 * export" defaults OFF with a sensitive-data warning once ticked (spec §20.3); a separate
 * "Anonymiserat testdata" card (spec §21.3) with its own format choice, for felsökning/open source -
 * never includes names/contact details/comments regardless of the checkbox above (that setting only
 * applies to the main export). Both cards are disabled with a hint until the plan has at least one
 * optimization run (the export always reflects the plan's current live assignments, spec §20.2, but
 * there is nothing meaningful to export before a solve has ever produced groups/assignments).
 */
export function ExportPanel() {
  const { planId } = useParams<{ planId: string }>();
  const runs = useOptimizationRuns(planId);
  const hasRun = (runs.data?.length ?? 0) > 0;

  const [format, setFormat] = useState<ExportFormat>("xlsx");
  const [layout, setLayout] = useState<ExportLayout>("grouped");
  const [includeComments, setIncludeComments] = useState(false);
  const [anonymizedFormat, setAnonymizedFormat] = useState<ExportFormat>("xlsx");

  const exportPlan = useExportPlan(planId ?? "");
  const exportAnonymized = useExportAnonymized(planId ?? "");

  if (!planId) {
    return null;
  }

  const handleFormatChange = (value: string) => {
    const nextFormat = value as ExportFormat;
    setFormat(nextFormat);
    setLayout((current) => normalizeLayoutForFormat(nextFormat, current));
  };

  const handleExport = () => {
    exportPlan.mutate(
      { format, layout, includeComments },
      {
        onSuccess: (saved) => {
          if (saved) {
            notifications.show({ color: "green", message: sv.export.exportSuccess });
          }
        },
        onError: (error) => showError(error, sv.export.exportFailed),
      },
    );
  };

  const handleExportAnonymized = () => {
    exportAnonymized.mutate(anonymizedFormat, {
      onSuccess: (saved) => {
        if (saved) {
          notifications.show({ color: "green", message: sv.export.anonymized.exportSuccess });
        }
      },
      onError: (error) => showError(error, sv.export.anonymized.exportFailed),
    });
  };

  return (
    <Stack gap="md">
      <Card withBorder padding="lg" data-testid="export-card">
        <Title order={4} mb="md">
          {sv.export.heading}
        </Title>

        {runs.isLoading && <Loader size="sm" />}
        {!runs.isLoading && !hasRun && (
          <Alert color="gray" mb="md" data-testid="export-empty-hint">
            {sv.export.emptyNoRun}
          </Alert>
        )}

        <Radio.Group value={format} onChange={handleFormatChange} label={sv.export.formatHeading} mb="md">
          <Group mt="xs">
            <Radio value="xlsx" label={sv.export.format.xlsx} disabled={!hasRun} />
            <Radio value="csv" label={sv.export.format.csv} disabled={!hasRun} />
          </Group>
        </Radio.Group>

        <Radio.Group
          value={layout}
          onChange={(value) => setLayout(value as ExportLayout)}
          label={sv.export.layoutHeading}
          mb={4}
        >
          <Group mt="xs">
            <Radio value="grouped" label={sv.export.layout.grouped} disabled={!hasRun || isGroupedLayoutDisabled(format)} />
            <Radio value="flat" label={sv.export.layout.flat} disabled={!hasRun} />
          </Group>
        </Radio.Group>
        {isGroupedLayoutDisabled(format) && (
          <Text size="xs" c="dimmed" mb="md">
            {sv.export.layoutDisabledForCsvHint}
          </Text>
        )}

        <Checkbox
          label={sv.export.includeCommentsLabel}
          description={
            <HelpTip label={sv.help.ariaLabel(sv.export.includeCommentsLabel)}>{sv.help.export.includeComments}</HelpTip>
          }
          checked={includeComments}
          disabled={!hasRun}
          onChange={(event) => setIncludeComments(event.currentTarget.checked)}
          mb={includeComments ? "xs" : "md"}
        />
        {showCommentsWarning(includeComments) && (
          <Alert color="yellow" mb="md" data-testid="comments-warning">
            {sv.export.includeCommentsWarning}
          </Alert>
        )}

        <Button onClick={handleExport} loading={exportPlan.isPending} disabled={!hasRun}>
          {sv.export.exportButton}
        </Button>
      </Card>

      <Card withBorder padding="lg" data-testid="anonymized-export-card">
        <Group gap={4} mb="xs">
          <Title order={5}>{sv.export.anonymized.heading}</Title>
          <HelpTip label={sv.help.ariaLabel(sv.export.anonymized.heading)}>{sv.help.export.anonymized}</HelpTip>
        </Group>
        <Text size="sm" c="dimmed" mb="md">
          {sv.export.anonymized.description}
        </Text>

        <Radio.Group
          value={anonymizedFormat}
          onChange={(value) => setAnonymizedFormat(value as ExportFormat)}
          label={sv.export.formatHeading}
          mb="md"
        >
          <Group mt="xs">
            <Radio value="xlsx" label={sv.export.format.xlsx} disabled={!hasRun} />
            <Radio value="csv" label={sv.export.format.csv} disabled={!hasRun} />
          </Group>
        </Radio.Group>

        <Button
          variant="default"
          onClick={handleExportAnonymized}
          loading={exportAnonymized.isPending}
          disabled={!hasRun}
        >
          {sv.export.anonymized.exportButton}
        </Button>
      </Card>
    </Stack>
  );
}
