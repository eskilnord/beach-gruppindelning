import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { Alert, Badge, Button, Card, Group, Loader, NumberInput, Stack, Switch, Text, Title } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { useTrainingBlocksForPlan, useSetCourts, useUpdateTrainingBlockActive } from "../../../api/trainingBlocks";
import { useDeleteTimeSlot } from "../../../api/timeSlots";
import { ApiError } from "../../../api/client";
import { sv } from "../../../i18n/sv";
import { DeleteConfirmModal } from "../../../components/DeleteConfirmModal";
import { HelpTip } from "../../../components/HelpTip";
import { TimeSlotModal } from "./TimeSlotModal";
import type { SlotBlocksView, TimeSlot, TrainingBlockView } from "../../../api/types";

interface BlockChipProps {
  block: TrainingBlockView;
  onToggle: (active: boolean) => void;
  pending: boolean;
}

function BlockChip({ block, onToggle, pending }: BlockChipProps) {
  return (
    <Card
      withBorder
      padding="xs"
      radius="md"
      style={{ opacity: block.active ? 1 : 0.55, minWidth: 120 }}
      data-testid="block-chip"
    >
      <Stack gap={4} align="center">
        <Text fw={500} size="sm">
          {block.courtName}
        </Text>
        {!block.active && (
          <Badge size="xs" color="gray" variant="light">
            {sv.resources.inactiveBadge}
          </Badge>
        )}
        <Switch
          size="sm"
          checked={block.active}
          disabled={pending}
          onChange={(event) => onToggle(event.currentTarget.checked)}
          aria-label={`${block.courtName} aktiv`}
        />
      </Stack>
    </Card>
  );
}

interface SlotRowProps {
  planId: string;
  entry: SlotBlocksView;
  onEdit: (slot: TimeSlot) => void;
  onDelete: (slot: TimeSlot) => void;
}

function SlotRow({ planId, entry, onEdit, onDelete }: SlotRowProps) {
  const setCourts = useSetCourts(planId);
  const updateBlockActive = useUpdateTrainingBlockActive(planId);
  const [courtsDraft, setCourtsDraft] = useState<number | "">(entry.blocks.length);

  useEffect(() => setCourtsDraft(entry.blocks.length), [entry.blocks.length]);

  const commitCourts = () => {
    if (courtsDraft === "" || courtsDraft === entry.blocks.length) {
      return;
    }
    setCourts.mutate(
      { slotId: entry.timeSlot.id, count: Number(courtsDraft) },
      {
        onError: (error) => {
          setCourtsDraft(entry.blocks.length);
          notifications.show({
            color: "red",
            title: sv.common.error,
            message: error instanceof ApiError ? error.message : sv.resources.courtsUpdateFailed,
          });
        },
      },
    );
  };

  const toggleBlock = (block: TrainingBlockView, active: boolean) => {
    updateBlockActive.mutate(
      { id: block.id, active },
      {
        onError: (error) => {
          notifications.show({
            color: "red",
            title: sv.common.error,
            message: error instanceof ApiError ? error.message : sv.resources.blockActiveUpdateFailed,
          });
        },
      },
    );
  };

  const activeCount = entry.blocks.filter((block) => block.active).length;

  return (
    <Card withBorder padding="md" data-testid="time-slot-row">
      <Group justify="space-between" align="flex-start" wrap="wrap">
        <div>
          <Text fw={600}>{entry.timeSlot.label}</Text>
          <Text size="xs" c="dimmed">
            {sv.resources.blocksCount(activeCount)}
            {activeCount !== entry.blocks.length ? ` (${entry.blocks.length} totalt)` : ""}
          </Text>
        </div>
        <Group gap="xs">
          <Button size="xs" variant="default" onClick={() => onEdit(entry.timeSlot)}>
            {sv.resources.editButton}
          </Button>
          <Button size="xs" variant="default" color="red" onClick={() => onDelete(entry.timeSlot)}>
            {sv.resources.deleteButton}
          </Button>
        </Group>
      </Group>

      <Group mt="sm" gap="xs" align="flex-end">
        <NumberInput
          label={sv.resources.courtsLabel}
          description={<HelpTip label={sv.help.resources.courtsAriaLabel}>{sv.help.resources.courts}</HelpTip>}
          min={0}
          max={60}
          w={140}
          value={courtsDraft}
          disabled={setCourts.isPending}
          onChange={(value) => setCourtsDraft(value === "" ? "" : Number(value))}
          onBlur={commitCourts}
        />
      </Group>

      {entry.blocks.length > 0 && (
        <>
          <Group gap={4} mt="md" mb={4}>
            <Text size="xs" fw={600} c="dimmed">
              {sv.resources.blocksHeading}
            </Text>
            <HelpTip label={sv.help.ariaLabel(sv.resources.blocksHeading)}>{sv.help.resources.courtActive}</HelpTip>
          </Group>
          <Group gap="sm" wrap="wrap">
            {entry.blocks.map((block) => (
              <BlockChip
                key={block.id}
                block={block}
                pending={updateBlockActive.isPending}
                onToggle={(active) => toggleBlock(block, active)}
              />
            ))}
          </Group>
        </>
      )}
    </Card>
  );
}

