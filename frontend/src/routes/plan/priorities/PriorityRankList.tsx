import { useEffect, useRef, useState } from "react";
import { ActionIcon, Badge, Group, Paper, Stack, Text, Tooltip } from "@mantine/core";
import { IconArrowDown, IconArrowUp, IconGripVertical } from "@tabler/icons-react";
import type { PriorityKey, PriorityRowView } from "../../../api/priorityOrder";
import { sv } from "../../../i18n/sv";

interface PriorityRankListProps {
  /** The four keys, ranked - index 0 is "most important" (rank 1). */
  order: PriorityKey[];
  /** Backing rows, keyed by `key` - only `labelSv`/`enabled` are read here (single source of truth
   *  for the row TITLE and its enabled state, per this milestone's hard rule). May be empty while the
   *  query is still loading; falls back to the raw key / `enabled: true` in that case, though
   *  PrioritiesPanel never actually renders this list before the query has resolved. */
  priorities: PriorityRowView[];
  /** True while `customWeightsActive` (PrioritiesPanel.tsx) - dims the list and disables every
   *  interaction (arrows AND drag), reflecting that this order is currently advisory-only. */
  disabled?: boolean;
  onMove: (index: number, direction: "up" | "down") => void;
  onReorder: (fromIndex: number, toIndex: number) => void;
}

interface RowButtonRefs {
  up: HTMLButtonElement | null;
  down: HTMLButtonElement | null;
}

/**
 * v0.6.0 F3 (M-S3): the ranked list of Stack<Paper> rows at the heart of the Prioriteringar screen.
 * Arrow buttons are the tested, always-available path; native HTML5 drag-and-drop (no new
 * dependency) is a progressive enhancement on top of the exact same `onMove`/`onReorder` callbacks
 * PrioritiesPanel.tsx wires to its debounced-autosave `commitOrder`.
 *
 * v0.6.0 F3 review fixes (a11y, FIX 10 MINOR): the Stack carries `role="list"` and each row
 * `role="listitem"` (Mantine's Paper/Stack render plain `<div>`s with no list semantics of their
 * own); a click that disables the very arrow just clicked (e.g. moving the top row further up)
 * hands keyboard focus off to its still-enabled sibling arrow instead of letting the browser drop
 * focus to `<body>`.
 */
