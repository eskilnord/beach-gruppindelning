package se.klubb.groupplanner.solver.constraints;

import ai.timefold.solver.test.api.score.stream.ConstraintVerifier;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.common.time.TimeKey;
import se.klubb.groupplanner.solver.domain.CoachFact;
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.domain.CoachWish;
import se.klubb.groupplanner.solver.domain.CoachWishType;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.solver.domain.TrainingBlock;

/** §10.18 coachAvailabilityHard, §10.19 coachRequirementHard, coachMaxGroups (spec §13.1),
 * §10.21b coachWishRequired, §10.21c coachWishForbidden. */
class CoachConstraintsTest {

    private final ConstraintVerifier<GroupPlanConstraintProvider, GroupPlanSolution> verifier = ConstraintVerifier.build(
            new GroupPlanConstraintProvider(), GroupPlanSolution.class, PlayerAssignment.class, GroupSchedule.class, CoachSlot.class);

    @Test
    void coachUnavailableAtScheduledTimePenalizes() {
        CoachFact coach = new CoachFact(1L, 100L, "Coach", 50_000, 0, 100_000, new long[] {5L}, Integer.MAX_VALUE);
        Group g = group(1);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, coach, false);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", new TimeKey(TimeKey.NO_DATE, 4, 21 * 60, 22 * 60 + 30), "21.00", 5L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachAvailabilityHard)
                .given(cs, gs)
                .penalizes(1);
    }

    @Test
    void coachAvailableAtScheduledTimeHasNoImpact() {
        CoachFact coach = new CoachFact(1L, 100L, "Coach", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE);
        Group g = group(1);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, coach, false);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", new TimeKey(TimeKey.NO_DATE, 4, 21 * 60, 22 * 60 + 30), "21.00", 5L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachAvailabilityHard)
                .given(cs, gs)
                .hasNoImpact();
    }

    @Test
    void missingRequiredCoachSlotPenalizes() {
        Group g = group(1);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, null, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachRequirementHard)
                .given(cs)
                .penalizes(1);
    }

    @Test
    void filledRequiredCoachSlotHasNoImpact() {
        CoachFact coach = new CoachFact(1L, 100L, "Coach", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE);
        Group g = group(1);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, coach, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachRequirementHard)
                .given(cs)
                .hasNoImpact();
    }

    @Test
    void coachAssignedToMoreGroupsThanMaxPenalizesByOverflow() {
        CoachFact coach = new CoachFact(1L, 100L, "Coach", 50_000, 0, 100_000, new long[0], 1);
        Group g1 = group(1);
        Group g2 = group(2);
        CoachSlot cs1 = new CoachSlot(CoachSlot.syntheticId(1, 0), g1, 0, coach, false);
        CoachSlot cs2 = new CoachSlot(CoachSlot.syntheticId(2, 0), g2, 0, coach, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachMaxGroups)
                .given(cs1, cs2)
                .penalizesBy(1);
    }

    @Test
    void coachWithinMaxGroupsHasNoImpact() {
        CoachFact coach = new CoachFact(1L, 100L, "Coach", 50_000, 0, 100_000, new long[0], 1);
        Group g1 = group(1);
        CoachSlot cs1 = new CoachSlot(CoachSlot.syntheticId(1, 0), g1, 0, coach, false);

        verifier.verifyThat(GroupPlanConstraintProvider::coachMaxGroups)
                .given(cs1)
                .hasNoImpact();
    }

    @Test
    void requiredCoachAbsentPenalizes() {
        Group g = group(1);
        PlayerAssignment p = player(1, g);
        CoachWish wish = new CoachWish(1L, CoachWishType.MUST, 1L, 200L);

        verifier.verifyThat(GroupPlanConstraintProvider::coachWishRequired)
                .given(wish, p)
                .penalizes(1);
    }

    @Test
    void requiredCoachPresentHasNoImpact() {
        Group g = group(1);
        PlayerAssignment p = player(1, g);
        CoachFact coach = new CoachFact(1L, 200L, "Coach", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, coach, false);
        CoachWish wish = new CoachWish(1L, CoachWishType.MUST, 1L, 200L);

        verifier.verifyThat(GroupPlanConstraintProvider::coachWishRequired)
                .given(wish, p, cs)
                .hasNoImpact();
    }

    @Test
    void forbiddenCoachPresentPenalizes() {
        Group g = group(1);
        PlayerAssignment p = player(1, g);
        CoachFact coach = new CoachFact(1L, 200L, "Coach", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, coach, false);
        CoachWish wish = new CoachWish(1L, CoachWishType.CANNOT, 1L, 200L);

        verifier.verifyThat(GroupPlanConstraintProvider::coachWishForbidden)
                .given(wish, p, cs)
                .penalizes(1);
    }

    @Test
    void forbiddenCoachAbsentHasNoImpact() {
        Group g = group(1);
        PlayerAssignment p = player(1, g);
        CoachWish wish = new CoachWish(1L, CoachWishType.CANNOT, 1L, 200L);

        verifier.verifyThat(GroupPlanConstraintProvider::coachWishForbidden)
                .given(wish, p)
                .hasNoImpact();
    }

    private static Group group(int order) {
        return new Group(order, "Grupp " + order, order, 4, 5, 6, 1, 0, 100_000);
    }

    private static PlayerAssignment player(long id, Group group) {
        return new PlayerAssignment(id, id, "P" + id, 60_000, 3, null, new long[0], new long[0], group, false);
    }
}
