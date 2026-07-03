import { pluralize } from "../../../lib/pluralizeSv";

/**
 * Formats an integer with a plain ASCII space as the thousands separator and a plain ASCII hyphen
 * for negative numbers (spec §19.9 worked example: "-2 344 940 mjukt") - deliberately NOT
 * `Intl.NumberFormat("sv-SE")`, which uses U+00A0 (non-breaking space) and U+2212 (minus sign)
 * instead of the plain characters the example (and Playwright's text matchers) expect.
 */
export function formatThousands(value: number): string {
  const sign = value < 0 ? "-" : "";
  const digits = String(Math.abs(Math.trunc(value)));
  const grouped = digits.replace(/\B(?=(\d{3})+(?!\d))/g, " ");
  return sign + grouped;
}

/**
 * Just the soft component of the score line ("-2 344 940 mjukt") - WI-E review fix: the Resultat
 * tab's quality strip (ResultsSummary.tsx) shows its own hard-violations and waitlist CHIPS, whose
 * hard count comes from the plan explanation (a true per-violation count) - repeating the full
 * {@link formatScoreLine} there would put the run summary's WEIGHTED |hard| magnitude (e.g.
 * groupMaxSizeHard penalizes per point over max, so |hard| can exceed the violation count) right
 * next to a chip counting actual violations, and the two can legitimately disagree. Only the soft
 * part carries non-duplicated information in that strip.
 */
export function formatSoftLine(soft: number | null | undefined): string {
  return `${formatThousands(soft ?? 0)} mjukt`;
}

/** Loosely-typed on purpose: both `SolveStatus` (score fields `number | undefined`) and
 *  `RunResultSummary` (`number`, parsed from JSON, so a caller might still pass through a stray
 *  `null`) need to flow through here without extra call-site narrowing. */
export interface ScoreLike {
  hard?: number | null;
  unassignedCount?: number | null;
  soft?: number | null;
}

/**
 * The live/last-run score line (spec §19.9): "0 hårda brott · 3 på kölista · -2 344 940 mjukt".
 * `hard`/`soft` are raw HardMediumSoftLongScore components (`hard` is always <= 0 in Timefold's
 * convention, so the violation count shown is its magnitude); `unassignedCount` (not the raw
 * `medium` score) is the waitlist headcount, matching backend/docs/m6b-notes.md's own framing
 * ("Medium -800 = 3 unassigned × priority-scaled cost").
 */
export function formatScoreLine(score: ScoreLike): string {
  const hardViolations = Math.abs(score.hard ?? 0);
  const waitlisted = score.unassignedCount ?? 0;
  const soft = score.soft ?? 0;

  const hardPart = pluralize(hardViolations, "hårt brott", "hårda brott");
  const waitlistPart = pluralize(waitlisted, "på kölista", "på kölista");

  return `${hardPart} · ${waitlistPart} · ${formatSoftLine(soft)}`;
}
