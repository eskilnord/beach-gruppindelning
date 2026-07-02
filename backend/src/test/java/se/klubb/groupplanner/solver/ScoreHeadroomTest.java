package se.klubb.groupplanner.solver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Overflow-headroom guard (docs/design/04-solver.md §3.4): computes the analytic worst-case {@code
 * HardMediumSoftLongScore} component totals from problem-size/weight metadata (never from a live
 * solve) and asserts they stay far below {@code Long.MAX_VALUE} — a build-breaking early warning if
 * a future UI weight-cap change or dataset-size change ever approaches the danger zone.
 *
 * <p>The worst-case numbers replicate the design doc's own §3.4 analysis verbatim (130 players, 12
 * groups, max UI weight 10 000) so this test stays meaningful once M6b's SOFT constraints
 * (levelBalance, groupOrderByLevel, pair wishes, ...) land — it is written against the full design
 * envelope, not just the HARD+MEDIUM subset implemented so far in M6a.
 */
class ScoreHeadroomTest {

    private static final int MAX_PLAYERS = 130;
    private static final int MAX_GROUPS = 12;
    // The ceiling ACTUALLY ENFORCED at the write paths (review fix 5): ConstraintWeightService and
    // FieldDefinitionValidator both reject weights above WeightLimits.MAX_WEIGHT, so this analysis
    // and the enforcement can never silently drift apart.
    private static final long MAX_UI_WEIGHT = se.klubb.groupplanner.fields.WeightLimits.MAX_WEIGHT;
    private static final long ONE_E15 = 1_000_000_000_000_000L;

    @Test
    void analyticWorstCaseStaysWellBelowOneQuadrillion() {
        long softLevelBalance = (long) MAX_GROUPS * 11_000L * MAX_UI_WEIGHT; // <= 1.32e9 (design: ~1.3e9)
        long softPairWishes = 1_300L * MAX_UI_WEIGHT; // <= 1.3e7
        long softOrdering = 11L * 1_000L * MAX_UI_WEIGHT; // <= 1.1e8
        long softTotal = softLevelBalance + softPairWishes + softOrdering;
        assertThat(softTotal).isLessThan(10_000_000_000L); // design: "Total << 1e10"

        long mediumTotal = (long) MAX_PLAYERS * 5L * MAX_UI_WEIGHT; // priority 1..5, weight-overridable
        assertThat(mediumTotal).isLessThan(10_000_000L);

        long hardTotal = 10_000L * MAX_UI_WEIGHT; // design: "Hard <= ~10 000 x 10 000 = 1e8"
        assertThat(hardTotal).isLessThan(1_000_000_000L);

        long worstCase = hardTotal + mediumTotal + softTotal;
        assertThat(worstCase).isLessThan(ONE_E15);

        long headroomFactor = Long.MAX_VALUE / Math.max(1, worstCase);
        assertThat(headroomFactor).isGreaterThan(100_000_000L); // design: "headroom factor > 1e8"
    }

    @Test
    void hardMediumSoftLongScoreLevelsCannotOverflowIndependently() {
        // Sanity: even a single level's worst case alone is nowhere near Long.MAX_VALUE, so no
        // individual score-level accumulation (hard, medium, or soft summed separately across a
        // whole solve) can silently wrap around.
        long singleLevelWorstCase = (long) MAX_GROUPS * 11_000L * MAX_UI_WEIGHT;
        assertThat(Long.MAX_VALUE - singleLevelWorstCase).isPositive();
        assertThat(singleLevelWorstCase).isLessThan(Long.MAX_VALUE / 1000);
    }
}
