package se.klubb.groupplanner.fields;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.fields.PriorityOrder.Priority;
import se.klubb.groupplanner.solver.constraints.ConstraintKeys;

/**
 * v0.6.0 milestone B6: the executable form of {@link PriorityOrder}'s class-javadoc analysis —
 * plain integer arithmetic, no solver, no Spring context. Pins the numeric claims the javadoc makes
 * in prose so they cannot silently drift out of sync with the actual ladder constants.
 *
 * <p>"One band" of level imbalance is modeled as {@code bandUnits x levelBalance's weight}, where
 * {@code bandUnits} is a band-width in level points divided by 10 (one {@code LevelMath
 * .SPREAD_UNIT_SCALED} spread unit = 10 level points) — this mirrors {@code levelBalance}'s actual
 * {@code matchWeight} (spread units), not a raw level-point count.
 *
 * <p><b>Review fix (2026-08-26)</b>: the pre-B6 version of this class asserted {@code k* ∈ [3, 6]}
 * as a WIDTH-INDEPENDENT bound and backed the band-width sweep with {@code (ceilDiv(x,c)+1)*c > x}
 * — an algebraic tautology true for ANY positive {@code x}/{@code c}, so it verified nothing about
 * the actual numbers. Both were wrong: {@code k*} is NOT width-independent (a 40-point band gives
 * {@code k* ≈ 7.06}, already outside the old claimed [3, 6] range) — what IS width-independent is a
 * DIFFERENT invariant, {@code k*(W) × W}, the total level-point drag a wish can win regardless of
 * how wide a "band" is taken to be (see {@link #kStarTimesWidthInvariantHoldsAcrossBandWidths()}).
 */
class WeightCalibrationTest {

    /** Default-order oneBandCost per the class javadoc's own worked example: 7 spread units (a
     * ~70-level-point band) x levelBalance's rank-1 weight (85). */
    private static final int DEFAULT_BAND_UNITS = 7;

    // ─────────────────────────────────────────────────────────────────── (a) dominance chain

    @Test
    void defaultOrderDominanceChainHoldsWithRatiosInRange() {
        Map<String, Integer> weights = PriorityOrder.weightsFor(PriorityOrder.defaultOrder());
        int sameGroupSoft = weights.get(ConstraintKeys.SAME_GROUP_SOFT);
        int previousGroupContinuity = weights.get(ConstraintKeys.PREVIOUS_GROUP_CONTINUITY);
        int timePreferenceSoft = weights.get(ConstraintKeys.TIME_PREFERENCE_SOFT);
        int levelBalance = weights.get(ConstraintKeys.LEVEL_BALANCE);
        int oneBandLevelCost = DEFAULT_BAND_UNITS * levelBalance;

        assertThat(sameGroupSoft).isEqualTo(2400);
        assertThat(previousGroupContinuity).isEqualTo(1500);
        assertThat(timePreferenceSoft).isEqualTo(950);
        assertThat(levelBalance).isEqualTo(85);
        assertThat(oneBandLevelCost).isEqualTo(595);

        assertThat(sameGroupSoft).isGreaterThan(previousGroupContinuity);
        assertThat(previousGroupContinuity).isGreaterThan(timePreferenceSoft);
        assertThat(timePreferenceSoft).isGreaterThan(oneBandLevelCost);

        assertRatioInRange(sameGroupSoft, previousGroupContinuity);
        assertRatioInRange(previousGroupContinuity, timePreferenceSoft);
        assertRatioInRange(timePreferenceSoft, oneBandLevelCost);
    }

    private static void assertRatioInRange(int higher, int lower) {
        // Integer-safe [1.5, 1.7] check: 1.5 <= higher/lower <= 1.7  <=>  1.5*lower <= higher*10 <= 1.7*lower*10...
        // done as a pure integer cross-multiplication to avoid any floating point (CLAUDE.md rule).
        assertThat(10L * higher).as("%d / %d ratio >= 1.5", higher, lower).isGreaterThanOrEqualTo(15L * lower);
        assertThat(10L * higher).as("%d / %d ratio <= 1.7", higher, lower).isLessThanOrEqualTo(17L * lower);
    }

    // ─────────────────────────────────────────────────────────────────── (b) k*, honestly, per width

