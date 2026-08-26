import { useEffect, useRef, useState } from "react";
import type { MutableRefObject } from "react";
import {
  Alert,
  Badge,
  Button,
  Divider,
  Drawer,
  Group,
  Loader,
  Modal,
  NumberInput,
  SimpleGrid,
  Stack,
  Switch,
  Text,
  Textarea,
  TextInput,
  Title,
} from "@mantine/core";
import { IconCircleCheck } from "@tabler/icons-react";
import { notifications } from "@mantine/notifications";
import { useFieldDefinitions } from "../../../api/fieldDefinitions";
import { useParticipantFieldValues, useUpdateParticipantFieldValues } from "../../../api/fieldValues";
import { useUpdateParticipant } from "../../../api/participants";
import { useDeleteParticipantComments } from "../../../api/comments";
import { useCoaches } from "../../../api/coaches";
import { usePersons } from "../../../api/persons";
import { useTimeSlots } from "../../../api/timeSlots";
import { ApiError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { DeleteConfirmModal } from "../../../components/DeleteConfirmModal";
import { CommentSuggestionList, type SuggestionApplied } from "./CommentSuggestionList";
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
  // B17 (v0.6.0 audit-fix batch B, P1): Mantine's Drawer calls `onClose` for BOTH an overlay click
  // and Escape, same as the "Stäng" button below calling it directly - all three need to go through
  // the SAME unsaved-changes guard. That guard's state (isDirty, handleSave, discard) lives inside
  // ParticipantDrawerBody (only mounted while a participant is selected), so this is a "latest ref"
  // (same pattern as Body's own customDraftRef/originalCustomRef): Body keeps it pointed at its
  // current `attemptClose` every render; the Drawer just calls through it. The no-op default is only
  // ever reachable before a participant is first selected (Drawer isn't open yet, so its onClose
  // can't fire) or during the unmount tick right after Body itself has already called onClose.
  const requestCloseRef: MutableRefObject<() => void> = useRef(() => {});

  return (
    <Drawer
      opened={participant !== null}
      onClose={() => requestCloseRef.current()}
      position="right"
      size="xl"
      title={
        <Group gap="xs">
          {/* Drawer.Title already renders an h2 internally - a nested <Title> here would be a
              second heading for the same text. Text fw={600} at the h4 font size keeps the
              pre-WP3 visual weight without the extra heading. */}
          <Text fw={600} style={{ fontSize: "var(--mantine-h4-font-size)" }}>
            {participant?.name ?? ""}
          </Text>
          {participant?.reviewedDone && (
            <Badge color="green" variant="light" leftSection={<IconCircleCheck size={14} color="var(--mantine-color-green-6)" />}>
              {sv.participants.drawer.doneIndicator}
            </Badge>
          )}
        </Group>
      }
    >
      {participant && (
        <ParticipantDrawerBody
          key={participant.id}
          planId={planId}
          participant={participant}
          allParticipants={allParticipants}
          onClose={onClose}
          requestCloseRef={requestCloseRef}
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
  /** B17: the outer ParticipantDrawer's "latest ref" the Drawer's own overlay-click/Escape onClose
   *  calls through - see its doc comment above. */
  requestCloseRef: MutableRefObject<() => void>;
}

function ParticipantDrawerBody({ planId, participant, allParticipants, onClose, requestCloseRef }: ParticipantDrawerBodyProps) {
  const fieldDefinitions = useFieldDefinitions(planId);
  const fieldValues = useParticipantFieldValues(planId, participant.id);
  const updateParticipant = useUpdateParticipant(planId);
  const updateFieldValues = useUpdateParticipantFieldValues(planId, participant.id);
  const deleteComments = useDeleteParticipantComments(planId);
  const coaches = useCoaches(planId);
  const persons = usePersons();
  const timeSlots = useTimeSlots(planId);

  const coachOptions = (coaches.data ?? []).map((coach) => {
    const person = persons.data?.find((candidate) => candidate.id === coach.personId);
    return { id: coach.id, name: person ? person.displayName || `${person.firstName} ${person.lastName}`.trim() : coach.personId };
  });

  const [structuredDraft, setStructuredDraft] = useState<StructuredDraft>(() => structuredDraftFrom(participant));
  const [originalStructured, setOriginalStructured] = useState<StructuredDraft>(() => structuredDraftFrom(participant));
  const [customDraft, setCustomDraft] = useState<Record<string, unknown>>({});
  const [originalCustom, setOriginalCustom] = useState<Record<string, unknown>>({});
  const [deleteCommentsOpen, setDeleteCommentsOpen] = useState(false);
  const [saving, setSaving] = useState(false);

  // Review fix (MAJOR 5): refs, not state, so the resync effect below can read the LATEST
  // customDraft/originalCustom without depending on them (which would re-fire the effect on every
  // keystroke) - kept in sync on every render, a standard "latest ref" pattern.
  const customDraftRef = useRef(customDraft);
  customDraftRef.current = customDraft;
  const originalCustomRef = useRef(originalCustom);
  originalCustomRef.current = originalCustom;

  useEffect(() => {
    if (!fieldValues.data) {
      return;
    }
    // Review fix (MAJOR 5): a field-values PUT triggered from CommentSuggestionList's "Lägg till"
    // invalidates this same query, so this effect re-fires WHILE the user may have unrelated unsaved
    // edits sitting in customDraft. Blindly overwriting both customDraft AND originalCustom here
    // would silently discard those edits. When something is genuinely dirty, skip the resync
    // entirely - CommentSuggestionList's `onApplied` callback (see handleSuggestionApplied below)
    // is what keeps the specific applied field in sync instead, without touching anything else.
    const isDirtyNow = Object.keys(diff(customDraftRef.current, originalCustomRef.current)).length > 0;
    if (isDirtyNow) {
      return;
    }
    const values: Record<string, unknown> = {};
    for (const fv of fieldValues.data) {
      values[fv.key] = fv.value ?? null;
    }
    setCustomDraft(values);
    setOriginalCustom(values);
    // Only re-sync from the server on a genuinely new response for this participant.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fieldValues.data]);

  /** Review fix (MAJOR 5): merges an applied "Tolkningsförslag" change into the SAME drafts the rest
   *  of the drawer edits, so the field/Switch reflects it immediately without clobbering (or being
   *  clobbered by) any other unsaved edit - see {@link SuggestionApplied}. */
  const handleSuggestionApplied = (change: SuggestionApplied) => {
    if (change.kind === "flag") {
      setStructuredDraft((prev) => ({ ...prev, manualReviewFlag: true }));
      setOriginalStructured((prev) => ({ ...prev, manualReviewFlag: true }));
      return;
    }
    setCustomDraft((prev) => ({ ...prev, [change.fieldKey]: change.value }));
    setOriginalCustom((prev) => ({ ...prev, [change.fieldKey]: change.value }));
  };

  const structuredChanges = diff(structuredDraft, originalStructured);
  const customChanges = diff(customDraft, originalCustom);
  const isDirty = Object.keys(structuredChanges).length > 0 || Object.keys(customChanges).length > 0;
  // B18.8 (v0.6.0 audit-fix batch B): whether previousGroupName specifically has an unsaved edit -
  // previousGroupOrderParsed below is always computed from the SAVED value, so it can silently
  // diverge from what's currently typed while this is true.
  const previousGroupNameDirty = Object.prototype.hasOwnProperty.call(structuredChanges, "previousGroupName");

  const hasComment = Boolean(participant.importedComment && participant.importedComment.trim().length > 0);
  const hasInternalNoteOriginally = Boolean(participant.internalNote && participant.internalNote.trim().length > 0);

  const [confirmCloseOpen, setConfirmCloseOpen] = useState(false);

  /**
   * WP3 ("Spara och markera som färdig"): `doneOverride` is applied AFTER the normal save chain
   * (structured-field PATCH, then custom-field PUT) succeeds in full, as its OWN trailing PATCH
   * containing only `{ reviewedDone }` - never folded into the first PATCH. This way a mid-chain
   * failure (e.g. the field-values PUT rejecting a value) never leaves the row falsely
   * "Klarmarkerad": the flag only flips once everything else the button also saved is confirmed
   * persisted. That trailing PATCH is itself revision-bump-exempt (see ParticipantProfileController).
   * Plain "Spara" (no override) never touches the flag - sticky done.
   *
   * B17: returns whether the save actually succeeded, so the unsaved-changes-close guard's own
   * "Spara" action (handleConfirmSave below) knows whether it's safe to also close the drawer.
   */
  const handleSave = async (doneOverride?: boolean): Promise<boolean> => {
    setSaving(true);
    try {
      if (Object.keys(structuredChanges).length > 0) {
        await updateParticipant.mutateAsync({ id: participant.id, body: structuredChanges });
      }
      if (Object.keys(customChanges).length > 0) {
        await updateFieldValues.mutateAsync(customChanges);
      }
      if (doneOverride !== undefined && doneOverride !== participant.reviewedDone) {
        await updateParticipant.mutateAsync({ id: participant.id, body: { reviewedDone: doneOverride } });
      }
      setOriginalStructured(structuredDraft);
      setOriginalCustom(customDraft);
      notifications.show({ color: "green", message: sv.participants.drawer.saveSuccess });
      return true;
    } catch (error) {
      notifications.show({
        color: "red",
        title: sv.common.error,
        message: error instanceof ApiError ? error.message : sv.participants.drawer.saveFailed,
      });
      return false;
    } finally {
      setSaving(false);
    }
  };

  /**
   * B17 (v0.6.0 audit-fix batch B, P1): the single gate every close attempt (the "Stäng" button, an
   * overlay click, and Escape - see requestCloseRef's doc comment on the outer ParticipantDrawer)
   * funnels through. Closes immediately when there's nothing unsaved; otherwise shows the three-way
   * confirmation instead of closing.
   */
  const attemptClose = () => {
    if (isDirty) {
      setConfirmCloseOpen(true);
      return;
    }
    onClose();
  };
  // Latest-ref pattern (same as customDraftRef/originalCustomRef above): isDirty changes on every
  // keystroke, so this is re-pointed every render rather than captured once.
  requestCloseRef.current = attemptClose;

  const handleConfirmSave = async () => {
    const success = await handleSave();
    setConfirmCloseOpen(false);
    if (success) {
      onClose();
    }
    // On failure the save's own red notification already explains why; leave the drawer open (still
    // dirty) so the admin can retry rather than silently discarding their edits.
  };

  const handleConfirmDiscard = () => {
    setStructuredDraft(originalStructured);
    setCustomDraft(originalCustom);
    setConfirmCloseOpen(false);
    onClose();
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
            {/* B18.9 (v0.6.0 audit-fix batch B): only meaningful when there's actually a comment to
                flag as sensitive - previously rendered unconditionally, even with none. */}
            {hasComment && (
              <Badge color="orange" variant="light">
                {sv.participants.drawer.sensitiveBadge}
              </Badge>
            )}
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
            onChange={(event) => {
              // Read the primitive out of the event BEFORE calling setState: React's dev-mode
              // functional-updater purity check can invoke this updater a second time on a later
              // render, by which point the SyntheticEvent's own fields (currentTarget included) have
              // already been released - reading it inside the updater closure crashes intermittently.
              const value = event.currentTarget.value;
              setStructuredDraft((prev) => ({ ...prev, internalNote: value }));
            }}
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

          {hasComment && fieldValues.data && (
            <CommentSuggestionList
              planId={planId}
              participantId={participant.id}
              fieldValues={fieldValues.data}
              fieldValuesFetching={fieldValues.isFetching}
              onApplied={handleSuggestionApplied}
            />
          )}
        </Stack>

        {/* --- Right: structured fields --- */}
        <Stack gap="sm">
          <Title order={5}>{sv.participants.drawer.structuredHeading}</Title>

          <NumberInput
            label={sv.participants.drawer.manualLevelScoreLabel}
            description={sv.participants.drawer.manualLevelScoreDescription}
            value={structuredDraft.manualLevelScore ?? ""}
            onChange={(value) =>
              setStructuredDraft((prev) => ({ ...prev, manualLevelScore: value === "" ? null : Number(value) }))
            }
          />
          <TextInput
            label={sv.participants.drawer.previousGroupNameLabel}
            value={structuredDraft.previousGroupName ?? ""}
            onChange={(event) => {
              // See internalNoteLabel's Textarea above for why the value is read out here, not
              // inside the updater closure.
              const value = event.currentTarget.value;
              setStructuredDraft((prev) => ({ ...prev, previousGroupName: value === "" ? null : value }));
            }}
          />
          {/* v0.6.0 F4 (M-S4): the solver's own trailing-integer parse of previousGroupName, when the
              backend exposes it (see api/types.ts's ParticipantProfile doc - additive/nullable ahead
              of typegen catching up). Neither field is editable here - it's a read-only hint about
              what the continuity constraint will actually use for the CURRENTLY SAVED value, not the
              draft above (matches the backend's own re-parse-on-solve timing). */}
          {participant.previousGroupOrder != null && (
            <Text size="xs" c="dimmed" data-testid="previous-group-order-hint">
              {sv.participants.drawer.previousGroupOrderParsed(participant.previousGroupOrder)}
              {/* B18.8: only while previousGroupName has an unsaved edit - otherwise the hint's own
                  "parsed from the saved value" framing is implicit and this would be noise. */}
              {previousGroupNameDirty && ` ${sv.participants.drawer.previousGroupSavedValueNote}`}
            </Text>
          )}
          {participant.previousGroupParseWarning && (
            <Text size="xs" c="orange" data-testid="previous-group-parse-warning">
              {participant.previousGroupParseWarning}
            </Text>
          )}
          <NumberInput
            label={sv.participants.drawer.previousGroupLevelLabel}
            value={structuredDraft.previousGroupLevel ?? ""}
            onChange={(value) =>
              setStructuredDraft((prev) => ({ ...prev, previousGroupLevel: value === "" ? null : Number(value) }))
            }
          />
          <Switch
            label={sv.participants.drawer.waitlistedLabel}
            description={sv.participants.drawer.waitlistedDescription}
            checked={structuredDraft.waitlisted}
            onChange={(event) => {
              // See internalNoteLabel's Textarea above for why the value is read out here, not
              // inside the updater closure.
              const checked = event.currentTarget.checked;
              setStructuredDraft((prev) => ({ ...prev, waitlisted: checked }));
            }}
          />
          <Switch
            label={sv.participants.drawer.manualReviewFlagLabel}
            description={sv.participants.drawer.manualReviewFlagDescription}
            checked={structuredDraft.manualReviewFlag}
            onChange={(event) => {
              const checked = event.currentTarget.checked;
              setStructuredDraft((prev) => ({ ...prev, manualReviewFlag: checked }));
            }}
          />
        </Stack>
      </SimpleGrid>

      <Divider />

      <Title order={5}>{sv.participants.drawer.customFieldsHeading}</Title>

      {(fieldValues.isLoading || timeSlots.isLoading) && <Loader size="sm" />}
      {fieldValues.isError && (
        <Alert color="red">
          {fieldValues.error instanceof ApiError ? fieldValues.error.message : sv.participants.drawer.fieldValuesSaveFailed}
        </Alert>
      )}

      {/* Wait for time slots too: rendering a timeRelation editor before the slot list arrives
          would momentarily show valid stored values as "invalid" (filtered + dimmed note). */}
      {/* B18.10 (v0.6.0 audit-fix batch B): this used to be wrapped in a ScrollArea.Autosize with a
          fixed mah={320}, which clipped its own inner region separately from the drawer's outer
          scroll - a walkthrough-proven overlap bug. No inner scroll region anymore; the whole
          Drawer.Body (Mantine's own overflow-y: auto) scrolls as one. */}
      {fieldValues.data && !timeSlots.isLoading && (
        <SimpleGrid cols={2} spacing="md">
          {fieldValues.data.map((fv) => (
            <CustomFieldEditor
              key={fv.key}
              fieldValue={fv}
              definition={fieldDefinitions.data?.find((def) => def.id === fv.fieldDefinitionId)}
              value={customDraft[fv.key] ?? null}
              onChange={(value) => setCustomDraft((prev) => ({ ...prev, [fv.key]: value }))}
              participants={allParticipants}
              coaches={coachOptions}
              timeSlots={timeSlots.data ?? []}
              selfId={participant.id}
            />
          ))}
        </SimpleGrid>
      )}

      <Group justify="flex-end">
        <Button variant="default" onClick={attemptClose}>
          {sv.participants.drawer.closeButton}
        </Button>
        <Button onClick={() => handleSave()} disabled={!isDirty} loading={saving}>
          {sv.participants.drawer.saveButton}
        </Button>
        {participant.reviewedDone ? (
          <Button variant="subtle" onClick={() => handleSave(false)} loading={saving}>
            {sv.participants.drawer.unmarkDoneButton}
          </Button>
        ) : (
          <Button color="green" onClick={() => handleSave(true)} loading={saving}>
            {sv.participants.drawer.saveAndMarkDoneButton}
          </Button>
        )}
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

      {/* B17 (v0.6.0 audit-fix batch B, P1): the unsaved-changes close guard - see attemptClose's own
          doc comment above for what funnels into this. Three actions, not the usual confirm/cancel
          pair: Spara (save then close), Släng ändringar (discard then close), Fortsätt redigera
          (cancel - stays open, no data change). */}
      <Modal
        opened={confirmCloseOpen}
        onClose={() => setConfirmCloseOpen(false)}
        title={sv.participants.drawer.unsavedChangesModal.title}
        centered
      >
        <Text mb="lg">{sv.participants.drawer.unsavedChangesModal.message}</Text>
        <Group justify="flex-end">
          <Button variant="default" onClick={() => setConfirmCloseOpen(false)}>
            {sv.participants.drawer.unsavedChangesModal.continueEditing}
          </Button>
          <Button color="red" variant="outline" onClick={handleConfirmDiscard}>
            {sv.participants.drawer.unsavedChangesModal.discard}
          </Button>
          <Button onClick={() => void handleConfirmSave()} loading={saving}>
            {sv.participants.drawer.unsavedChangesModal.save}
          </Button>
        </Group>
      </Modal>
    </Stack>
  );
}
