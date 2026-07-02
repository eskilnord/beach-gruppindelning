import { describe, expect, it } from "vitest";
import { buildScheduleGrid } from "./scheduleGrid";
import type { SlotBlocksView, TrainingGroup } from "../../../api/types";

function block(id: string, timeSlotId: string, courtId: string, courtName: string, active = true) {
  return { id, timeSlotId, courtId, courtName, activityPlanId: "plan-1", active, locked: false };
}

function slot(id: string, label: string, blocks: ReturnType<typeof block>[]): SlotBlocksView {
  return {
    timeSlot: { id, activityPlanId: "plan-1", startTime: "18:00", endTime: "19:30", durationMinutes: 90, label },
    blocks,
  };
}

function group(id: string, name: string, assignedTrainingBlockId: string | undefined): TrainingGroup {
  return { id, activityPlanId: "plan-1", name, requiredCoachCount: 1, locked: false, assignedTrainingBlockId };
}

// The spec §19.11 worked example:
//   Tid       Bana 1              Bana 2              Bana 3
//   18.00     Herr 3 / Coach A    Dam 2 / Coach B     Ledig
//   19.30     Herr 6 / Coach C    Dam 5 / Coach D     Herr 4 / Coach E
//   21.00     Herr 11 / Coach F   Dam 12 / Coach G    Nybörjare (rendered "empty" here - the real
//                                                      "inaktiverad" case is covered separately below)
describe("buildScheduleGrid", () => {
  it("maps the spec §19.11 worked example: a group cell, an empty (Ledig) cell, courts sorted naturally", () => {
    const slotBlocks: SlotBlocksView[] = [
      slot("slot-1800", "Torsdag 18.00–19.30", [
        block("b1-1", "slot-1800", "court-1", "Bana 1"),
        block("b1-2", "slot-1800", "court-2", "Bana 2"),
      ]),
    ];
    const groups: TrainingGroup[] = [group("g-herr3", "Herr 3", "b1-1")];

    const grid = buildScheduleGrid(slotBlocks, groups, { "g-herr3": "Coach A" });

    expect(grid.courts.map((c) => c.name)).toEqual(["Bana 1", "Bana 2"]);
    expect(grid.rows).toHaveLength(1);
    expect(grid.rows[0].cells[0]).toEqual({
      kind: "group",
      blockId: "b1-1",
      groupId: "g-herr3",
      groupName: "Herr 3",
      coachName: "Coach A",
    });
    expect(grid.rows[0].cells[1]).toEqual({ kind: "empty", blockId: "b1-2" });
  });

  it("marks a deactivated block as inactive (Inaktiverad), even if it once had a group", () => {
    const slotBlocks: SlotBlocksView[] = [
      slot("slot-2100", "Torsdag 21.00–22.30", [block("b3-1", "slot-2100", "court-1", "Bana 1", false)]),
    ];
    const grid = buildScheduleGrid(slotBlocks, [], {});
    expect(grid.rows[0].cells[0]).toEqual({ kind: "inactive", blockId: "b3-1" });
  });

  it("renders a blank cell when a court has no block at all in a given time slot", () => {
    const slotBlocks: SlotBlocksView[] = [
      slot("slot-a", "Torsdag 18.00–19.30", [block("ba-1", "slot-a", "court-1", "Bana 1")]),
      slot("slot-b", "Torsdag 19.30–21.00", [
        block("bb-1", "slot-b", "court-1", "Bana 1"),
        block("bb-2", "slot-b", "court-2", "Bana 2"),
      ]),
    ];
    const grid = buildScheduleGrid(slotBlocks, [], {});

    expect(grid.courts.map((c) => c.name)).toEqual(["Bana 1", "Bana 2"]);
    // slot-a only ever declared "Bana 1" - its "Bana 2" cell has no underlying block.
    expect(grid.rows[0].cells).toEqual([{ kind: "empty", blockId: "ba-1" }, { kind: "none" }]);
  });

  it("sorts courts numerically, not lexicographically ('Bana 2' before 'Bana 10')", () => {
    const slotBlocks: SlotBlocksView[] = [
      slot("slot-a", "Torsdag 18.00–19.30", [
        block("b-10", "slot-a", "court-10", "Bana 10"),
        block("b-2", "slot-a", "court-2", "Bana 2"),
      ]),
    ];
    const grid = buildScheduleGrid(slotBlocks, [], {});
    expect(grid.courts.map((c) => c.name)).toEqual(["Bana 2", "Bana 10"]);
  });

  it("preserves the backend's chronological row order (no client-side re-sorting)", () => {
    const slotBlocks: SlotBlocksView[] = [
      slot("slot-late", "Torsdag 21.00–22.30", [block("b-late", "slot-late", "court-1", "Bana 1")]),
      slot("slot-early", "Torsdag 18.00–19.30", [block("b-early", "slot-early", "court-1", "Bana 1")]),
    ];
    const grid = buildScheduleGrid(slotBlocks, [], {});
    expect(grid.rows.map((r) => r.timeSlotId)).toEqual(["slot-late", "slot-early"]);
  });

  it("falls back to null coachName when no coach is resolved for the group", () => {
    const slotBlocks: SlotBlocksView[] = [
      slot("slot-a", "Torsdag 18.00–19.30", [block("b-1", "slot-a", "court-1", "Bana 1")]),
    ];
    const groups: TrainingGroup[] = [group("g-1", "Nybörjare", "b-1")];
    const grid = buildScheduleGrid(slotBlocks, groups, {});
    expect(grid.rows[0].cells[0]).toMatchObject({ kind: "group", groupName: "Nybörjare", coachName: null });
  });
});
