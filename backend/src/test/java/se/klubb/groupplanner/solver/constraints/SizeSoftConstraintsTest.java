package se.klubb.groupplanner.solver.constraints;

import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.solver.constraints.Justifications.GroupSizeDeviationJustification;
import se.klubb.groupplanner.solver.constraints.Justifications.GroupUnderMinJustification;
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;

/** §10.4 groupSizeTarget (+ groupSizeTargetEmpty complement), §10.5 groupMinSizeSoft (+
 * groupMinSizeEmpty complement) — docs/design/04-solver.md §4 rows 10.4/10.5. */
class SizeSoftConstraintsTest {

    private final ConstraintVerifier<GroupPlanConstraintProvider, GroupPlanSolution> verifier = ConstraintVerifier.build(
            new GroupPlanConstraintProvider(), GroupPlanSolution.class, PlayerAssignment.class, GroupSchedule.class, CoachSlot.class);

    @Test
    void eightPlayersInTargetTenGroupPenalizesByTwo() {
        Group g = group(1, 8, 10, 12);
        verifier.verifyThat(GroupPlanConstraintProvider::groupSizeTarget)
                .given(player(1, g), player(2, g), player(3, g), player(4, g), player(5, g), player(6, g), player(7, g), player(8, g))
                .penalizesBy(2);
    }

    @Test
    void groupAtExactlyTargetSizeHasNoImpact() {
        Group g = group(1, 8, 10, 12);
        verifier.verifyThat(GroupPlanConstraintProvider::groupSizeTarget)
                .given(player(1, g), player(2, g), player(3, g), player(4, g), player(5, g), player(6, g), player(7, g), player(8, g),
                        player(9, g), player(10, g))
                .hasNoImpact();
    }

    @Test
    void justifiesWithGroupSizeDeviationJustification() {
        Group g = group(1, 8, 10, 12);
        verifier.verifyThat(GroupPlanConstraintProvider::groupSizeTarget)
                .given(player(1, g))
                .justifiesWith(new GroupSizeDeviationJustification(1L, 1, 10));
    }

    @Test
    void emptyGroupTargetTenPenalizesByTen() {
        Group g = group(1, 8, 10, 12);
        Group other = group(2, 8, 10, 12);
        verifier.verifyThat(GroupPlanConstraintProvider::groupSizeTargetEmpty)
                .given(g, other, player(1, other))
                .penalizesBy(10);
    }

    @Test
    void nonEmptyGroupHasNoImpactOnEmptyComplement() {
        Group g = group(1, 8, 10, 12);
        verifier.verifyThat(GroupPlanConstraintProvider::groupSizeTargetEmpty)
                .given(g, player(1, g))
                .hasNoImpact();
    }

    @Test
    void sevenPlayersInMinNineGroupPenalizesByTwo() {
        Group g = group(1, 9, 10, 12);
        verifier.verifyThat(GroupPlanConstraintProvider::groupMinSizeSoft)
                .given(player(1, g), player(2, g), player(3, g), player(4, g), player(5, g), player(6, g), player(7, g))
                .penalizesBy(2);
    }

    @Test
    void groupAtMinSizeHasNoImpact() {
        Group g = group(1, 9, 10, 12);
        PlayerAssignment[] nine = new PlayerAssignment[9];
        for (int i = 0; i < 9; i++) {
            nine[i] = player(i + 1, g);
        }
        verifier.verifyThat(GroupPlanConstraintProvider::groupMinSizeSoft)
                .given(nine)
                .hasNoImpact();
    }

    @Test
    void justifiesWithGroupUnderMinJustification() {
        Group g = group(1, 9, 10, 12);
        verifier.verifyThat(GroupPlanConstraintProvider::groupMinSizeSoft)
                .given(player(1, g))
                .justifiesWith(new GroupUnderMinJustification(1L, 1, 9));
    }

    @Test
    void emptyGroupWithPositiveMinSizePenalizesByMinSize() {
        Group g = group(1, 9, 10, 12);
        Group other = group(2, 9, 10, 12);
        verifier.verifyThat(GroupPlanConstraintProvider::groupMinSizeEmpty)
                .given(g, other, player(1, other))
                .penalizesBy(9);
    }

    @Test
    void emptyGroupWithZeroMinSizeHasNoImpact() {
        Group g = group(1, 0, 10, 12);
        verifier.verifyThat(GroupPlanConstraintProvider::groupMinSizeEmpty)
                .given(g)
                .hasNoImpact();
    }

    private static Group group(int order, int min, int target, int max) {
        return new Group(order, "Grupp " + order, order, min, target, max, 1, 0, 100_000);
    }

    private static PlayerAssignment player(long id, Group group) {
        return new PlayerAssignment(id, id, "P" + id, 60_000, 3, null, new long[0], new long[0], group, false);
    }
}
