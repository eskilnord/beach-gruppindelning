import { describe, expect, it } from "vitest";
import { canDeleteSavedPlan, legalNextStatuses, statusActions, statusColor } from "./savedPlanActions";

describe("legalNextStatuses", () => {
  it("draft -> saved only", () => {
    expect(legalNextStatuses("draft")).toEqual(["saved"]);
  });

  it("saved -> locked or archived", () => {
    expect(legalNextStatuses("saved")).toEqual(["locked", "archived"]);
  });

  it("locked -> published or archived", () => {
    expect(legalNextStatuses("locked")).toEqual(["published", "archived"]);
  });

  it("published -> archived only", () => {
    expect(legalNextStatuses("published")).toEqual(["archived"]);
  });

  it("archived is terminal (no legal next status)", () => {
    expect(legalNextStatuses("archived")).toEqual([]);
  });

  it("an unrecognized status has no legal next status", () => {
    expect(legalNextStatuses("something-else")).toEqual([]);
  });
});

describe("statusActions", () => {
  it("renders exactly the legal next steps for saved (Lås / Arkivera), nothing illegal", () => {
    const actions = statusActions("saved");
    expect(actions.map((a) => a.targetStatus)).toEqual(["locked", "archived"]);
    // Never offers going back to draft or straight to published - both are illegal transitions.
    expect(actions.map((a) => a.targetStatus)).not.toContain("draft");
    expect(actions.map((a) => a.targetStatus)).not.toContain("published");
  });

  it("renders exactly the legal next steps for locked (Publicera / Arkivera)", () => {
    expect(statusActions("locked").map((a) => a.targetStatus)).toEqual(["published", "archived"]);
  });

  it("renders no actions for a terminal archived plan", () => {
    expect(statusActions("archived")).toEqual([]);
  });

  it("pairs each action with a color matching that status's badge color", () => {
    const actions = statusActions("saved");
    for (const action of actions) {
      expect(action.color).toBe(statusColor(action.targetStatus));
    }
  });
});

describe("canDeleteSavedPlan", () => {
  it("allows delete for draft and saved", () => {
    expect(canDeleteSavedPlan("draft")).toBe(true);
    expect(canDeleteSavedPlan("saved")).toBe(true);
  });

  it("forbids delete once locked, published, or archived", () => {
    expect(canDeleteSavedPlan("locked")).toBe(false);
    expect(canDeleteSavedPlan("published")).toBe(false);
    expect(canDeleteSavedPlan("archived")).toBe(false);
  });
});
