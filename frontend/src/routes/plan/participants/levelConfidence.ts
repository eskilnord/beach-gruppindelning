import { sv } from "../../../i18n/sv";

export type LevelConfidenceBucket = "high" | "medium" | "low" | "none";

export interface LevelConfidenceBadge {
  bucket: LevelConfidenceBucket;
  label: string;
  color: string;
}

/**
 * Maps the backend's numeric `levelConfidence` (spec §11.4: "hög/medel/låg" or a 0.0-1.0 number -
 * LevelService always produces the numeric form, at four fixed points: 1.0/0.6/0.3/0.0, see
 * backend/src/main/java/se/klubb/groupplanner/level/LevelService.java CONFIDENCE_* constants) to
 * the Swedish confidence badge shown in the Deltagarvy grid and drawer.
 *
 * Uses threshold bands rather than exact-equality so the mapping stays correct even if a future
 * milestone tunes the fixed points slightly (spec §11.3 "justerbar viktning").
 */
export function describeLevelConfidence(confidence: number | null | undefined): LevelConfidenceBadge {
  if (confidence === null || confidence === undefined || confidence <= 0) {
    return { bucket: "none", label: sv.participants.levelConfidence.none, color: "gray" };
  }
  if (confidence >= 0.9) {
    return { bucket: "high", label: sv.participants.levelConfidence.high, color: "green" };
  }
  if (confidence >= 0.45) {
    return { bucket: "medium", label: sv.participants.levelConfidence.medium, color: "blue" };
  }
  return { bucket: "low", label: sv.participants.levelConfidence.low, color: "yellow" };
}
