import type { ComponentProps } from "react";
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MantineProvider } from "@mantine/core";
import { sv } from "../../../i18n/sv";
import { PriorityRankList } from "./PriorityRankList";
import type { PriorityKey, PriorityRowView } from "../../../api/priorityOrder";

const ORDER: PriorityKey[] = ["TRAIN_TOGETHER", "PREVIOUS_GROUP", "PREFERRED_TIME", "LEVEL"];

const PRIORITIES: PriorityRowView[] = [
  { key: "TRAIN_TOGETHER", rank: 1, labelSv: "Träna tillsammans", summarySv: "s1", constraintKeys: [], weights: {}, enabled: true },
  { key: "PREVIOUS_GROUP", rank: 2, labelSv: "Fortsätta i samma grupp", summarySv: "s2", constraintKeys: [], weights: {}, enabled: true },
  { key: "PREFERRED_TIME", rank: 3, labelSv: "Önskad tid", summarySv: "s3", constraintKeys: [], weights: {}, enabled: true },
  { key: "LEVEL", rank: 4, labelSv: "Jämn nivå", summarySv: "s4", constraintKeys: [], weights: {}, enabled: true },
];

function renderList(overrides: Partial<ComponentProps<typeof PriorityRankList>> = {}) {
  const onMove = overrides.onMove ?? vi.fn();
  const onReorder = overrides.onReorder ?? vi.fn();
  render(
    <MantineProvider>
      <PriorityRankList order={ORDER} priorities={PRIORITIES} onMove={onMove} onReorder={onReorder} {...overrides} />
    </MantineProvider>,
  );
  return { onMove, onReorder };
}

function rowAt(index: number) {
  return screen.getAllByTestId("priority-row")[index];
}

/** Minimal DataTransfer stand-in: jsdom has no native DataTransfer, but fireEvent just assigns
 *  whatever object is passed as `dataTransfer` straight onto the synthetic event - a plain mock
 *  with the two members PriorityRankList actually touches is enough. */
function fakeDataTransfer() {
  return { setData: vi.fn(), effectAllowed: "" };
}

