package se.klubb.groupplanner.solver.constraints;

import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.solver.constraints.Justifications.ContinuityJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.GroupOrderInversionJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.LevelSpreadJustification;
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;

/** §10.6 levelBalance, §10.7 groupOrderByLevel, §10.8 previousGroupContinuity — docs/design/04
 * -solver.md §4. Level values below are exact multiples of 100 (levelScaled) so SAD/mean-diff
 * points are exact integers with no floorDiv rounding surprises, matching the design table's own
 * worked examples. */
class LevelSoftConstraintsTest {

    private final ConstraintVerifier<GroupPlanConstraintProvider, GroupPlanSolution> verifier = ConstraintVerifier.build(
            new GroupPlanConstraintProvider(), GroupPlanSolution.class, PlayerAssignment.class, GroupSchedule.class, CoachSlot.class);

    // ─────────────────────────────────────────────────────────────────── §10.6 levelBalance

    @Test
    void fixedLevelsProduceExactSpreadUnits() {
        // levels {600, 640, 700} scaled: {60000, 64000, 70000}; mean = floorDiv(194000,3) = 64666
        // SAD = |60000-64666| + |64000-64666| + |70000-64666| = 4666+666+5334 = 10666
        // matchWeight is in spread units (LevelMath.SPREAD_UNIT_SCALED = 1000): floorDiv(10666, 1000) = 10
        // (sadPoints, the whole-level-points figure used for display, stays floorDiv(10666, 100) = 106 -
        // see justifiesWithLevelSpreadJustification below, and LevelMathTest, for that unchanged value.)
        Group g = group(1);
        verifier.verifyThat(GroupPlanConstraintProvider::levelBalance)
                .given(player(1, 60_000, g), player(2, 64_000, g), player(3, 70_000, g))
                .penalizesBy(10);
    }

    @Test
    void justifiesWithLevelSpreadJustification() {
        // Same {60000,64000,70000} fixture as above: the justification still carries sadPoints (106)
        // and meanScaled (64666), unaffected by the matchWeight unit temper - pins display parity.
        Group g = group(1);
        verifier.verifyThat(GroupPlanConstraintProvider::levelBalance)
                .given(player(1, 60_000, g), player(2, 64_000, g), player(3, 70_000, g))
                .justifiesWith(new LevelSpreadJustification(1L, 106, 64666L));
    }

    @Test
    void perfectlyLevelGroupHasNoImpact() {
        Group g = group(1);
        verifier.verifyThat(GroupPlanConstraintProvider::levelBalance)
                .given(player(1, 60_000, g), player(2, 60_000, g), player(3, 60_000, g))
                .hasNoImpact();
    }

    // ─────────────────────────────────────────────────────────────── §10.7 groupOrderByLevel

    @Test
    void group1MeanFiveHundredBelowGroup2MeanSevenHundredPenalizes() {
        // Grupp 1 (order 1, "better") mean 500 < Grupp 2 (order 2) mean 700 -> inversion.
        // v0.6.0 milestone B6: matchWeight is in spread units (LevelMath.SPREAD_UNIT_SCALED = 1000),
        // the same unit levelBalance uses - was whole level points (denominator *100) before.
        // numerator = 70000*1 - 50000*1 = 20000; denominator = 1*1*1000 = 1000 -> 20 + 1 = 21.
        Group g1 = group(1);
        Group g2 = group(2);
        verifier.verifyThat(GroupPlanConstraintProvider::groupOrderByLevel)
                .given(player(1, 50_000, g1), player(2, 70_000, g2))
                .penalizesBy(21);
    }

    @Test
    void correctlyOrderedAdjacentGroupsHaveNoImpact() {
        Group g1 = group(1);
        Group g2 = group(2);
        verifier.verifyThat(GroupPlanConstraintProvider::groupOrderByLevel)
                .given(player(1, 70_000, g1), player(2, 50_000, g2))
                .hasNoImpact();
    }

