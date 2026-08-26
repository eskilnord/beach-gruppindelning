package se.klubb.groupplanner.solver.constraints;

/**
 * Pure-integer level math (docs/design/04-solver.md §3.3) — the single implementation of "spread"
 * shared by two roles that use different final divisors on the same underlying SAD: {@code
 * sadPoints} (whole level points) for group-level display and what-if deltas, and {@code
 * spreadUnits} (spread units, since v0.6.0 milestone B2) as the {@code levelBalance} constraint's
 * matchWeight. {@code floorDiv} rounding only; no division except that, no float/double/BigDecimal
 * (CLAUDE.md determinism rules, enforced by {@code NoFloatingPointArchTest}).
 *
 * <pre>
 * sum_g       = Sum x_i                       (long)
 * mean_g      = Math.floorDiv(sum_g, n)       (floor division - defined, deterministic)
 * SAD_g       = Sum |x_i - mean_g|            (sum of absolute deviations, scaled units)
 * sadPoints   = Math.floorDiv(SAD_g, 100)     (back to whole level points; display/justification)
 * matchWeight = Math.floorDiv(SAD_g, 1000)    (spread units; levelBalance's matchWeight)
 * </pre>
 */
public final class LevelMath {

    /** One spread unit = 10 whole level points of a group's total absolute deviation (SAD).
     *  Used as levelBalance's matchWeight. Display surfaces keep using sadPoints (whole points). */
    public static final int SPREAD_UNIT_SCALED = 1_000; // 10 level points x 100 fixed-point

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
        return Math.toIntExact(Math.floorDiv(sadScaled(levelsScaled), 100));
    }

    /**
     * Sum-of-absolute-deviations spread, expressed in {@link #SPREAD_UNIT_SCALED}-sized units
     * (dividing the scaled-unit SAD by 1000 via {@code floorDiv}). Shares the exact same SAD
     * computation as {@link #sadPoints(int[])} so the two never diverge; only the final divisor
     * differs. Empty input has zero spread by definition.
     */
    public static int spreadUnits(int[] levelsScaled) {
        return Math.toIntExact(Math.floorDiv(sadScaled(levelsScaled), SPREAD_UNIT_SCALED));
    }

    /** Shared scaled-unit sum-of-absolute-deviations computation, used by both {@link
     *  #sadPoints(int[])} and {@link #spreadUnits(int[])}. Empty input has zero SAD by definition. */
    private static long sadScaled(int[] levelsScaled) {
        if (levelsScaled.length == 0) {
            return 0L;
        }
        long total = sum(levelsScaled);
        long mean = floorMean(total, levelsScaled.length);
        long sad = 0L;
        for (int level : levelsScaled) {
            sad += Math.abs((long) level - mean);
        }
        return sad;
    }
}