    /**
     * Property (b), rewritten (review fix): {@code k*(W) = sameGroupSoft / oneBandCost(W)} for
     * {@code oneBandCost(W) = (W / 10) x levelBalance}, computed EXACTLY per band width {@code W ∈
     * {40, 70, 100}} level points via pure-integer cross-multiplication (no floating point, CLAUDE.md
     * rule) — no artificial universal range, just the actual numbers: {@code k*(40) ≈ 7.06 (already
     * > 6!)}, {@code k*(70) ≈ 4.03}, {@code k*(100) ≈ 2.82}. {@code k*} genuinely shrinks as the band
     * widens (a wider band costs more per spread unit already, so fewer of them fit under
     * {@code sameGroupSoft}) — {@code k*} is NOT a width-independent constant, unlike the invariant in
     * {@link #kStarTimesWidthInvariantHoldsAcrossBandWidths()} below.
     */
    @Test
    void kStarPerBandWidthMatchesExactWorkedArithmetic() {
        Map<String, Integer> weights = PriorityOrder.weightsFor(PriorityOrder.defaultOrder());
        int sameGroupSoft = weights.get(ConstraintKeys.SAME_GROUP_SOFT);
        int levelBalance = weights.get(ConstraintKeys.LEVEL_BALANCE);
        assertThat(sameGroupSoft).isEqualTo(2400);
        assertThat(levelBalance).isEqualTo(85);

        assertKStarHundredths(sameGroupSoft, bandCost(40, levelBalance), 706); // k*(40) ~= 7.06, > 6
        assertKStarHundredths(sameGroupSoft, bandCost(70, levelBalance), 403); // k*(70) ~= 4.03
        assertKStarHundredths(sameGroupSoft, bandCost(100, levelBalance), 282); // k*(100) ~= 2.82

        // k* strictly decreases as the band widens - not a fixed universal number.
        assertThat(bandCost(40, levelBalance)).isLessThan(bandCost(70, levelBalance));
        assertThat(bandCost(70, levelBalance)).isLessThan(bandCost(100, levelBalance));
    }

    /**
     * Property (b)'s actual width-INDEPENDENT invariant (review fix - what the pre-B6 test SHOULD
     * have asserted instead of a fixed range on k* itself): {@code k*(W) x W} — the total level-POINT
     * displacement a friend wish can win, regardless of which band width the calculation is done in
     * — collapses algebraically to {@code sameGroupSoft x 10 / levelBalance}, a constant that does
     * NOT depend on {@code W} at all (since {@code k*(W) x W = sameGroupSoft x W / ((W/10) x
     * levelBalance) = sameGroupSoft x 10 / levelBalance}). At the default order this is {@code 2400 x
     * 10 / 85 ≈ 282.4} level points: "a friend wish drags ≈280 level points of displacement,
     * regardless of how wide a band is taken to be" — verified here via pure-integer
     * cross-multiplication against the {@code [270, 290]} bracket, for the same three widths as
     * above.
     */
    @Test
    void kStarTimesWidthInvariantHoldsAcrossBandWidths() {
        Map<String, Integer> weights = PriorityOrder.weightsFor(PriorityOrder.defaultOrder());
        int sameGroupSoft = weights.get(ConstraintKeys.SAME_GROUP_SOFT);
        int levelBalance = weights.get(ConstraintKeys.LEVEL_BALANCE);

        for (int bandWidthLevelPoints : new int[] {40, 70, 100}) {
            long oneBandCost = bandCost(bandWidthLevelPoints, levelBalance);
            // k*(W) x W, expressed without ever dividing: k*(W) x W = (sameGroupSoft x W) / oneBandCost,
            // so "270 <= k*(W) x W <= 290" is exactly "270*oneBandCost <= sameGroupSoft*W <=
            // 290*oneBandCost" - pure integer cross-multiplication, no floating point.
            long dragNumerator = (long) sameGroupSoft * bandWidthLevelPoints;
            assertThat(dragNumerator)
                    .as("k*(%d) x %d must be >= 270 level points", bandWidthLevelPoints, bandWidthLevelPoints)
                    .isGreaterThanOrEqualTo(270L * oneBandCost);
            assertThat(dragNumerator)
                    .as("k*(%d) x %d must be <= 290 level points", bandWidthLevelPoints, bandWidthLevelPoints)
                    .isLessThanOrEqualTo(290L * oneBandCost);
        }
    }

    private static long bandCost(int bandWidthLevelPoints, int levelBalanceWeight) {
        assertThat(bandWidthLevelPoints % 10).as("band width must be a whole number of spread units").isZero();
        int bandUnits = bandWidthLevelPoints / 10;
        return (long) bandUnits * levelBalanceWeight;
    }

    /** Asserts {@code round(numerator / denominator * 100) == expectedHundredths}, via pure-integer
     *  round-half-up arithmetic (no floating point, CLAUDE.md rule): {@code round(x) =
     *  floorDiv(2x + 1, 2)} generalized to a fraction by scaling both the numerator and the rounding
     *  epsilon by the denominator first. */
    private static void assertKStarHundredths(long numerator, long denominator, long expectedHundredths) {
        long scaledNumerator = numerator * 100;
        long roundedHundredths = Math.floorDiv(2 * scaledNumerator + denominator, 2 * denominator);
        assertThat(roundedHundredths)
                .as("k* = %d / %d rounded to hundredths", numerator, denominator)
                .isEqualTo(expectedHundredths);
    }

