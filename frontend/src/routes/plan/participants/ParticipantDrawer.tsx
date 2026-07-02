import { useEffect, useState } from "react";
import {
  Alert,
  Badge,
  Button,
  Divider,
  Drawer,
  Group,
  Loader,
  NumberInput,
  ScrollArea,
  SimpleGrid,
  Stack,
  Switch,
  Text,
  Textarea,
  TextInput,
  Title,
} from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { useFieldDefinitions } from "../../../api/fieldDefinitions";
import { useParticipantFieldValues, useUpdateParticipantFieldValues } from "../../../api/fieldValues";
import { useUpdateParticipant } from "../../../api/participants";
import { useDeleteParticipantComments } from "../../../api/comments";
import { ApiError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { DeleteConfirmModal } from "../../../components/DeleteConfirmModal";
import { CustomFieldEditor } from "./CustomFieldEditor";
import type { ParticipantRow } from "./participantRow";

interface ParticipantDrawerProps {
  planId: string;
  participant: ParticipantRow | null;
  allParticipants: ParticipantRow[];
  onClose: () => void;
}

/**
 * Deltagarvy detail drawer (spec §19.4/§9.1's "structured entry" side-by-side working surface): left
 * side is the sensitive comment reference (importedComment read-only, internalNote editable, a
 * per-participant "Radera kommentarer" danger action); right side is every structured field
 * (manualLevelScore/previousGroupName/previousGroupLevel/waitlisted/manualReviewFlag, all via
 * participant PATCH) plus every custom-field value for this participant (via field-values PUT),
 * rendered by field type.
 */
export function ParticipantDrawer({ planId, participant, allParticipants, onClose }: ParticipantDrawerProps) {
  return (
    <Drawer opened={participant !== null} onClose={onClose} position="right" size="xl" title={participant?.name ?? ""}>
      {participant && (
        <ParticipantDrawerBody
          key={participant.id}
          planId={planId}
          participant={participant}
          allParticipants={allParticipants}
          onClose={onClose}
        />
      )}
    </Drawer>
  );
}

interface StructuredDraft {
  manualLevelScore: number | null;
  previousGroupName: string | null;
  previousGroupLevel: number | null;
  waitlisted: boolean;
  manualReviewFlag: boolean;
  internalNote: string;
}

function structuredDraftFrom(participant: ParticipantRow): StructuredDraft {
  return {
    manualLevelScore: participant.manualLevelScore ?? null,
    previousGroupName: participant.previousGroupName ?? null,
    previousGroupLevel: participant.previousGroupLevel ?? null,
    waitlisted: participant.waitlisted,
    manualReviewFlag: participant.manualReviewFlag,
    internalNote: participant.internalNote ?? "",
  };
}

function diff<T extends object>(draft: T, original: T): Partial<T> {
  const changed: Partial<T> = {};
  for (const key of Object.keys(draft) as (keyof T)[]) {
    if (JSON.stringify(draft[key]) !== JSON.stringify(original[key])) {
      changed[key] = draft[key];
    }
  }
  return changed;
}

interface ParticipantDrawerBodyProps {
  planId: string;
  participant: ParticipantRow;
  allParticipants: ParticipantRow[];
  onClose: () => void;
}

function ParticipantDrawerBody({ planId, participant, allParticipants, onClose }: ParticipantDrawerBodyProps) {
  const fieldDefinitions = useFieldDefinitions(planId);
  const fieldValues = useParticipantFieldValues(planId, participant.id);
  const updateParticipant = useUpdateParticipant(planId);
  const updateFieldValues = useUpdateParticipantFieldValues(planId, participant.id);
  const deleteComments = useDeleteParticipantComments(planId);

  const [structuredDraft, setStructuredDraft] = useState<StructuredDraft>(() => structuredDraftFrom(participant));
  const [originalStructured, setOriginalStructured] = useState<StructuredDraft>(() => structuredDraftFrom(participant));
  const [customDraft, setCustomDraft] = useState<Record<string, unknown>>({});
  const [originalCustom, setOriginalCustom] = useState<Record<string, unknown>>({});
  const [deleteCommentsOpen, setDeleteCommentsOpen] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (fieldValues.data) {
      const values: Record<string, unknown> = {};
      for (const fv of fieldValues.data) {
        values[fv.key] = fv.value ?? null;
      }
      setCustomDraft(values);
      setOriginalCustom(values);
    }
    // Only re-sync from the server on a genuinely new response for this participant.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fieldValues.data]);

  const structuredChanges = diff(structuredDraft, originalStructured);
  const customChanges = diff(customDraft, originalCustom);
  const isDirty = Object.keys(structuredChanges).length > 0 || Object.keys(customChanges).length > 0;

  const hasComment = Boolean(participant.importedComment && participant.importedComment.trim().length > 0);
  const hasInternalNoteOriginally = Boolean(participant.internalNote && participant.internalNote.trim().length > 0);

  const handleSave = async () => {
    setSaving(true);
    try {
      if (Object.keys(structuredChanges).length > 0) {
        await updateParticipant.mutateAsync({ id: participant.id, body: structuredChanges });
      }
      if (Object.keys(customChanges).length > 0) {
        await updateFieldValues.mutateAsync(customChanges);
      }
      setOriginalStructured(structuredDraft);
      setOriginalCustom(customDraft);
      notifications.show({ color: "green", message: sv.participants.drawer.saveSuccess });
    } catch (error) {
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: error instanceof ApiError ? error.message : sv.participants.drawer.saveFailed,
      });
    } finally {
      setSaving(false);
    }
  };

  const handleDeleteComments = () => {
    deleteComments.mutate(participant.id, {
      onSuccess: () => {
        setDeleteCommentsOpen(false);
        setStructuredDraft((prev) => ({ ...prev, internalNote: "" }));
        setOriginalStructured((prev) => ({ ...prev, internalNote: "" }));
        notifications.show({ color: "green", message: sv.participants.drawer.deleteCommentsModal.title });
      },
      onError: (error) => {
        notifications.show({
          color: "red",
          title: sv.common.error,
          message: error instanceof ApiError ? error.message : sv.participants.drawer.deleteCommentsModal.failed,
        });
      },
    });
  };

  return (
    <Stack gap="md">
      <SimpleGrid cols={2} spacing="lg">
        {/* --- Left: sensitive comment reference --- */}
        <Stack gap="sm">
          <Group justify="space-between">
            <Title order={5}>{sv.participants.drawer.commentsHeading}</Title>
            <Badge color="orange" variant="light">
              {sv.participants.drawer.sensitiveBadge}
            </Badge>
          </Group>

          <div>
            <Text size="sm" fw={500}>
              {sv.participants.drawer.importedCommentLabel}
            </Text>
            <Text size="sm" c={hasComment ? undefined : "dimmed"}>
              {hasComment ? participant.importedComment : sv.participants.drawer.noComment}
            </Text>
          </div>

          <Textarea
            label={sv.participants.drawer.internalNoteLabel}
            placeholder={sv.participants.drawer.internalNotePlaceholder}
            autosize
            minRows={3}
            value={structuredDraft.internalNote}
            onChange={(event) => setStructuredDraft((prev) => ({ ...prev, internalNote: event.currentTarget.value }))}
          />

          <Button
            color="red"
            variant="outline"
            size="xs"
            disabled={!hasComment && !hasInternalNoteOriginally}
            onClick={() => setDeleteCommentsOpen(true)}
          >
            {sv.participants.drawer.deleteCommentsButton}
          </Button>
        </Stack>

        {/* --- Right: structured fields --- */}
        <Stack gap="sm">
          <Title order={5}>{sv.participants.drawer.structuredHeading}</Title>

          <NumberInput
            label={sv.participants.drawer.manualLevelScoreLabel}
            value={structuredDraft.manualLevelScore ?? ""}
            onChange={(value) =>
              setStructuredDraft((prev) => ({ ...prev, manualLevelScore: value === "" ? null : Number(value) }))
            }
          />
          <TextInput
            label={sv.participants.drawer.previousGroupNameLabel}
            value={structuredDraft.previousGroupName ?? ""}
            onChange={(event) =>
              setStructuredDraft((prev) => ({
                ...prev,
                previousGroupName: event.currentTarget.value === "" ? null : event.currentTarget.value,
              }))
            }
          />
          <NumberInput
            label={sv.participants.drawer.previousGroupLevelLabel}
            value={structuredDraft.previousGroupLevel ?? ""}
            onChange={(value) =>
              setStructuredDraft((prev) => ({ ...prev, previousGroupLevel: value === "" ? null : Number(value) }))
            }
          />
          <Switch
            label={sv.participants.drawer.waitlistedLabel}
            checked={structuredDraft.waitlisted}
            onChange={(event) => setStructuredDraft((prev) => ({ ...prev, waitlisted: event.currentTarget.checked }))}
          />
          <Switch
            label={sv.participants.drawer.manualReviewFlagLabel}
            checked={structuredDraft.manualReviewFlag}
            onChange={(event) =>
              setStructuredDraft((prev) => ({ ...prev, manualReviewFlag: event.currentTarget.checked }))
            }
          />
        </Stack>
      </SimpleGrid>

      <Divider />

      <Title order={5}>{sv.participants.drawer.customFieldsHeading}</Title>

      {fieldValues.isLoading && <Loader size="sm" />}
      {fieldValues.isError && (
        <Alert color="red">
          {fieldValues.error instanceof ApiError ? fieldValues.error.message : sv.participants.drawer.fieldValuesSaveFailed}
        </Alert>
      )}

      {fieldValues.data && (
        <ScrollArea.Autosize mah={320}>
          <SimpleGrid cols={2} spacing="md">
            {fieldValues.data.map((fv) => (
              <CustomFieldEditor
                key={fv.key}
                fieldValue={fv}
                definition={fieldDefinitions.data?.find((def) => def.id === fv.fieldDefinitionId)}
                value={customDraft[fv.key] ?? null}
                onChange={(value) => setCustomDraft((prev) => ({ ...prev, [fv.key]: value }))}
                participants={allParticipants}
                selfId={participant.id}
              />
            ))}
          </SimpleGrid>
        </ScrollArea.Autosize>
      )}

      <Group justify="flex-end">
        <Button variant="default" onClick={onClose}>
          {sv.participants.drawer.closeButton}
        </Button>
        <Button onClick={handleSave} disabled={!isDirty} loading={saving}>
          {sv.participants.drawer.saveButton}
        </Button>
      </Group>

      <DeleteConfirmModal
        opened={deleteCommentsOpen}
        title={sv.participants.drawer.deleteCommentsModal.title}
        message={sv.participants.drawer.deleteCommentsModal.message}
        confirmLabel={sv.participants.drawer.deleteCommentsModal.confirm}
        loading={deleteComments.isPending}
        onClose={() => setDeleteCommentsOpen(false)}
        onConfirm={handleDeleteComments}
      />
    </Stack>
  );
}
