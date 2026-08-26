/**
 * v0.6.0 F3 (M-S3): pure array-reorder helpers backing PriorityRankList.tsx's arrow buttons (via
 * {@link moveItem}) and native HTML5 drag-and-drop (via {@link reorder}), plus a safety check
 * ({@link isPermutation}) PrioritiesPanel.tsx runs before ever sending a reordered array to the
 * backend. No knowledge of PriorityKey/React here on purpose - generic over T so these are trivially
 * unit-testable without any component/query-client scaffolding.
 */

/**
 * Swaps the item at `index` with its neighbor in `direction` ("up" moves it one slot earlier -
 * towards rank 1; "down" moves it one slot later). Returns a NEW array reference on a real move, or
 * the SAME `list` reference back unchanged at a boundary (index already first/going up, or already
 * last/going down, or `index` out of range) - callers use `next === list` to cheaply detect a no-op
 * and skip triggering a save.
 */
export function moveItem<T>(list: readonly T[], index: number, direction: "up" | "down"): T[] {
  const targetIndex = direction === "up" ? index - 1 : index + 1;
  if (index < 0 || index >= list.length || targetIndex < 0 || targetIndex >= list.length) {
    return list as T[];
  }
  const next = list.slice();
  [next[index], next[targetIndex]] = [next[targetIndex], next[index]];
  return next;
}

/**
 * Moves the item at `fromIndex` to sit at `toIndex`, shifting the items in between - backs the
 * drag-and-drop drop handler (dropping row N onto row M's position). Same no-op contract as
 * {@link moveItem}: returns the same `list` reference back when the indices are equal or either is
 * out of range.
 */
export function reorder<T>(list: readonly T[], fromIndex: number, toIndex: number): T[] {
  if (
    fromIndex === toIndex ||
    fromIndex < 0 ||
    fromIndex >= list.length ||
    toIndex < 0 ||
    toIndex >= list.length
  ) {
    return list as T[];
  }
  const next = list.slice();
  const [moved] = next.splice(fromIndex, 1);
  next.splice(toIndex, 0, moved);
  return next;
}

/**
 * True when `candidate` contains exactly the same elements as `reference`, each exactly as many
 * times - i.e. `candidate` is a reordering of `reference`, nothing dropped or duplicated. Used as a
 * defensive guard immediately before a PUT (moveItem/reorder above are both proven never to violate
 * this on their own, but the check is cheap and catches any future regression at the one call site
 * that actually talks to the backend, rather than trusting the invariant silently).
 */
export function isPermutation<T>(candidate: readonly T[], reference: readonly T[]): boolean {
  if (candidate.length !== reference.length) {
    return false;
  }
  const remaining = new Map<T, number>();
  for (const item of reference) {
    remaining.set(item, (remaining.get(item) ?? 0) + 1);
  }
  for (const item of candidate) {
    const count = remaining.get(item);
    if (!count) {
      return false;
    }
    remaining.set(item, count - 1);
  }
  return true;
}

/**
 * v0.6.0 audit batch D (D4): true when `a` and `b` are the same length and have equal elements at
 * every index (order-sensitive, unlike {@link isPermutation}) - backs PrioritiesPanel.tsx's
 * "Återställ till standardordning" button visibility (only shown once the displayed order has
 * actually drifted from `PriorityOrderView.defaultOrder`).
 */
export function arraysEqual<T>(a: readonly T[], b: readonly T[]): boolean {
  return a.length === b.length && a.every((item, index) => item === b[index]);
}
