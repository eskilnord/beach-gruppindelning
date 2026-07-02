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
}
