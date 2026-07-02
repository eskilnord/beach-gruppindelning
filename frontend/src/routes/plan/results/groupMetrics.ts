/**
 * Client-side nivåsnitt/nivåspridning (spec §19.10) - computed from members' `estimatedLevel` for
 * DISPLAY purposes only. Conceptually mirrors the backend's `LevelMath` (mean + sum-of-absolute-
 * deviations spread, docs/design/04-solver.md §3.3), but in plain floating point: CLAUDE.md's
 * fixed-point/no-float determinism rule applies to `solver.domain`/`solver.constraints` Java code
 * (score-affecting logic that must reproduce bit-identically across CI runners), not to a read-only
 * UI label - there is no cross-platform reproducibility requirement for a rounded display number.
 */
export interface LevelStats {
  mean: number | null;
  spread: number | null;
}

export function computeLevelStats(levels: Array<number | null | undefined>): LevelStats {
  const valid = levels.filter((level): level is number => typeof level === "number" && Number.isFinite(level));
  if (valid.length === 0) {
    return { mean: null, spread: null };
  }
  const sum = valid.reduce((total, level) => total + level, 0);
  const mean = sum / valid.length;
  const spread = valid.reduce((total, level) => total + Math.abs(level - mean), 0);
  return { mean: Math.round(mean), spread: Math.round(spread) };
}