    @Test
    void nonAdjacentGroupsAreNeverCompared() {
        // Grupp 1 and Grupp 3 differ by more than 1 in groupOrder - the design's own adjacent-only
        // scope (§4 row 10.7) means no join tuple is even formed, regardless of an inversion.
        Group g1 = group(1);
        Group g3 = new Group(3, "Grupp 3", 3, 4, 5, 6, 1, 0, 100_000);
        verifier.verifyThat(GroupPlanConstraintProvider::groupOrderByLevel)
                .given(player(1, 10_000, g1), player(3, 90_000, g3))
                .hasNoImpact();
    }

    @Test
    void justifiesWithGroupOrderInversionJustification() {
        Group g1 = group(1);
        Group g2 = group(2);
        verifier.verifyThat(GroupPlanConstraintProvider::groupOrderByLevel)
                .given(player(1, 50_000, g1), player(2, 70_000, g2))
                .justifiesWith(new GroupOrderInversionJustification(1L, 2L, 21));
    }

    // ─────────────────────────────────────────────────────────── §10.8 previousGroupContinuity

    @Test
    void previousGroupFourPlacedInGroupSevenPenalizesByOne() {
        // v0.6.0 milestone B6: continuity became capped binary (min(abs(diff), 1)) instead of
        // per-step distance - a 3-step move (4 -> 7) still only costs matchWeight 1, same as a
        // 1-step move, so the ladder's rank ordering can't be inverted by drift magnitude.
        Group g7 = new Group(7, "Grupp 7", 7, 4, 5, 6, 1, 0, 100_000);
        PlayerAssignment p = new PlayerAssignment(1L, 1L, "P1", 60_000, 3, 4, new long[0], new long[0], g7, false);
        verifier.verifyThat(GroupPlanConstraintProvider::previousGroupContinuity)
                .given(p)
                .penalizesBy(1);
    }

    @Test
    void previousGroupOneStepAwayAlsoPenalizesByOne() {
        // Same binary cap: a 1-step move (4 -> 5) costs the SAME matchWeight (1) as any other
        // nonzero move - pins that the binary cap applies at the smallest possible drift too, not
        // just the large one above.
        Group g5 = new Group(5, "Grupp 5", 5, 4, 5, 6, 1, 0, 100_000);
        PlayerAssignment p = new PlayerAssignment(1L, 1L, "P1", 60_000, 3, 4, new long[0], new long[0], g5, false);
        verifier.verifyThat(GroupPlanConstraintProvider::previousGroupContinuity)
                .given(p)
                .penalizesBy(1);
    }

    @Test
    void placedInSamePreviousGroupHasNoImpact() {
        Group g4 = new Group(4, "Grupp 4", 4, 4, 5, 6, 1, 0, 100_000);
        PlayerAssignment p = new PlayerAssignment(1L, 1L, "P1", 60_000, 3, 4, new long[0], new long[0], g4, false);
        verifier.verifyThat(GroupPlanConstraintProvider::previousGroupContinuity)
                .given(p)
                .hasNoImpact();
    }

    @Test
    void playerWithNoPreviousGroupHasNoImpact() {
        Group g4 = new Group(4, "Grupp 4", 4, 4, 5, 6, 1, 0, 100_000);
        PlayerAssignment p = new PlayerAssignment(1L, 1L, "P1", 60_000, 3, null, new long[0], new long[0], g4, false);
        verifier.verifyThat(GroupPlanConstraintProvider::previousGroupContinuity)
                .given(p)
                .hasNoImpact();
    }

    @Test
    void justifiesWithContinuityJustification() {
        Group g7 = new Group(7, "Grupp 7", 7, 4, 5, 6, 1, 0, 100_000);
        PlayerAssignment p = new PlayerAssignment(1L, 1L, "P1", 60_000, 3, 4, new long[0], new long[0], g7, false);
        verifier.verifyThat(GroupPlanConstraintProvider::previousGroupContinuity)
                .given(p)
                .justifiesWith(new ContinuityJustification(1L, 4, 7));
    }

    private static Group group(int order) {
        return new Group(order, "Grupp " + order, order, 4, 5, 6, 1, 0, 100_000);
    }

    private static PlayerAssignment player(long id, int levelScaled, Group group) {
        return new PlayerAssignment(id, id, "P" + id, levelScaled, 3, null, new long[0], new long[0], group, false);
    }
}
