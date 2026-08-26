import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { Alert, Button, Card, Divider, Loader, Text, TextInput, Title } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { ApiError } from "../../../api/client";
import { useExportPlan } from "../../../api/export";
import { usePlan } from "../../../api/plans";
import { useCreateSavedPlan } from "../../../api/savedPlans";
import { useOptimizationRuns } from "../../../api/runs";
import { sv } from "../../../i18n/sv";
import { formatDateTime } from "../../../lib/formatDateTime";

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
 *    looking away.
 *  - "Exportera till Excel": the exact same `useExportPlan` mutation ExportPanel's advanced card
 *    uses, but with the request body PINNED to `{format:"xlsx", layout:"grouped",
 *    includeComments:false}` - comments can never leak through this simplified path (see
 *    SimpleSaveExportCard.test.tsx's own pinned-request-body test). Every other format/layout
 *    choice, and the "Inkludera kommentarer" opt-in, stays reachable only in Avancerat läge (the
 *    dimmed hint line says so explicitly).
 *
 * The "kör en optimering först" empty-hint (sv.export.emptyNoRun, `hasRun` gating the export
 * button) is preserved unchanged from ExportPanel's advanced card - saving is NOT gated on it
 * (saving a plan with no run yet is still a legitimate empty snapshot, same as SavedPlansPanel).
 */
export function SimpleSaveExportCard() {
  const { planId } = useParams<{ planId: string }>();
  const plan = usePlan(planId);
  const runs = useOptimizationRuns(planId);
  const hasRun = (runs.data?.length ?? 0) > 0;

  const createSavedPlan = useCreateSavedPlan(planId ?? "");
  const exportPlan = useExportPlan(planId ?? "");

  const [name, setName] = useState("");
  const [nameInitialized, setNameInitialized] = useState(false);
  const [savedAt, setSavedAt] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);

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

  const handleSave = () => {
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

  const handleExport = () => {
    exportPlan.mutate(
      { format: "xlsx", layout: "grouped", includeComments: false },
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

  return (
    <Card withBorder padding="lg" data-testid="simple-save-export-card">
      <Title order={4} mb="md">
        {sv.simple.steps.exportera}
      </Title>

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

      <Divider my="md" />

      {runs.isLoading && <Loader size="sm" />}
      {!runs.isLoading && !hasRun && (
        <Alert color="gray" mb="md" data-testid="export-empty-hint">
          {sv.export.emptyNoRun}
        </Alert>
      )}

      <Button
        size="lg"
        onClick={handleExport}
        loading={exportPlan.isPending}
        disabled={!hasRun}
        data-testid="simple-export-button"
      >
        {sv.simple.saveExport.exportButton}
      </Button>

      <Text size="xs" c="dimmed" mt="sm">
        {sv.simple.saveExport.advancedHint}
      </Text>
    </Card>
  );
}
