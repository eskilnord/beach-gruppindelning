import type { SlotBlocksView, TrainingBlockView, TrainingGroup } from "../../../api/types";

export interface ScheduleCourt {
  id: string;
  name: string;
}

export type ScheduleCell =
  | { kind: "group"; blockId: string; groupId: string; groupName: string; coachName: string | null }
  | { kind: "empty"; blockId: string }
  | { kind: "inactive"; blockId: string }
  /** No TrainingBlock exists for this (time slot, court) pair at all - e.g. a slot that declared
   *  fewer courts than another slot in the same plan. Rendered as a blank cell, distinct from
   *  "empty" (a real, active block with nobody in it yet - spec §19.11's "Ledig"). */
  | { kind: "none" };

export interface ScheduleRow {
  timeSlotId: string;
  label: string;
  cells: ScheduleCell[];
}

export interface ScheduleGrid {
  courts: ScheduleCourt[];
  rows: ScheduleRow[];
}

/**
 * Planeringskarta cell mapping (spec §19.11): rows = time slots (already chronologically ordered by
 * the backend, see TrainingBlockRepository/TimeSlotRepository javadoc - "Tid" column), columns =
 * every court referenced by any block in the plan (sorted, natural numeric order so "Bana 10" sorts
 * after "Bana 2"). Each cell is exactly one of: a group ("Herr 3 / Anna L" - spec's own worked
 * example), "Ledig" (an active block nobody is assigned to yet), "Inaktiverad" (a §12.3 manually or
 * shrink-deactivated block), or blank (this court simply has no block at this time slot).
 *
 * Pure/hand-rolled (no grid library, per the milestone brief) so it is trivially unit-testable
 * without React or a running backend.
 */
export function buildScheduleGrid(
  slotBlocks: SlotBlocksView[],
  groups: TrainingGroup[],
  coachNameByGroupId: Record<string, string | null | undefined>,
): ScheduleGrid {
  const courtNameById = new Map<string, string>();
  for (const slot of slotBlocks) {
    for (const block of slot.blocks) {
      courtNameById.set(block.courtId, block.courtName);
    }
  }
  const courts: ScheduleCourt[] = [...courtNameById.entries()]
    .map(([id, name]) => ({ id, name }))
    .sort((a, b) => a.name.localeCompare(b.name, "sv", { numeric: true }));

  const groupByBlockId = new Map<string, TrainingGroup>();
  for (const group of groups) {
    if (group.assignedTrainingBlockId) {
      groupByBlockId.set(group.assignedTrainingBlockId, group);
    }
  }

  const rows: ScheduleRow[] = slotBlocks.map((slot) => {
    const blockByCourtId = new Map<string, TrainingBlockView>(slot.blocks.map((b) => [b.courtId, b]));
    const cells: ScheduleCell[] = courts.map((court): ScheduleCell => {
      const block = blockByCourtId.get(court.id);
      if (!block) {
        return { kind: "none" };
      }
      if (!block.active) {
        return { kind: "inactive", blockId: block.id };
      }
      const group = groupByBlockId.get(block.id);
      if (!group) {
        return { kind: "empty", blockId: block.id };
      }
      return {
        kind: "group",
        blockId: block.id,
        groupId: group.id,
        groupName: group.name,
        coachName: coachNameByGroupId[group.id] ?? null,
      };
    });
    return { timeSlotId: slot.timeSlot.id, label: slot.timeSlot.label, cells };
  });

  return { courts, rows };
}
