package se.klubb.groupplanner.solver.constraints;

/**
 * Pure-integer level math (docs/design/04-solver.md §3.3) — the single implementation of "spread"
 * used by the {@code levelBalance} constraint (M6b), group-level display, and what-if deltas, so the
 * numbers agree everywhere. {@code floorDiv} rounding only; no division except that, no
 * float/double/BigDecimal (CLAUDE.md determinism rules, enforced by {@code NoFloatingPointArchTest}).
 *
 * <pre>
 * sum_g    = Sum x_i                       (long)
 * mean_g   = Math.floorDiv(sum_g, n)       (floor division - defined, deterministic)
 * SAD_g    = Sum |x_i - mean_g|            (sum of absolute deviations, scaled units)
 * penalty  = Math.floorDiv(SAD_g, 100)     (back to whole level points)
 * </pre>
 */
public final class LevelMath {

    private LevelMath() {
    }

    /** Sum of a group's scaled levels. */
    public static long sum(int[] levelsScaled) {
        long total = 0L;
        for (int level : levelsScaled) {
            total += level;
        }
        return total;
    }

    /** Floor-division mean of a group's scaled levels; {@code n} must be &gt; 0. */
    public static long floorMean(long sumScaled, int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be > 0, got " + n);
        }
        return Math.floorDiv(sumScaled, n);
    }

    /**
     * Sum-of-absolute-deviations spread, converted back to whole level points (dividing the
     * scaled-unit SAD by 100 via {@code floorDiv}). Empty input has zero spread by definition.
     */
    public static int sadPoints(int[] levelsScaled) {
        if (levelsScaled.length == 0) {
            return 0;
        }
        long total = sum(levelsScaled);
        long mean = floorMean(total, levelsScaled.length);
        long sad = 0L;
        for (int level : levelsScaled) {
            sad += Math.abs((long) level - mean);
        }
        return Math.toIntExact(Math.floorDiv(sad, 100));
    }
}
