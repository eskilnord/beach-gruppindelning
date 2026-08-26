package se.klubb.groupplanner.explain;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.explain.ExplanationDtos.WeightBreakEvenView;
import se.klubb.groupplanner.solver.constraints.ConstraintKeys;

/**
 * M-E3 pure-function tests for {@link WeightBreakEven} — see that class's javadoc for the closed-form
 * derivation ({@code C = Σ_{j!=k} units_j·w_j}, {@code AT_MOST = floorDiv(C, -magnitudeUnits)},
 * {@code AT_LEAST = ceilDiv(-C, magnitudeUnits)}). Each test builds a minimal 2-entry {@code
 * perConstraint} list: the key under test, plus a "fixed" filler entry ({@code units=0}, so it never
 * gets its own row) whose {@code scoreDelta} directly sets {@code C} for the tested key (with only two
 * entries, {@code C = totalSoft - ownSoft} collapses to exactly the filler's own softScore).
 */
class WeightBreakEvenTest {

    private static MoveProbe.ConstraintDelta delta(String key, long softDelta, long units) {
        return new MoveProbe.ConstraintDelta(key, HardMediumSoftLongScore.ofSoft(softDelta), units, true);
    }

    /** A filler contributor: fixes {@code C} for whatever OTHER key is under test, but never produces
     * its own break-even row ({@code units=0}). */
    private static MoveProbe.ConstraintDelta filler(long softDelta) {
        return delta(ConstraintKeys.GROUP_SIZE_TARGET, softDelta, 0);
    }

    private static Map<String, HardMediumSoftLongScore> weights(String key, long magnitude) {
        return Map.of(key, HardMediumSoftLongScore.ofSoft(magnitude), ConstraintKeys.GROUP_SIZE_TARGET, HardMediumSoftLongScore.ofSoft(800));
    }

    private static WeightBreakEvenView rowFor(List<WeightBreakEvenView> rows, String key) {
        return rows.stream().filter(r -> r.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("No row for " + key + " in " + rows));
    }

    // ─────────────────────────────────────────────────────────────────────── floor rounding + units==0 omitted

    /** sameGroupSoft (PENALIZE, units=3, magnitudeUnits=-3) with C=10 (filler softDelta=10): raw
     * 10/3 = 3.33, floors to 3 (NOT rounds to 3.33 or ceils to 4 — {@code total(3) = 10-9 = 1 >= 0}
     * still non-worse, {@code total(4) = 10-12 = -2} already worse). The filler's own units=0 entry
     * must produce NO row at all. */
    @Test
    void atMostFloorsNonExactDivisionAndOmitsUnitsZeroKeys() {
        List<MoveProbe.ConstraintDelta> perConstraint = List.of(delta(ConstraintKeys.SAME_GROUP_SOFT, -9, 3), filler(10));
        List<WeightBreakEvenView> rows = WeightBreakEven.compute(perConstraint, weights(ConstraintKeys.SAME_GROUP_SOFT, 2400));

        assertThat(rows).extracting(WeightBreakEvenView::key).containsExactly(ConstraintKeys.SAME_GROUP_SOFT);
        WeightBreakEvenView row = rowFor(rows, ConstraintKeys.SAME_GROUP_SOFT);
        assertThat(row.direction()).isEqualTo("AT_MOST");
        assertThat(row.threshold()).isEqualTo(3);
        assertThat(row.impossibleReasonSv()).isNull();
        assertThat(row.currentWeight()).isEqualTo(2400);
        assertThat(row.messageSv()).contains("3");
    }

    // ─────────────────────────────────────────────────────────────────────── exact-zero boundary

