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

function pluralize(count: number, singular: string, plural: string): string {
  return `${count} ${count === 1 ? singular : plural}`;
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
  const softPart = `${formatThousands(soft)} mjukt`;

  return `${hardPart} · ${waitlistPart} · ${softPart}`;
}
