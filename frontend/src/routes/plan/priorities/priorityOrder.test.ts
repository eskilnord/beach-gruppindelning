import { describe, expect, it } from "vitest";
import { arraysEqual, isPermutation, moveItem, reorder } from "./priorityOrder";

describe("moveItem", () => {
  const list = ["A", "B", "C", "D"];

  it("moves an item up (earlier) by swapping with its predecessor", () => {
    expect(moveItem(list, 1, "up")).toEqual(["B", "A", "C", "D"]);
  });

  it("moves an item down (later) by swapping with its successor", () => {
    expect(moveItem(list, 1, "down")).toEqual(["A", "C", "B", "D"]);
  });

  it("returns the SAME reference (no-op) when moving the first item up", () => {
    const result = moveItem(list, 0, "up");
    expect(result).toBe(list);
  });

  it("returns the SAME reference (no-op) when moving the last item down", () => {
    const result = moveItem(list, 3, "down");
    expect(result).toBe(list);
  });

  it("returns the SAME reference for an out-of-range index", () => {
    expect(moveItem(list, -1, "up")).toBe(list);
    expect(moveItem(list, 4, "down")).toBe(list);
  });

  it("does not mutate the input array", () => {
    const copy = [...list];
    moveItem(list, 1, "up");
    expect(list).toEqual(copy);
  });
});

describe("reorder", () => {
  const list = ["A", "B", "C", "D"];

  it("moves an item from one index to another, shifting the items in between", () => {
    expect(reorder(list, 0, 2)).toEqual(["B", "C", "A", "D"]);
    expect(reorder(list, 3, 0)).toEqual(["D", "A", "B", "C"]);
  });

  it("returns the SAME reference (no-op) when fromIndex equals toIndex", () => {
    expect(reorder(list, 1, 1)).toBe(list);
  });

  it("returns the SAME reference for an out-of-range index", () => {
    expect(reorder(list, -1, 2)).toBe(list);
    expect(reorder(list, 1, 9)).toBe(list);
  });

  it("does not mutate the input array", () => {
    const copy = [...list];
    reorder(list, 0, 2);
    expect(list).toEqual(copy);
  });
});

describe("isPermutation", () => {
  const reference = ["TRAIN_TOGETHER", "PREVIOUS_GROUP", "PREFERRED_TIME", "LEVEL"];

  it("true for any reordering of the same elements", () => {
    expect(isPermutation(["LEVEL", "TRAIN_TOGETHER", "PREFERRED_TIME", "PREVIOUS_GROUP"], reference)).toBe(true);
    expect(isPermutation(reference, reference)).toBe(true);
  });

  it("false when an element is missing", () => {
    expect(isPermutation(["TRAIN_TOGETHER", "PREVIOUS_GROUP", "PREFERRED_TIME"], reference)).toBe(false);
  });

  it("false when an element is duplicated in place of another", () => {
    expect(isPermutation(["TRAIN_TOGETHER", "TRAIN_TOGETHER", "PREFERRED_TIME", "LEVEL"], reference)).toBe(false);
  });

  it("false on a different length", () => {
    expect(isPermutation([...reference, "LEVEL"], reference)).toBe(false);
  });
});

describe("arraysEqual", () => {
  const reference = ["TRAIN_TOGETHER", "PREVIOUS_GROUP", "PREFERRED_TIME", "LEVEL"];

  it("true for the same order", () => {
    expect(arraysEqual(reference, [...reference])).toBe(true);
  });

  it("false for a different order of the same elements (order-sensitive, unlike isPermutation)", () => {
    expect(arraysEqual(reference, ["PREVIOUS_GROUP", "TRAIN_TOGETHER", "PREFERRED_TIME", "LEVEL"])).toBe(false);
  });

  it("false on a different length", () => {
    expect(arraysEqual(reference, reference.slice(0, 3))).toBe(false);
  });
});
