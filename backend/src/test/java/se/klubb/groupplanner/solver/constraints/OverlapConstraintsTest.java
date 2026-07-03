package se.klubb.groupplanner.solver.constraints;

import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.common.time.TimeKey;
import se.klubb.groupplanner.solver.domain.CoachFact;
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.solver.domain.TrainingBlock;

/** §10.15 coachNoOverlap, §10.16 playerNoOverlap, §10.17 coachCannotTrainAndCoachSameTime. */
class OverlapConstraintsTest {

    private final ConstraintVerifier<GroupPlanConstraintProvider, GroupPlanSolution> verifier = ConstraintVerifier.build(
            new GroupPlanConstraintProvider(), GroupPlanSolution.class, PlayerAssignment.class, GroupSchedule.class, CoachSlot.class);

    @Test
    void coachDoubleBookedAtOverlappingTimesPenalizes() {
        CoachFact coach = new CoachFact(1L, 100L, "Coach", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE, new long[0], new long[0]);
        Group g1 = group(1);
        Group g2 = group(2);
        CoachSlot cs1 = new CoachSlot(CoachSlot.syntheticId(1, 0), g1, 0, coach, false);
        CoachSlot cs2 = new CoachSlot(CoachSlot.syntheticId(2, 0), g2, 0, coach, false);
        TrainingBlock block1 = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "A", 1L);
        TrainingBlock block2 = new TrainingBlock(2L, 2L, "Bana 2", timeKey(18 * 60, 19 * 60 + 30), "B", 1L);
        GroupSchedule gs1 = new GroupSchedule(1L, g1, block1, false);
        GroupSchedule gs2 = new GroupSchedule(2L, g2, block2, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachNoOverlap)
                .given(cs1, cs2, gs1, gs2)
                .penalizes(1);
    }

    @Test
    void coachAtTwoNonOverlappingTimesHasNoImpact() {
        CoachFact coach = new CoachFact(1L, 100L, "Coach", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE, new long[0], new long[0]);
        Group g1 = group(1);
        Group g2 = group(2);
        CoachSlot cs1 = new CoachSlot(CoachSlot.syntheticId(1, 0), g1, 0, coach, false);
        CoachSlot cs2 = new CoachSlot(CoachSlot.syntheticId(2, 0), g2, 0, coach, false);
        TrainingBlock block1 = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "A", 1L);
        TrainingBlock block2 = new TrainingBlock(2L, 1L, "Bana 1", timeKey(19 * 60 + 30, 21 * 60), "B", 2L);
        GroupSchedule gs1 = new GroupSchedule(1L, g1, block1, false);
        GroupSchedule gs2 = new GroupSchedule(2L, g2, block2, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachNoOverlap)
                .given(cs1, cs2, gs1, gs2)
                .hasNoImpact();
    }

    @Test
    void samePersonDoubleBookedAtOverlappingTimesPenalizes() {
        Group g1 = group(1);
        Group g2 = group(2);
        PlayerAssignment a = new PlayerAssignment(1L, 999L, "Kalle-A", 60_000, 3, null, new long[0], new long[0], g1, false);
        PlayerAssignment b = new PlayerAssignment(2L, 999L, "Kalle-B", 60_000, 3, null, new long[0], new long[0], g2, false);
        TrainingBlock block1 = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "A", 1L);
        TrainingBlock block2 = new TrainingBlock(2L, 2L, "Bana 2", timeKey(18 * 60, 19 * 60 + 30), "B", 1L);
        GroupSchedule gs1 = new GroupSchedule(1L, g1, block1, false);
        GroupSchedule gs2 = new GroupSchedule(2L, g2, block2, false);

        verifier.verifyThat(GroupPlanConstraintProvider::playerNoOverlap)
                .given(a, b, gs1, gs2)
                .penalizes(1);
    }

    @Test
    void differentPersonsInTwoGroupsAtOverlappingTimesHasNoImpact() {
        Group g1 = group(1);
        Group g2 = group(2);
        PlayerAssignment a = new PlayerAssignment(1L, 999L, "Kalle", 60_000, 3, null, new long[0], new long[0], g1, false);
        PlayerAssignment b = new PlayerAssignment(2L, 888L, "Anna", 60_000, 3, null, new long[0], new long[0], g2, false);
        TrainingBlock block1 = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "A", 1L);
        TrainingBlock block2 = new TrainingBlock(2L, 2L, "Bana 2", timeKey(18 * 60, 19 * 60 + 30), "B", 1L);
        GroupSchedule gs1 = new GroupSchedule(1L, g1, block1, false);
        GroupSchedule gs2 = new GroupSchedule(2L, g2, block2, false);

        verifier.verifyThat(GroupPlanConstraintProvider::playerNoOverlap)
                .given(a, b, gs1, gs2)
                .hasNoImpact();
    }

    @Test
    void coachPlaysWhileCoachingAtOverlappingTimePenalizes() {
        CoachFact coach = new CoachFact(1L, 200L, "Anna", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE, new long[0], new long[0]);
        Group coachGroup = group(1);
        Group playGroup = group(2);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), coachGroup, 0, coach, false);
        PlayerAssignment pa = new PlayerAssignment(1L, 200L, "Anna", 60_000, 3, null, new long[0], new long[0], playGroup, false);
        TrainingBlock coachBlock = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "Coach@18.00", 1L);
        TrainingBlock playBlock = new TrainingBlock(2L, 2L, "Bana 2", timeKey(18 * 60, 19 * 60 + 30), "Play@18.00", 1L);
        GroupSchedule gsCoach = new GroupSchedule(1L, coachGroup, coachBlock, false);
        GroupSchedule gsPlay = new GroupSchedule(2L, playGroup, playBlock, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachCannotTrainAndCoachSameTime)
                .given(cs, pa, gsCoach, gsPlay)
                .penalizes(1);
    }

    @Test
    void coachPlaysAtANonOverlappingTimeHasNoImpact() {
        CoachFact coach = new CoachFact(1L, 200L, "Anna", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE, new long[0], new long[0]);
        Group coachGroup = group(1);
        Group playGroup = group(2);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), coachGroup, 0, coach, false);
        PlayerAssignment pa = new PlayerAssignment(1L, 200L, "Anna", 60_000, 3, null, new long[0], new long[0], playGroup, false);
        TrainingBlock coachBlock = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "Coach@18.00", 1L);
        TrainingBlock playBlock = new TrainingBlock(2L, 1L, "Bana 1", timeKey(19 * 60 + 30, 21 * 60), "Play@19.30", 2L);
        GroupSchedule gsCoach = new GroupSchedule(1L, coachGroup, coachBlock, false);
        GroupSchedule gsPlay = new GroupSchedule(2L, playGroup, playBlock, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachCannotTrainAndCoachSameTime)
                .given(cs, pa, gsCoach, gsPlay)
                .hasNoImpact();
    }

    private static Group group(int order) {
        return new Group(order, "Grupp " + order, order, 4, 5, 6, 1, 0, 100_000);
    }

    private static TimeKey timeKey(int startMin, int endMin) {
        return new TimeKey(TimeKey.NO_DATE, 4, startMin, endMin);
    }
}