describe("PriorityRankList", () => {
  it("renders each row with the backend's labelSv as title and the frontend explanation, ranked 1..4", () => {
    renderList();

    ORDER.forEach((key, index) => {
      const row = rowAt(index);
      expect(row).toHaveAttribute("data-priority-key", key);
      expect(within(row).getByText(PRIORITIES[index].labelSv)).toBeInTheDocument();
      expect(within(row).getByText(sv.simple.priorities.explanations[key])).toBeInTheDocument();
      expect(within(row).getByText(String(index + 1))).toBeInTheDocument();
    });
  });

  it("disables the up arrow on the first row and the down arrow on the last row", () => {
    renderList();

    const firstRow = rowAt(0);
    expect(within(firstRow).getByRole("button", { name: sv.simple.priorities.moveUpAriaLabel("Träna tillsammans") })).toBeDisabled();
    expect(within(firstRow).getByRole("button", { name: sv.simple.priorities.moveDownAriaLabel("Träna tillsammans") })).toBeEnabled();

    const lastRow = rowAt(3);
    expect(within(lastRow).getByRole("button", { name: sv.simple.priorities.moveUpAriaLabel("Jämn nivå") })).toBeEnabled();
    expect(within(lastRow).getByRole("button", { name: sv.simple.priorities.moveDownAriaLabel("Jämn nivå") })).toBeDisabled();
  });

  it("clicking an arrow calls onMove with the row's index and direction", async () => {
    const user = userEvent.setup();
    const { onMove } = renderList();

    const secondRow = rowAt(1);
    await user.click(within(secondRow).getByRole("button", { name: sv.simple.priorities.moveUpAriaLabel("Fortsätta i samma grupp") }));
    expect(onMove).toHaveBeenCalledWith(1, "up");

    await user.click(within(secondRow).getByRole("button", { name: sv.simple.priorities.moveDownAriaLabel("Fortsätta i samma grupp") }));
    expect(onMove).toHaveBeenCalledWith(1, "down");
  });

  it("disables every arrow and marks the list aria-disabled when `disabled` is true", () => {
    renderList({ disabled: true });

    screen.getAllByTestId("priority-row").forEach((row) => {
      within(row)
        .getAllByRole("button")
        .forEach((button) => expect(button).toBeDisabled());
    });
    expect(screen.getByTestId("priority-rank-list")).toHaveAttribute("aria-disabled", "true");
  });

  // v0.6.0 F3 review fix (FIX 9, MINOR, drag-down off-by-one): the insertion marker renders ABOVE
  // the target row (border-top - "insert before this row"). Dropping DOWNWARD (source index <
  // target index) must therefore reorder onto `target - 1`, not the raw target index - otherwise the
  // item lands one slot past where the marker showed it would (see PriorityRankList.tsx's onDrop
  // doc comment for why removing the source item first shifts the target's own index down by one).
  it("dragging a row DOWNWARD onto a later row calls onReorder with the target index minus one", () => {
    const { onReorder } = renderList();

    const dataTransfer = fakeDataTransfer();
    const source = rowAt(0);
    const target = rowAt(2);

    fireEvent.dragStart(source, { dataTransfer });
    fireEvent.dragOver(target, { dataTransfer });
    fireEvent.drop(target, { dataTransfer });

    expect(onReorder).toHaveBeenCalledWith(0, 1);
  });

  // Dragging UPWARD has no such shift (nothing before the source index moves when it's removed), so
  // the raw target index is correct as-is - this pins that the fix above only adjusts the downward
  // case, not both.
  it("dragging a row UPWARD onto an earlier row calls onReorder with the target index unchanged", () => {
    const { onReorder } = renderList();

    const dataTransfer = fakeDataTransfer();
    const source = rowAt(2);
    const target = rowAt(0);

    fireEvent.dragStart(source, { dataTransfer });
    fireEvent.dragOver(target, { dataTransfer });
    fireEvent.drop(target, { dataTransfer });

    expect(onReorder).toHaveBeenCalledWith(2, 0);
  });

  it("dropping a row onto itself does not call onReorder", () => {
    const { onReorder } = renderList();

    const dataTransfer = fakeDataTransfer();
    const row = rowAt(1);

    fireEvent.dragStart(row, { dataTransfer });
    fireEvent.dragOver(row, { dataTransfer });
    fireEvent.drop(row, { dataTransfer });

    expect(onReorder).not.toHaveBeenCalled();
  });

  it("rows are not draggable when `disabled` is true", () => {
    renderList({ disabled: true });

    screen.getAllByTestId("priority-row").forEach((row) => {
      expect(row).toHaveAttribute("draggable", "false");
    });
  });

  // v0.6.0 F3 review fix (a11y, FIX 10 MINOR): Mantine's Stack/Paper render plain <div>s with no
  // list semantics of their own.
  it("exposes list/listitem roles", () => {
    renderList();

    expect(screen.getByTestId("priority-rank-list")).toHaveAttribute("role", "list");
    screen.getAllByTestId("priority-row").forEach((row) => expect(row).toHaveAttribute("role", "listitem"));
  });

  describe("enabled: false (FIX 5, MAJOR)", () => {
    it("renders an enabled row without the disabled note, at full opacity, with a filled rank badge", () => {
      renderList();

      const row = rowAt(0);
      expect(row).toHaveAttribute("data-priority-enabled", "true");
      expect(within(row).queryByTestId("priority-row-disabled-note")).not.toBeInTheDocument();
    });

    it("renders a row backed by a disabled constraint dimmed, with a neutral rank badge and the Swedish note - never hidden", () => {
      const priorities = PRIORITIES.map((row) => (row.key === "LEVEL" ? { ...row, enabled: false } : row));
      renderList({ priorities });

      const rows = screen.getAllByTestId("priority-row");
      expect(rows).toHaveLength(4); // still rendered, just dimmed - never hidden.

      const disabledRow = rows.find((row) => row.getAttribute("data-priority-key") === "LEVEL")!;
      expect(disabledRow).toHaveAttribute("data-priority-enabled", "false");
      expect(within(disabledRow).getByTestId("priority-row-disabled-note")).toHaveTextContent(
        sv.simple.priorities.disabledRuleNote,
      );

      const otherRows = rows.filter((row) => row.getAttribute("data-priority-key") !== "LEVEL");
      otherRows.forEach((row) => {
        expect(row).toHaveAttribute("data-priority-enabled", "true");
        expect(within(row).queryByTestId("priority-row-disabled-note")).not.toBeInTheDocument();
      });
    });
  });

  // v0.6.0 audit batch D (D3): the static, per-POSITION sentence rendered under each row.
  it("renders sv.simple.priorities.rankMeaning[index] under each row, keyed by POSITION not priority", () => {
    renderList();

    sv.simple.priorities.rankMeaning.forEach((sentence, index) => {
      expect(within(rowAt(index)).getByTestId("priority-rank-meaning")).toHaveTextContent(sentence);
    });
  });

  it("the per-position sentence moves WITH the row when it changes position", () => {
    const { rerender } = render(
      <MantineProvider>
        <PriorityRankList order={ORDER} priorities={PRIORITIES} onMove={vi.fn()} onReorder={vi.fn()} />
      </MantineProvider>,
    );

    // LEVEL starts at index 3 (rank 4) - "Vägs in sist."
    expect(within(rowAt(3)).getByTestId("priority-rank-meaning")).toHaveTextContent(sv.simple.priorities.rankMeaning[3]);

    const reordered: PriorityKey[] = ["LEVEL", "TRAIN_TOGETHER", "PREVIOUS_GROUP", "PREFERRED_TIME"];
    rerender(
      <MantineProvider>
        <PriorityRankList order={reordered} priorities={PRIORITIES} onMove={vi.fn()} onReorder={vi.fn()} />
      </MantineProvider>,
    );

    // LEVEL is now at index 0 (rank 1) - the sentence describes the POSITION, so it's now
    // rankMeaning[0] ("Väger tyngst av allt."), not the rankMeaning[3] it had before the move.
    const levelRow = rowAt(0);
    expect(levelRow).toHaveAttribute("data-priority-key", "LEVEL");
    expect(within(levelRow).getByTestId("priority-rank-meaning")).toHaveTextContent(sv.simple.priorities.rankMeaning[0]);
  });

  // v0.6.0 audit batch D (D4): the arrow buttons get tooltips (not just aria-label).
  it("shows a tooltip on the up-arrow matching its accessible name", async () => {
    const user = userEvent.setup();
    renderList();

    const firstRow = rowAt(1);
    const upButton = within(firstRow).getByRole("button", {
      name: sv.simple.priorities.moveUpAriaLabel("Fortsätta i samma grupp"),
    });
    await user.hover(upButton);

    expect(await screen.findAllByText(sv.simple.priorities.moveUpAriaLabel("Fortsätta i samma grupp"))).not.toHaveLength(0);
  });

  // v0.6.0 F3 review fix (a11y, FIX 10 MINOR): clicking the up-arrow on the row that becomes rank 1
  // disables that very button - focus must hand off to the sibling (down) arrow rather than being
  // dropped to <body>.
  it("hands focus to the sibling arrow when the clicked arrow becomes disabled", async () => {
    const user = userEvent.setup();
    let order: PriorityKey[] = [...ORDER];
    const onMove = vi.fn((index: number, direction: "up" | "down") => {
      order = direction === "up" ? swap(order, index, index - 1) : swap(order, index, index + 1);
    });

    const { rerender } = render(
      <MantineProvider>
        <PriorityRankList order={order} priorities={PRIORITIES} onMove={onMove} onReorder={vi.fn()} />
      </MantineProvider>,
    );

    const secondRow = rowAt(1);
    const upButton = within(secondRow).getByRole("button", { name: sv.simple.priorities.moveUpAriaLabel("Fortsätta i samma grupp") });
    await user.click(upButton);

    rerender(
      <MantineProvider>
        <PriorityRankList order={order} priorities={PRIORITIES} onMove={onMove} onReorder={vi.fn()} />
      </MantineProvider>,
    );

    const movedRow = rowAt(0);
    expect(movedRow).toHaveAttribute("data-priority-key", "PREVIOUS_GROUP");
    const downButton = within(movedRow).getByRole("button", {
      name: sv.simple.priorities.moveDownAriaLabel("Fortsätta i samma grupp"),
    });
    expect(downButton).toHaveFocus();
  });
});

function swap<T>(list: readonly T[], a: number, b: number): T[] {
  const next = list.slice();
  [next[a], next[b]] = [next[b], next[a]];
  return next;
}