    // ─────────────────────────────────────────────────────────────── (c) AVOID_LADDER rounding

    /**
     * {@link PriorityOrder#AVOID_LADDER}'s javadoc documents it as 0.75&#215; {@link
     * PriorityOrder#UNIT_LADDER} at every rank, ROUNDED TO THE NEAREST 5 (a plain 0.75&#215; would
     * give rank 3 {@code 712.5}, not an integer) — this pins that rounding rule numerically, via pure
     * integer cross-multiplication: every {@code AVOID_LADDER[i]} must be within &#177;5 of
     * {@code 0.75 x UNIT_LADDER[i]} (checked as {@code |4*avoid - 3*unit| <= 20}, i.e. {@code |avoid -
     * 0.75*unit| <= 5} scaled by 4 to stay integer-only).
     */
    @Test
    void avoidLadderIsWithinFiveOfSeventyFivePercentOfUnitLadderAtEveryRank() {
        for (int rank = 0; rank < PriorityOrder.UNIT_LADDER.length; rank++) {
            long unit = PriorityOrder.UNIT_LADDER[rank];
            long avoid = PriorityOrder.AVOID_LADDER[rank];
            long fourAvoidMinusThreeUnit = 4 * avoid - 3 * unit; // = 4*(avoid - 0.75*unit)
            assertThat(Math.abs(fourAvoidMinusThreeUnit))
                    .as("AVOID_LADDER[%d]=%d must be within +/-5 of 0.75 x UNIT_LADDER[%d]=%d", rank, avoid, rank, unit)
                    .isLessThanOrEqualTo(20L); // 4*5
        }
    }

    // ─────────────────────────────────────────────────────────────────── (d) reversal sanity

    @Test
    void reversedOrderMakesLevelDominateAFriendWishPerBand() {
        List<Priority> reversed = List.of(Priority.LEVEL, Priority.PREFERRED_TIME, Priority.PREVIOUS_GROUP, Priority.TRAIN_TOGETHER);
        Map<String, Integer> weights = PriorityOrder.weightsFor(reversed);

        int levelBalance = weights.get(ConstraintKeys.LEVEL_BALANCE);
        int sameGroupSoft = weights.get(ConstraintKeys.SAME_GROUP_SOFT);
        assertThat(levelBalance).isEqualTo(340);
        assertThat(sameGroupSoft).isEqualTo(600);

        int oneBandCost = DEFAULT_BAND_UNITS * levelBalance;
        assertThat(oneBandCost).isEqualTo(2380);
        // Level dominates a single wish per band under reversal (340x7=2380 > 600) - this is the
        // per-band dominance relationship, not a claim that pre-v0.6.0 behavior is reproduced in
        // every other respect (unrelated constraints, ladder shape, etc. are unaffected by rank order).
        assertThat(oneBandCost)
                .as("level dominates a single wish per band under reversal (340x7=2380 > 600)")
                .isGreaterThan(sameGroupSoft);
    }

    // ────────────────────────────────────────────────────────── (e) ladder bounds / monotonicity

    @Test
    void everyLadderValueFitsWithinWeightLimitsAndIsStrictlyDecreasing() {
        int uiPresetCeiling = WeightLimits.MAX_WEIGHT / 4; // the frontend's x4 preset must fit under MAX_WEIGHT.

        assertLadderIsValid(PriorityOrder.UNIT_LADDER, uiPresetCeiling);
        assertLadderIsValid(PriorityOrder.LEVEL_LADDER, uiPresetCeiling);
        assertLadderIsValid(PriorityOrder.ORDER_LADDER, uiPresetCeiling);
        assertLadderIsValid(PriorityOrder.AVOID_LADDER, uiPresetCeiling);
    }

    private static void assertLadderIsValid(int[] ladder, int uiPresetCeiling) {
        assertThat(ladder).hasSize(4);
        for (int i = 0; i < ladder.length; i++) {
            assertThat(ladder[i]).as("ladder[%d] >= MIN_WEIGHT", i).isGreaterThanOrEqualTo(WeightLimits.MIN_WEIGHT);
            assertThat(ladder[i]).as("ladder[%d] <= MAX_WEIGHT/4", i).isLessThanOrEqualTo(uiPresetCeiling);
            if (i > 0) {
                assertThat(ladder[i]).as("ladder strictly decreasing at index %d", i).isLessThan(ladder[i - 1]);
            }
        }
    }
}
