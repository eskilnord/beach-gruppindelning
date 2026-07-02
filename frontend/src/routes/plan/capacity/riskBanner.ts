import { sv } from "../../../i18n/sv";
import type { CapacityResponse } from "../../../api/types";

export type BannerColor = "green" | "yellow" | "red" | "gray";

export interface RiskBanner {
  color: BannerColor;
  title: string;
  /** Swedish sentence straight from the backend (CapacityService) - kept verbatim rather than
   *  re-derived client-side, so the UI never disagrees with the number it's explaining. */
  message: string;
}

/**
 * Maps CapacityResponse.waitlistRisk (spec §12.4) to a color + Swedish headline for the Kapacitetsvy
 * banner. NONE -> green ("no risk"), OVER_TARGET -> yellow (fits max, exceeds target - spec's own
 * worked example), OVER_MAX -> red (a waitlist is required), anything else (including the backend's
 * own UNKNOWN, or a future/unrecognized value) falls back to a neutral gray "can't be computed" state.
 */
export function describeWaitlistRisk(
  risk: CapacityResponse["waitlistRisk"] | undefined,
  message: string | undefined,
): RiskBanner {
  const text = message ?? "";
  switch (risk) {
    case "NONE":
      return { color: "green", title: sv.capacity.risk.none, message: text };
    case "OVER_TARGET":
      return { color: "yellow", title: sv.capacity.risk.overTarget, message: text };
    case "OVER_MAX":
      return { color: "red", title: sv.capacity.risk.overMax, message: text };
    default:
      return { color: "gray", title: sv.capacity.risk.unknown, message: text };
  }
}

/** Maps CapacityResponse.coachShortageRisk (spec §12.4 "Risk för tränarbrist") to a color + Swedish
 *  headline for the coach-shortage banner. */
export function describeCoachShortage(shortageRisk: boolean, message: string | undefined): RiskBanner {
  const text = message ?? "";
  return shortageRisk
    ? { color: "red", title: sv.capacity.coachShortage.risk, message: text }
    : { color: "green", title: sv.capacity.coachShortage.ok, message: text };
}