/**
 * Resursvy (spec §19.6/§12): time slots with a live "Antal banor" -> TrainingBlock count, and a
 * per-block active toggle for manual exceptions (spec §12.3, e.g. "Bana 4 är inte tillgänglig
 * 21.00–22.30"). Reads the grouped GET .../training-blocks view (slot + its blocks together) as the
 * single source of truth for the list, rather than separately fetching time slots and blocks.
 */
export function ResourcesPanel() {
  const { planId } = useParams<{ planId: string }>();
  const blocksByPlan = useTrainingBlocksForPlan(planId);
  const deleteSlot = useDeleteTimeSlot(planId ?? "");

  const [modalOpen, setModalOpen] = useState(false);
  const [editingSlot, setEditingSlot] = useState<TimeSlot | null>(null);
  const [deletingSlot, setDeletingSlot] = useState<TimeSlot | null>(null);

  if (blocksByPlan.isLoading) {
    return <Loader size="sm" />;
  }
  if (blocksByPlan.isError) {
    return (
      <Alert color="red">
        {blocksByPlan.error instanceof ApiError ? blocksByPlan.error.message : sv.resources.loadFailed}
      </Alert>
    );
  }

  const entries = blocksByPlan.data ?? [];
  const isEmpty = entries.length === 0;

  return (
    <Card withBorder padding="lg">
      <Group justify="space-between" mb="sm">
        <Title order={4}>{sv.resources.heading}</Title>
        <Button
          onClick={() => {
            setEditingSlot(null);
            setModalOpen(true);
          }}
        >
          {sv.resources.newSlotButton}
        </Button>
      </Group>

      {isEmpty && <Text c="dimmed">{sv.resources.empty}</Text>}

      {!isEmpty && (
        <>
          <Stack gap="md">
            {entries.map((entry) => (
              <SlotRow
                key={entry.timeSlot.id}
                planId={planId!}
                entry={entry}
                onEdit={(slot) => {
                  setEditingSlot(slot);
                  setModalOpen(true);
                }}
                onDelete={(slot) => setDeletingSlot(slot)}
              />
            ))}
          </Stack>

          <Text size="xs" c="dimmed" mt="md">
            {sv.resources.exceptionHint}
          </Text>
        </>
      )}

      {planId && (
        <TimeSlotModal
          planId={planId}
          opened={modalOpen}
          slot={editingSlot}
          onClose={() => {
            setModalOpen(false);
            setEditingSlot(null);
          }}
        />
      )}

      <DeleteConfirmModal
        opened={deletingSlot !== null}
        title={sv.resources.deleteModal.title}
        message={deletingSlot ? sv.resources.deleteModal.message(deletingSlot.label) : ""}
        confirmLabel={sv.resources.deleteModal.confirm}
        loading={deleteSlot.isPending}
        onClose={() => setDeletingSlot(null)}
        onConfirm={() => {
          if (!deletingSlot) return;
          deleteSlot.mutate(deletingSlot.id, {
            onSuccess: () => setDeletingSlot(null),
            onError: (error) => {
              notifications.show({
                color: "red",
                title: sv.common.error,
                message: error instanceof ApiError ? error.message : sv.resources.deleteModal.failed,
              });
            },
          });
        }}
      />
    </Card>
  );
}
