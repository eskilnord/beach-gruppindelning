import { formatThousands } from "../../optimize/scoreFormat";

interface ScoreDeltaLike {
  hard: number;
  medium: number;
  soft: number;
}

/**
 * Formats a {@code ScoreDeltaView} (HardMediumSoftLongScore delta returned by an alternative-group
 * probe or a what-if move, docs/design/04-solver.md §11/§12) as a short signed summary, e.g.
 * "-1 hårt · -37 mjukt" (kravspec §18.1's own worked example: "Total score försämras med 37 poäng").
 * Hard/medium components are omitted when zero (the common case for a same-feasibility alternative);
 * soft is always shown since it's the deciding number in practice.
 */
export function formatScoreDelta(delta: ScoreDeltaLike): string {
  const parts: string[] = [];
  if (delta.hard !== 0) {
    parts.push(`${delta.hard > 0 ? "+" : ""}${formatThousands(delta.hard)} hårt`);
  }
  if (delta.medium !== 0) {
    parts.push(`${delta.medium > 0 ? "+" : ""}${formatThousands(delta.medium)} medium`);
  }
  parts.push(`${delta.soft > 0 ? "+" : ""}${formatThousands(delta.soft)} mjukt`);
  return parts.join(" · ");
}