export function PriorityRankList({ order, priorities, disabled = false, onMove, onReorder }: PriorityRankListProps) {
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const [dragOverIndex, setDragOverIndex] = useState<number | null>(null);

  const rowByKey = new Map(priorities.map((row) => [row.key, row]));

  // a11y focus handoff (FIX 10): remembers the row/direction of the last arrow click so the effect
  // below can tell whether that exact button just became disabled by this render's new `order`, and
  // if so, move focus onto its sibling (still-enabled) arrow in the same row.
  const buttonRefs = useRef(new Map<PriorityKey, RowButtonRefs>());
  const lastInteractionRef = useRef<{ key: PriorityKey; direction: "up" | "down" } | null>(null);

  useEffect(() => {
    const interaction = lastInteractionRef.current;
    if (!interaction) {
      return;
    }
    lastInteractionRef.current = null;
    const newIndex = order.indexOf(interaction.key);
    if (newIndex === -1) {
      return;
    }
    const clickedButtonNowDisabled =
      interaction.direction === "up" ? newIndex === 0 : newIndex === order.length - 1;
    if (!clickedButtonNowDisabled) {
      return;
    }
    const refs = buttonRefs.current.get(interaction.key);
    const sibling = interaction.direction === "up" ? refs?.down : refs?.up;
    sibling?.focus();
  }, [order]);

  const handleMoveClick = (index: number, direction: "up" | "down") => {
    lastInteractionRef.current = { key: order[index], direction };
    onMove(index, direction);
  };

  const clearDragState = () => {
    setDragIndex(null);
    setDragOverIndex(null);
  };

  return (
    <Stack
      gap="xs"
      role="list"
      data-testid="priority-rank-list"
      aria-disabled={disabled || undefined}
      style={disabled ? { opacity: 0.6, pointerEvents: "none" } : undefined}
    >
      {order.map((key, index) => {
        const rowView = rowByKey.get(key);
        const labelSv = rowView?.labelSv ?? key;
        // FIX 5 (MAJOR): a row backed by a currently-disabled constraint (advanced-mode override, see
        // PriorityRowView.enabled's doc comment) still renders - never hidden - but dimmed, with a
        // neutral rank badge and a short note that it doesn't affect optimization right now.
        const rowEnabled = rowView?.enabled ?? true;
        const showInsertionLine = dragOverIndex === index && dragIndex !== null && dragIndex !== index;
        return (
          <Paper
            key={key}
            withBorder
            p="md"
            radius="md"
            role="listitem"
            data-testid="priority-row"
            data-priority-key={key}
            data-priority-enabled={rowEnabled}
            draggable={!disabled}
            style={{
              ...(showInsertionLine ? { borderTop: "3px solid var(--mantine-color-blue-6)" } : undefined),
              ...(rowEnabled ? undefined : { opacity: 0.6 }),
              // v0.6.0 audit batch D (D4): the row is draggable (native HTML5 DnD, above) - `grab`
              // signals that affordance instead of the default pointer cursor implying it's just
              // clickable text.
              cursor: disabled ? undefined : "grab",
            }}
            onDragStart={(event) => {
              setDragIndex(index);
              event.dataTransfer.effectAllowed = "move";
              // Firefox requires data actually be set for the drag to start at all.
              event.dataTransfer.setData("text/plain", key);
            }}
            onDragOver={(event) => {
              if (dragIndex === null) {
                return;
              }
              event.preventDefault();
              setDragOverIndex(index);
            }}
            onDragLeave={() => setDragOverIndex((current) => (current === index ? null : current))}
            onDrop={(event) => {
              event.preventDefault();
              if (dragIndex !== null && dragIndex !== index) {
                // v0.6.0 F3 review fix (FIX 9, MINOR, drag-down off-by-one): the insertion marker
                // above renders on the TARGET row (border-top - "insert before this row"). Moving
                // DOWN (dragIndex < index) removes the source item first, which shifts every index
                // after it (including the target's) back by one - reordering straight onto `index`
                // therefore lands the item one slot too far (after the target, not before it). Moving
                // UP has no such shift (nothing before `dragIndex` moves), so only the downward case
                // needs the target-1 adjustment for the drop to land exactly where the marker shows.
                const targetIndex = dragIndex < index ? index - 1 : index;
                onReorder(dragIndex, targetIndex);
              }
              clearDragState();
            }}
            onDragEnd={clearDragState}
          >
            <Group justify="space-between" wrap="nowrap">
              <Group gap="sm" wrap="nowrap" style={{ minWidth: 0 }}>
                {/* v0.6.0 audit batch D (D4): a visual drag-handle affordance, decorative only (the
                    whole row is already draggable and the arrow buttons are the tested, always-
                    available path - see this component's own doc comment) - aria-hidden so it adds
                    nothing to the accessibility tree. */}
                <IconGripVertical
                  size={16}
                  color="var(--mantine-color-dimmed)"
                  aria-hidden
                  style={{ cursor: disabled ? undefined : "grab", flexShrink: 0 }}
                />
                <Badge
                  circle
                  size="lg"
                  // v0.6.0 audit batch D (D4): outline once the list is disabled (customWeightsActive)
                  // - a third, visually distinct state from the normal filled/light split, reinforcing
                  // that this rank number isn't currently authoritative over optimization.
                  variant={disabled ? "outline" : rowEnabled ? "filled" : "light"}
                  color={rowEnabled ? undefined : "gray"}
                >
                  {index + 1}
                </Badge>
                <div style={{ minWidth: 0 }}>
                  <Text fw={600}>{labelSv}</Text>
                  <Text size="sm" c="dimmed">
                    {sv.simple.priorities.explanations[key]}
                  </Text>
                  {/* v0.6.0 audit batch D (D3): static, per-POSITION sentence (moves WITH the row
                      across a reorder, since it's indexed by `index`/rank, never by `key`) - honest by
                      construction: it describes what THIS rank means, not a claim about this specific
                      priority. */}
                  <Text size="xs" c="dimmed" data-testid="priority-rank-meaning">
                    {sv.simple.priorities.rankMeaning[index]}
                  </Text>
                  {!rowEnabled && (
                    // v0.6.0 audit batch D (D4): deliberately NOT `c="dimmed"` any more - stacked on
                    // top of the row's own `opacity: 0.6` (disabled constraint) and, when the whole
                    // list is ALSO disabled (customWeightsActive), that list-level 0.6 too - two
                    // compounding dims plus a low-contrast text color made this genuinely hard to read.
                    <Text size="sm" data-testid="priority-row-disabled-note">
                      {sv.simple.priorities.disabledRuleNote}
                    </Text>
                  )}
                </div>
              </Group>
              {/* v0.6.0 audit batch D (D4): a plain Group with an explicit 4px gap, replacing
                  ActionIcon.Group's flush (0px, shared-border) layout - the two arrows read as
                  separate actions now, not one segmented control. */}
              <Group gap={4} wrap="nowrap">
                <Tooltip label={sv.simple.priorities.moveUpAriaLabel(labelSv)} disabled={disabled || index === 0}>
                  <ActionIcon
                    ref={(el) => {
                      const entry = buttonRefs.current.get(key) ?? { up: null, down: null };
                      entry.up = el;
                      buttonRefs.current.set(key, entry);
                    }}
                    variant="default"
                    aria-label={sv.simple.priorities.moveUpAriaLabel(labelSv)}
                    disabled={disabled || index === 0}
                    onClick={() => handleMoveClick(index, "up")}
                  >
                    <IconArrowUp size={16} />
                  </ActionIcon>
                </Tooltip>
                <Tooltip
                  label={sv.simple.priorities.moveDownAriaLabel(labelSv)}
                  disabled={disabled || index === order.length - 1}
                >
                  <ActionIcon
                    ref={(el) => {
                      const entry = buttonRefs.current.get(key) ?? { up: null, down: null };
                      entry.down = el;
                      buttonRefs.current.set(key, entry);
                    }}
                    variant="default"
                    aria-label={sv.simple.priorities.moveDownAriaLabel(labelSv)}
                    disabled={disabled || index === order.length - 1}
                    onClick={() => handleMoveClick(index, "down")}
                  >
                    <IconArrowDown size={16} />
                  </ActionIcon>
                </Tooltip>
              </Group>
            </Group>
          </Paper>
        );
      })}
    </Stack>
  );
}
