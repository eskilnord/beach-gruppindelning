import { sv } from "../../../../i18n/sv";

export interface Badge {
  label: string;
  color: string;
}

/**
 * Maps an {@link AlternativeGroupView}'s `verdict` (design §11.3 as amended by the M7 review:
 * `WOULD_BREAK_HARD|BETTER|NEUTRAL|WORSE`, see backend ExplanationService#toAlternativeView -
 * NEUTRAL is an exactly-zero score delta, narrative "påverkar inte totalpoängen") to a Swedish
 * label + Mantine color for the ALTERNATIVEN cards (spec §17.3) and the what-if consequence report.
 * Any unrecognized value (a future backend addition the frontend hasn't been updated for) falls
 * back to the raw string with a neutral gray, matching this codebase's existing fallback convention
 * (see riskBanner.ts).
 */
export function verdictBadge(verdict: string): Badge {
  switch (verdict) {
    case "WOULD_BREAK_HARD":
      return { label: sv.results.explain.verdict.wouldBreakHard, color: "red" };
    case "BETTER":
      return { label: sv.results.explain.verdict.better, color: "green" };
    case "NEUTRAL":
      return { label: sv.results.explain.verdict.neutral, color: "gray" };
    case "WORSE":
      return { label: sv.results.explain.verdict.worse, color: "gray" };
    default:
      return { label: verdict, color: "gray" };
  }
}

/**
 * Maps an {@link AlternativeGroupView}'s `origin` entries (design §11.3's union-rule labels:
 * `FRIEND_WISH|FRIEND_VIA_COACH|COACH_WISH|PREVIOUS_GROUP|TOP_SCORE` - a candidate can carry more
 * than one) to a Swedish badge label + color, for the ALTERNATIVEN comparison cards (spec §17.3).
 * `FRIEND_VIA_COACH` (v0.3.0 WI-5) always appears ALONGSIDE `FRIEND_WISH`, never alone - both
 * badges render on the same card.
 */
export function originBadge(origin: string): Badge {
  switch (origin) {
    case "FRIEND_WISH":
      return { label: sv.results.explain.origin.friendWish, color: "grape" };
    case "FRIEND_VIA_COACH":
      return { label: sv.results.explain.origin.friendViaCoach, color: "violet" };
    case "COACH_WISH":
      return { label: sv.results.explain.origin.coachWish, color: "indigo" };
    case "PREVIOUS_GROUP":
      return { label: sv.results.explain.origin.previousGroup, color: "cyan" };
    case "TOP_SCORE":
      return { label: sv.results.explain.origin.topScore, color: "teal" };
    default:
      return { label: origin, color: "gray" };
  }
}

/**
 * Renders a "Tillämpade vikter"/brokenWish weight badge in kravspec §17.2's own worked-example
 * phrasing verbatim: "Kompisönskemål var soft 60... nivåbalans var soft 100 och maxstorlek var hard"
 * - the spec keeps the English hard/soft tokens even mid-Swedish-sentence, so this deliberately does
 * NOT go through {@link sv.hardOrSoft}'s "Hård"/"Mjuk" translation. HARD constraints omit the numeric
 * weight (it's always a fixed ONE_HARD unit internally, not a meaningful magnitude to show - see
 * ConstraintWeightsTable's own "—" placeholder for the same reason), matching the same visual
 * convention used elsewhere in the Fält/Optimering tabs.
 */
export function weightBadgeLabel(level: string, weight: number): string {
  const lower = level.toLowerCase();
  return lower === "hard" ? lower : `${lower} ${weight}`;
}
