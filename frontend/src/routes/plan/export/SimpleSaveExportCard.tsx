import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Alert, Button, Card, Divider, Group, List, Loader, Modal, Stack, Text, TextInput, Title } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { ApiError } from "../../../api/client";
import { useExportPlan } from "../../../api/export";
import { usePlan } from "../../../api/plans";
import { useCreateSavedPlan, useSavedPlans } from "../../../api/savedPlans";
import { useOptimizationRuns } from "../../../api/runs";
import { sv } from "../../../i18n/sv";
import { formatDateTime } from "../../../lib/formatDateTime";
import { hasUsableResult } from "../runStatus";
import { describeExportResult } from "./exportForm";

/** "sv-SE" `YYYY-MM-DD` for the prefilled save name - deliberately NOT `formatDateTime` (that's a
 *  short LOCALE display string with a time component, spec §19.9's own "Senaste körning" style) -
 *  the prefill needs a plain, sortable date stamp. `Intl.DateTimeFormat`'s `en-CA` locale happens to
 *  format as `YYYY-MM-DD` natively, but building it from the parts directly avoids depending on that
 *  locale quirk. */
function todayDateStamp(): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function showError(error: unknown, fallback: string) {
  notifications.show({ color: "red", title: sv.common.error, message: error instanceof ApiError ? error.message : fallback });
}

/**
 * v0.6.0 F6 (M-S6): the SIMPLE-mode "Spara & exportera" step 6 surface - ExportPanel.tsx renders
 * this INSTEAD OF the full advanced export surface (format/layout radios, comments checkbox,
 * anonymiserat testdata card) in SIMPLE mode, same `<SimpleOnly>`/`<AdvancedOnly>` split
 * ResourcesPanel.tsx already uses.
 *
 * Two actions, no lifecycle table (that stays an ADVANCED-only concept, see SavedPlansPanel.tsx's
 * own doc comment on why saving/locking has its own tab):
 *  - "Spara plan": a `POST .../saved-plans` with a prefilled `${plan.name} ${YYYY-MM-DD}` name -
 *    success/failure render INLINE in this card (not a toast), so the admin sees the result without
 *    looking away. v0.6.0 audit batch D (D6): guarded by a "same name already exists?" confirm, and
 *    followed by a compact read-only list of this plan's saved versions (newest first).
 *  - "Exportera till Excel": the exact same `useExportPlan` mutation ExportPanel's advanced card
 *    uses, but with the request body PINNED to `{format:"xlsx", layout:"grouped",
 *    includeComments:false}` - comments can never leak through this simplified path (see
 *    SimpleSaveExportCard.test.tsx's own pinned-request-body test). Every other format/layout
 *    choice, and the "Inkludera kommentarer" opt-in, stays reachable only in Avancerat läge (the
 *    dimmed hint line says so explicitly).
 *
 * v0.6.0 audit batch D (D8): the "kör en optimering först" gate no longer borrows ExportPanel's
 * ADVANCED-worded `sv.export.emptyNoRun` (which names "fliken Optimering", a tab that doesn't exist
 * in SIMPLE's navigation) - it has its own SIMPLE-worded copy + a working "Gå till Optimera" button,
 * and a FAILED runs query renders as its own distinct error state (never the "no run yet" claim).
 */