    /** C=9, magnitudeUnits=-3: raw = 9/3 = 3 EXACTLY - {@code total(3) = 9-9 = 0}, which must still
     * count as the (inclusive) break-even threshold, not be excluded as "not quite enough". */
    @Test
    void thresholdMakingTheDeltaExactlyZeroIsStillTheBreakEven() {
        List<MoveProbe.ConstraintDelta> perConstraint = List.of(delta(ConstraintKeys.SAME_GROUP_SOFT, -50, 3), filler(9));
        List<WeightBreakEvenView> rows = WeightBreakEven.compute(perConstraint, weights(ConstraintKeys.SAME_GROUP_SOFT, 2400));

        WeightBreakEvenView row = rowFor(rows, ConstraintKeys.SAME_GROUP_SOFT);
        assertThat(row.direction()).isEqualTo("AT_MOST");
        assertThat(row.threshold()).isEqualTo(3);
        assertThat(row.impossibleReasonSv()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────── clamping, both ends

    @Test
    void atMostClampsImpossibleWhenEvenTheLowestAllowedWeightIsNotEnough() {
        // magnitudeUnits=-1, C=-5 -> raw = floorDiv(-5,1) = -5, below WeightLimits.MIN_WEIGHT (1).
        List<MoveProbe.ConstraintDelta> perConstraint = List.of(delta(ConstraintKeys.SAME_GROUP_SOFT, -2405, 1), filler(-5));
        List<WeightBreakEvenView> rows = WeightBreakEven.compute(perConstraint, weights(ConstraintKeys.SAME_GROUP_SOFT, 2400));

        WeightBreakEvenView row = rowFor(rows, ConstraintKeys.SAME_GROUP_SOFT);
        assertThat(row.direction()).isEqualTo("AT_MOST");
        assertThat(row.threshold()).isNull();
        assertThat(row.impossibleReasonSv()).isEqualTo("Inte ens den lägsta tillåtna vikten (1) räcker.");
    }

    @Test
    void atLeastClampsImpossibleWhenEvenTheHighestAllowedWeightIsNotEnough() {
        // reward key, magnitudeUnits=+1, C=-20000 -> raw = ceilDiv(20000,1) = 20000, above MAX_WEIGHT (10 000).
        // The tested row's OWN scoreDelta cancels out of C entirely (see class javadoc) - its magnitude is
        // arbitrary filler, but its SIGN must still agree with COACH_PREFERENCE_SOFT's REWARD direction
        // (positive), per the FIX-4 self-verifying sign assertion.
        List<MoveProbe.ConstraintDelta> perConstraint = List.of(delta(ConstraintKeys.COACH_PREFERENCE_SOFT, 19400, 1), filler(-20000));
        List<WeightBreakEvenView> rows = WeightBreakEven.compute(perConstraint, weights(ConstraintKeys.COACH_PREFERENCE_SOFT, 600));

        WeightBreakEvenView row = rowFor(rows, ConstraintKeys.COACH_PREFERENCE_SOFT);
        assertThat(row.direction()).isEqualTo("AT_LEAST");
        assertThat(row.threshold()).isNull();
        assertThat(row.impossibleReasonSv()).isEqualTo("Inte ens den högsta tillåtna vikten (10 000) räcker.");
    }

    // ─────────────────────────────────────────────────────────────────────── reward-sign key, in-range

    /** coachPreferenceSoft is a {@code .reward(...)} constraint (positive units convention): raising
     * its weight makes the total BETTER, so a positive {@code units} gives {@code magnitudeUnits > 0}
     * directly (no sign flip, unlike a PENALIZE key) - AT_LEAST, with a non-exact ceil-rounding
     * threshold ({@code raw = ceilDiv(101, 2) = 51}, not the truncated 50). */
    @Test
    void atLeastCeilsNonExactDivisionForARewardSignKey() {
        // With exactly two entries C always equals the filler's own softScore (the tested key's OWN
        // scoreDelta cancels out of C = totalSoft - ownSoft = (own + filler) - own = filler) - so
        // C = -101 here, and ceilDiv(101, 2) = 51 (101/2 = 50.5, ceiling to 51, not truncating to 50).
        List<MoveProbe.ConstraintDelta> perConstraint = List.of(delta(ConstraintKeys.COACH_PREFERENCE_SOFT, 40, 2), filler(-101));
        List<WeightBreakEvenView> rows = WeightBreakEven.compute(perConstraint, weights(ConstraintKeys.COACH_PREFERENCE_SOFT, 600));

        WeightBreakEvenView row = rowFor(rows, ConstraintKeys.COACH_PREFERENCE_SOFT);
        assertThat(row.direction()).isEqualTo("AT_LEAST");
        assertThat(row.threshold()).isEqualTo(51);
        assertThat(row.impossibleReasonSv()).isNull();
        assertThat(row.currentWeight()).isEqualTo(600);
    }

    // ─────────────────────────────────────────────────────────────────────── units==0 omitted (standalone)

    @Test
    void aKeyWithZeroUnitsNeverProducesARow() {
        List<MoveProbe.ConstraintDelta> perConstraint = List.of(delta(ConstraintKeys.SAME_GROUP_SOFT, 0, 0), filler(10));
        List<WeightBreakEvenView> rows = WeightBreakEven.compute(perConstraint, weights(ConstraintKeys.SAME_GROUP_SOFT, 2400));

        assertThat(rows).extracting(WeightBreakEvenView::key).doesNotContain(ConstraintKeys.SAME_GROUP_SOFT);
    }

    @Test
    void aKeyWithUnitsKnownFalseNeverProducesARow() {
        List<MoveProbe.ConstraintDelta> perConstraint = List.of(
                new MoveProbe.ConstraintDelta(ConstraintKeys.SAME_GROUP_SOFT, HardMediumSoftLongScore.ZERO, 0L, false), filler(10));
        List<WeightBreakEvenView> rows = WeightBreakEven.compute(perConstraint, weights(ConstraintKeys.SAME_GROUP_SOFT, 2400));

        assertThat(rows).extracting(WeightBreakEvenView::key).doesNotContain(ConstraintKeys.SAME_GROUP_SOFT);
    }
}
