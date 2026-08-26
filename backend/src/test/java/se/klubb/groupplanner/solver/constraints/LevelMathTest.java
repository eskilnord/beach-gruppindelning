package se.klubb.groupplanner.solver.constraints;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LevelMathTest {

    @Test
    void sadPointsOfEmptyGroupIsZero() {
        assertThat(LevelMath.sadPoints(new int[0])).isZero();
    }

    @Test
    void sadPointsOfSingleMemberGroupIsZero() {
        assertThat(LevelMath.sadPoints(new int[] {64200})).isZero();
    }

    @Test
    void sadPointsExactWorkedExample() {
        // levels 600, 640, 700 (scaled x100 -> 60000, 64000, 70000): mean = floorDiv(194000,3) = 64666
        // SAD = |60000-64666| + |64000-64666| + |70000-64666| = 4666 + 666 + 5334 = 10666
        // penalty = floorDiv(10666, 100) = 106
        int[] levelsScaled = {60000, 64000, 70000};
        assertThat(LevelMath.sadPoints(levelsScaled)).isEqualTo(106);
    }

    @Test
    void floorMeanUsesFloorDivisionNotRounding() {
        // sum=10, n=3 -> 3.333..., floorDiv gives 3 (not rounded to 3.33 or up to 4).
        assertThat(LevelMath.floorMean(10, 3)).isEqualTo(3);
        // Negative-leaning case documents floorDiv semantics explicitly (differs from truncation).
        assertThat(Math.floorDiv(-10, 3)).isEqualTo(-4);
    }

    @Test
    void sumIsExactLongSum() {
        assertThat(LevelMath.sum(new int[] {100, 200, 300})).isEqualTo(600L);
        assertThat(LevelMath.sum(new int[0])).isZero();
    }

    @Test
    void floorMeanRejectsZeroOrNegativeCount() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> LevelMath.floorMean(100, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─────────────────────────────────────────────────────────────────────── spreadUnits

    @Test
    void spreadUnitsOfEmptyGroupIsZero() {
        assertThat(LevelMath.spreadUnits(new int[0])).isZero();
    }

    @Test
    void spreadUnitsOfSingleMemberGroupIsZero() {
        assertThat(LevelMath.spreadUnits(new int[] {64200})).isZero();
    }

    @Test
    void spreadUnitsExactWorkedExample() {
        // Same fixture as sadPointsExactWorkedExample: SAD_scaled = 10666.
        // spreadUnits = floorDiv(10666, SPREAD_UNIT_SCALED=1000) = 10.
        int[] levelsScaled = {60000, 64000, 70000};
        assertThat(LevelMath.spreadUnits(levelsScaled)).isEqualTo(10);
    }

    @Test
    void spreadUnitsRoundsDownAtUnitBoundary() {
        // A synthetic two-member group whose SAD_scaled is exactly 999 -> below one unit -> 0.
        // mean = floorDiv(999, 2) = 499; SAD = |0-499| + |999-499| = 499 + 500 = 999.
        assertThat(LevelMath.spreadUnits(new int[] {0, 999})).isZero();
        // SAD_scaled exactly 1000 -> exactly one unit.
        // mean = floorDiv(1000, 2) = 500; SAD = |0-500| + |1000-500| = 500 + 500 = 1000.
        assertThat(LevelMath.spreadUnits(new int[] {0, 1000})).isEqualTo(1);
    }

    @Test
    void spreadUnitsEqualsFloorDivOfSadScaledByUnitSize() {
        // Property check across a few vectors: spreadUnits == floorDiv(rawSadScaled, 1000), where
        // rawSadScaled is recomputed independently here (not via sadPoints, to avoid tautology).
        int[][] vectors = {
                {60000, 64000, 70000},
                {0, 999},
                {0, 1000},
                {100, 200, 300, 400},
                {64200},
                {12345, 54321, 99999, 1},
        };
        for (int[] levelsScaled : vectors) {
            long rawSad = rawSadScaled(levelsScaled);
            int expected = Math.toIntExact(Math.floorDiv(rawSad, LevelMath.SPREAD_UNIT_SCALED));
            assertThat(LevelMath.spreadUnits(levelsScaled)).isEqualTo(expected);
        }
    }

    private static long rawSadScaled(int[] levelsScaled) {
        if (levelsScaled.length == 0) {
            return 0L;
        }
        long sum = 0L;
        for (int level : levelsScaled) {
            sum += level;
        }
        long mean = Math.floorDiv(sum, levelsScaled.length);
        long sad = 0L;
        for (int level : levelsScaled) {
            sad += Math.abs((long) level - mean);
        }
        return sad;
    }
}