export function SimpleSaveExportCard() {
  const { planId } = useParams<{ planId: string }>();
  const navigate = useNavigate();
  const plan = usePlan(planId);
  const runs = useOptimizationRuns(planId);
  const savedPlans = useSavedPlans(planId);
  // v0.6.0 final pre-release fix round (FIX 1, MAJOR): was `runs.data?.length > 0`, which accepted
  // ANY run - including one still SOLVING or one that FAILED outright, neither of which has anything
  // to export. See runStatus.ts's own doc comment for the exact FINISHED/CANCELLED-with-summary
  // discriminator.
  const hasRun = hasUsableResult(runs.data);

  const createSavedPlan = useCreateSavedPlan(planId ?? "");
  const exportPlan = useExportPlan(planId ?? "");

  const [name, setName] = useState("");
  const [nameInitialized, setNameInitialized] = useState(false);
  const [savedAt, setSavedAt] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  // v0.6.0 audit batch D (D6): "a version with this exact name already exists" double-save guard -
  // opens instead of saving immediately; confirming proceeds with the save anyway.
  const [duplicateConfirmOpen, setDuplicateConfirmOpen] = useState(false);
  // v0.6.0 audit batch D (D7): a cancelled Tauri save dialog used to be completely silent (saveFile
  // just resolved `false`, nothing shown) - rendered as a subtle inline note (not a toast, which
  // would misleadingly read as either a success or an error) right under the export button.
  const [exportCancelledNote, setExportCancelledNote] = useState(false);

  // Prefills the name field the moment the plan's own name has loaded - only once, and only if the
  // admin hasn't already started typing (so a slow-loading plan query can never clobber an
  // in-progress edit).
  useEffect(() => {
    if (!nameInitialized && plan.data) {
      setName(`${plan.data.name} ${todayDateStamp()}`);
      setNameInitialized(true);
    }
  }, [plan.data, nameInitialized]);

  if (!planId) {
    return null;
  }

  const performSave = () => {
    setSaveError(null);
    setSavedAt(null);
    createSavedPlan.mutate(
      { name: name.trim() },
      {
        onSuccess: (created) => {
          setSavedAt(created.createdAt);
        },
        onError: (error) => {
          setSaveError(error instanceof ApiError ? error.message : sv.simple.saveExport.saveFailed);
        },
      },
    );
  };

  const handleSave = () => {
    const trimmed = name.trim();
    const isDuplicateName = (savedPlans.data ?? []).some((version) => version.name === trimmed);
    if (isDuplicateName) {
      setDuplicateConfirmOpen(true);
      return;
    }
    performSave();
  };

  const handleExport = () => {
    setExportCancelledNote(false);
    exportPlan.mutate(
      { format: "xlsx", layout: "grouped", includeComments: false },
      {
        onSuccess: (result) => {
          const notification = describeExportResult(result, {
            downloaded: sv.export.exportSuccess,
            savedGeneric: sv.export.exportSaved,
            savedWithFilename: sv.export.exportSavedToDisk,
            cancelled: sv.export.exportCancelled,
          });
          if (!result.saved) {
            setExportCancelledNote(true);
            return;
          }
          notifications.show({ color: notification.color, message: notification.message });
        },
        onError: (error) => showError(error, sv.export.exportFailed),
      },
    );
  };

  // v0.6.0 audit batch D (D6): newest-first for display - useSavedPlans itself returns oldest-first
  // (its own doc comment: `ORDER BY created_at, id`, the plan's full save HISTORY), so this reverses
  // a copy rather than mutating the cached array.
  const savedVersionsNewestFirst = [...(savedPlans.data ?? [])].reverse();

  return (
    <Card withBorder padding="lg" data-testid="simple-save-export-card">
      <Title order={4} mb="md">
        {sv.simple.steps.exportera}
      </Title>

      <Text size="sm" c="dimmed" mb="md">
        {sv.simple.saveExport.intro}
      </Text>

      <TextInput
        label={sv.simple.saveExport.nameLabel}
        value={name}
        onChange={(event) => setName(event.currentTarget.value)}
        mb="sm"
        data-testid="simple-save-name-input"
      />
      <Button
        onClick={handleSave}
        loading={createSavedPlan.isPending}
        disabled={name.trim().length === 0}
        data-testid="simple-save-button"
        mb="md"
      >
        {/* v0.6.0 F6 review fix (FIX 6, MINOR): sv.simple.saveExport.saving was a dead key (defined,
            never read) - wired here as the button's own loading label instead of dropping it, since a
            save can take a moment (network round-trip) and "Sparar…" is better feedback than the bare
            spinner (Mantine's `loading` prop) alone. */}
        {createSavedPlan.isPending ? sv.simple.saveExport.saving : sv.simple.saveExport.saveButton}
      </Button>

      {savedAt && (
        <Alert color="green" mb="md" data-testid="simple-save-success">
          {sv.simple.saveExport.savedSuccess(formatDateTime(savedAt))}
        </Alert>
      )}
      {saveError && (
        <Alert color="red" mb="md" data-testid="simple-save-error">
          {saveError}
        </Alert>
      )}

      <Text fw={600} size="sm" mb={4}>
        {sv.simple.saveExport.savedVersionsHeading}
      </Text>
      {savedPlans.isLoading && <Loader size="xs" />}
      {!savedPlans.isLoading && savedVersionsNewestFirst.length === 0 && (
        <Text size="sm" c="dimmed" data-testid="simple-saved-versions-empty">
          {sv.simple.saveExport.savedVersionsEmpty}
        </Text>
      )}
      {savedVersionsNewestFirst.length > 0 && (
        <List size="sm" spacing={4} data-testid="simple-saved-versions-list">
          {savedVersionsNewestFirst.map((version) => (
            <List.Item key={version.id}>
              {version.name} — {formatDateTime(version.createdAt)}
            </List.Item>
          ))}
        </List>
      )}

      <Divider my="md" />

      {runs.isLoading && <Loader size="sm" />}
      {runs.isError && (
        <Alert color="red" mb="md" data-testid="export-runs-error">
          <Stack gap={8}>
            <Text size="sm">{sv.simple.saveExport.loadRunsFailed}</Text>
            <Button size="xs" variant="light" onClick={() => runs.refetch()}>
              {sv.simple.saveExport.retryButton}
            </Button>
          </Stack>
        </Alert>
      )}
      {!runs.isLoading && !runs.isError && !hasRun && (
        <Alert color="gray" mb="md" data-testid="export-empty-hint">
          <Stack gap={8}>
            <Text size="sm">{sv.simple.saveExport.noRun.message}</Text>
            <Button
              size="xs"
              variant="light"
              onClick={() => navigate(`/plans/${planId}/optimering`)}
              style={{ alignSelf: "flex-start" }}
            >
              {sv.simple.saveExport.noRun.button}
            </Button>
          </Stack>
        </Alert>
      )}

      <Button
        size="lg"
        onClick={handleExport}
        loading={exportPlan.isPending}
        disabled={!hasRun || runs.isError}
        data-testid="simple-export-button"
      >
        {sv.simple.saveExport.exportButton}
      </Button>

      <Text size="xs" c="dimmed" mt="sm">
        {sv.simple.saveExport.exportExplanation}
      </Text>
      {exportCancelledNote && (
        <Text size="xs" c="dimmed" mt={4} data-testid="simple-export-cancelled-note">
          {sv.export.exportCancelled}
        </Text>
      )}

      <Text size="xs" c="dimmed" mt="sm">
        {sv.simple.saveExport.advancedHint}
      </Text>

      <Modal
        opened={duplicateConfirmOpen}
        onClose={() => setDuplicateConfirmOpen(false)}
        title={sv.simple.saveExport.duplicateNameConfirm.title}
      >
        <Text size="sm" mb="lg">
          {sv.simple.saveExport.duplicateNameConfirm.message}
        </Text>
        <Group justify="flex-end">
          <Button variant="default" onClick={() => setDuplicateConfirmOpen(false)}>
            {sv.common.cancel}
          </Button>
          <Button
            data-testid="simple-save-duplicate-confirm"
            onClick={() => {
              setDuplicateConfirmOpen(false);
              performSave();
            }}
          >
            {sv.simple.saveExport.duplicateNameConfirm.confirmLabel}
          </Button>
        </Group>
      </Modal>
    </Card>
  );
}
