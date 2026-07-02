package se.klubb.groupplanner.solver.constraints;

import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.common.time.TimeKey;
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.solver.domain.TrainingBlock;

/** §10.1 trainingBlockCapacity, §10.3 groupMaxSizeHard (docs/design/04-solver.md §4). */
class CapacityConstraintsTest {

    private final ConstraintVerifier<GroupPlanConstraintProvider, GroupPlanSolution> verifier = ConstraintVerifier.build(
            new GroupPlanConstraintProvider(), GroupPlanSolution.class, PlayerAssignment.class, GroupSchedule.class, CoachSlot.class);

    @Test
    void twoGroupsOnTheSameTrainingBlockPenalizesOnce() {
        Group g1 = group(1L, 1);
        Group g2 = group(2L, 2);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "Torsdag 18.00, Bana 1", 1L);
        GroupSchedule gs1 = new GroupSchedule(1L, g1, block, false);
        GroupSchedule gs2 = new GroupSchedule(2L, g2, block, false);

        verifier.verifyThat(GroupPlanConstraintProvider::trainingBlockCapacity)
                .given(gs1, gs2)
                .penalizes(1);
    }

    @Test
    void twoGroupsOnDifferentTrainingBlocksHasNoImpact() {
        Group g1 = group(1L, 1);
        Group g2 = group(2L, 2);
        TrainingBlock block1 = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "A", 1L);
        TrainingBlock block2 = new TrainingBlock(2L, 2L, "Bana 2", timeKey(18 * 60, 19 * 60 + 30), "B", 1L);
        GroupSchedule gs1 = new GroupSchedule(1L, g1, block1, false);
        GroupSchedule gs2 = new GroupSchedule(2L, g2, block2, false);

        verifier.verifyThat(GroupPlanConstraintProvider::trainingBlockCapacity)
                .given(gs1, gs2)
                .hasNoImpact();
    }

    @Test
    void fourPlayersInMaxThreeGroupPenalizesByOne() {
        Group g = new Group(1L, "Grupp 1", 1, 2, 3, 3, 1, 0, 100_000);
        verifier.verifyThat(GroupPlanConstraintProvider::groupMaxSizeHard)
                .given(player(1, g), player(2, g), player(3, g), player(4, g))
                .penalizesBy(1);
    }

    @Test
    void groupAtExactlyMaxSizeHasNoImpact() {
        Group g = new Group(1L, "Grupp 1", 1, 2, 3, 3, 1, 0, 100_000);
        verifier.verifyThat(GroupPlanConstraintProvider::groupMaxSizeHard)
                .given(player(1, g), player(2, g), player(3, g))
                .hasNoImpact();
    }

    @Test
    void waitlistedPlayersAreExcludedFromGroupMaxSizeHard() {
        // forEach(PlayerAssignment) excludes null-group (waitlisted) entities (verified semantics,
        // design §10.11's own note) - a waitlisted player must never count against a group's size.
        Group g = new Group(1L, "Grupp 1", 1, 2, 3, 3, 1, 0, 100_000);
        verifier.verifyThat(GroupPlanConstraintProvider::groupMaxSizeHard)
                .given(player(1, g), player(2, g), player(3, g), player(4, null))
                .hasNoImpact();
    }

    private static Group group(long id, int order) {
        return new Group(id, "Grupp " + order, order, 4, 5, 6, 1, 0, 100_000);
    }

    private static TimeKey timeKey(int startMin, int endMin) {
        return new TimeKey(TimeKey.NO_DATE, 4, startMin, endMin);
    }

    private static PlayerAssignment player(long id, Group group) {
        return new PlayerAssignment(id, id, "P" + id, 60_000, 3, null, new long[0], new long[0], group, false);
    }
}
