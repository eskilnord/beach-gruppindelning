import { sv } from "../../../i18n/sv";

/**
 * "Are these groups good?" at-a-glance quality signals for a single group (user feedback v0.4 #5) -
 * a pure, null-tolerant function so GroupCard.tsx can derive its colored status dot/border and
 * compact chip row from the exact same view-model fields it already has on hand (group size/target/
 * min/max, coach count, the client-side level stats from groupMetrics.ts, and - only for the top-3
 * groups the plan explanation's `problematicGroups` ranking flags - a penalty sum), without a second
 * source of truth for "is this group fine".
 */

export type SignalSeverity = "ok" | "warn" | "bad";
export type QualityStatus = "good" | "warn" | "bad";

export interface QualitySignal {
  /** Stable identifier for a signal *kind* (not per-instance) - used by callers to look up a
   *  specific signal's severity for coloring an individual chip (e.g. "does the size chip alone
   *  need to be red, even though the group's overall status is merely warn"). */
  key:
    | "coachMissing"
    | "coachBelowRequired"
    | "coachInPlace"
    | "sizeBelowMin"
    | "sizeAboveMax"
    | "sizeBelowTarget"
    | "sizeAboveTarget"
    | "sizeAtTarget"
    | "levelOutsideBand"
    | "levelInsideBand"
    | "topPenalty";
  severity: SignalSeverity;
  textSv: string;
}

export interface GroupQuality {
  /** Worst severity across every emitted signal - "good" when nothing rose above "ok" (including
   *  the case where zero signals were emitted at all, e.g. a group with no target/band/coach
   *  requirement configured has nothing to warn about). */
  status: QualityStatus;
  signals: QualitySignal[];
}

export interface GroupQualityInput {
  size: number;
  minSize: number | null;
  targetSize: number | null;
  maxSize: number | null;
  requiredCoachCount: number | null;
  coachCount: number;
  /** GroupCard's own `computeLevelStats(...).mean` (groupMetrics.ts) - already rounded to a whole
   *  point, the exact same number the card's "Nivåsnitt" stat shows, so this module never computes
   *  a second, potentially-diverging mean from the raw member levels. */
  levelMean: number | null;
  /** Sum-of-absolute-deviations spread (groupMetrics.ts) - accepted for completeness/future display
   *  use but deliberately NOT turned into its own signal here: it is an aggregate over the whole
   *  group, not a per-member max deviation, so there is no principled threshold to say "the spread
   *  implies someone sits outside the band" without inventing an arbitrary cutoff. The one thing we
   *  CAN say for certain - whether the group's own mean sits outside its configured band - is covered
   *  by the level-vs-band signal below. */
  levelSpread: number | null;
  levelMin: number | null;
  levelMax: number | null;
  /** The plan explanation's `problematicGroups[].penaltySum` for this group, or omitted/null when
   *  the group isn't one of the (top-3, penaltySum > 0) groups ResultsPanel wires through - see its
   *  own javadoc. Never computed here; this module only reacts to what the caller already decided
   *  is "in the top 3". */
  penaltySum?: number | null;
}

/** One decimal, Swedish comma - mirrors the backend's own level formatting convention
 *  (JustificationMessages#formatLevel, e.g. scaled 64000 -> "640,0"). Used only for a group's
 *  levelMin/levelMax band boundaries, which - unlike {@link GroupQualityInput.levelMean} above -
 *  are raw configured doubles that can genuinely carry a meaningful fractional value. */
export function formatBandBoundary(value: number): string {
  const rounded = Math.round(value * 10) / 10;
  return rounded.toFixed(1).replace(".", ",");
}

/** Maps a signal's severity (or a group's overall status, since the two enums share the same
 *  "worse than ok" ordering) to the Mantine color used consistently across GroupCard's status dot/
 *  top border/chips and nowhere else - callers needing a color for a status with no matching signal
 *  (e.g. a chip whose check doesn't apply, no target configured) fall back to "gray" themselves. */
export function severityColor(severity: SignalSeverity | QualityStatus): string {
  switch (severity) {
    case "bad":
      return "red";
    case "warn":
      return "yellow";
    default:
      return "teal";
  }
}

export function computeGroupQuality(input: GroupQualityInput): GroupQuality {
  const signals: QualitySignal[] = [];

  // --- Coach coverage: only meaningful when the group actually requires a coach. Any shortfall is
  // bad (review fix 4): a group configured for 2 coaches with only 1 assigned is understaffed, not
  // "in place" - the zero case just gets its own blunter wording. ---
  if (input.requiredCoachCount != null && input.requiredCoachCount > 0) {
    if (input.coachCount === 0) {
      signals.push({ key: "coachMissing", severity: "bad", textSv: sv.results.quality.signals.coachMissing });
    } else if (input.coachCount < input.requiredCoachCount) {
      signals.push({
        key: "coachBelowRequired",
        severity: "bad",
        textSv: sv.results.quality.signals.coachBelowRequired(input.coachCount, input.requiredCoachCount),
      });
    } else {
      signals.push({ key: "coachInPlace", severity: "ok", textSv: sv.results.quality.signals.coachInPlace });
    }
  }

  // --- Size vs min/max/target: hard bounds win over the softer "on target" check. ---
  if (input.minSize != null && input.size < input.minSize) {
    signals.push({
      key: "sizeBelowMin",
      severity: "bad",
      textSv: sv.results.quality.signals.sizeBelowMin(input.size, input.minSize),
    });
  } else if (input.maxSize != null && input.size > input.maxSize) {
    signals.push({
      key: "sizeAboveMax",
      severity: "bad",
      textSv: sv.results.quality.signals.sizeAboveMax(input.size, input.maxSize),
    });
  } else if (input.targetSize != null) {
    if (input.size < input.targetSize) {
      signals.push({
        key: "sizeBelowTarget",
        severity: "warn",
        textSv: sv.results.quality.signals.sizeBelowTarget(input.size, input.targetSize),
      });
    } else if (input.size > input.targetSize) {
      signals.push({
        key: "sizeAboveTarget",
        severity: "warn",
        textSv: sv.results.quality.signals.sizeAboveTarget(input.size, input.targetSize),
      });
    } else {
      signals.push({ key: "sizeAtTarget", severity: "ok", textSv: sv.results.quality.signals.sizeAtTarget });
    }
  }

  // --- Level mean vs the group's configured band (informational band, spec §7 "never hard"). ---
  if (input.levelMin != null && input.levelMax != null && input.levelMean != null) {
    if (input.levelMean < input.levelMin || input.levelMean > input.levelMax) {
      signals.push({
        key: "levelOutsideBand",
        severity: "warn",
        textSv: sv.results.quality.signals.levelOutsideBand(
          input.levelMean,
          formatBandBoundary(input.levelMin),
          formatBandBoundary(input.levelMax),
        ),
      });
    } else {
      signals.push({ key: "levelInsideBand", severity: "ok", textSv: sv.results.quality.signals.levelInsideBand });
    }
  }

  // --- Plan-wide problematic-group ranking (only set for the top-3, penaltySum > 0 groups). ---
  if (input.penaltySum != null && input.penaltySum > 0) {
    signals.push({ key: "topPenalty", severity: "warn", textSv: sv.results.quality.signals.topPenalty });
  }

  const status: QualityStatus = signals.some((s) => s.severity === "bad")
    ? "bad"
    : signals.some((s) => s.severity === "warn")
      ? "warn"
      : "good";

  return { status, signals };
}
