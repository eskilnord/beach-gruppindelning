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

/** §10.9 timeAvailabilityHard (docs/design/04-solver.md §4). */
class TimeAvailabilityConstraintTest {

    private final ConstraintVerifier<GroupPlanConstraintProvider, GroupPlanSolution> verifier = ConstraintVerifier.build(
            new GroupPlanConstraintProvider(), GroupPlanSolution.class, PlayerAssignment.class, GroupSchedule.class, CoachSlot.class);

    @Test
    void playerUnavailableAtGroupsScheduledTimeSlotPenalizes() {
        Group g = new Group(1L, "Grupp 1", 1, 4, 5, 6, 1, 0, 100_000);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", new TimeKey(TimeKey.NO_DATE, 4, 21 * 60, 22 * 60 + 30), "Torsdag 21.00", 5L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);
        PlayerAssignment p = new PlayerAssignment(1L, 100L, "Kalle", 60_000, 3, null, new long[] {5L}, new long[0], g, false);

        verifier.verifyThat(GroupPlanConstraintProvider::timeAvailabilityHard)
                .given(p, gs)
                .penalizes(1);
    }

    @Test
    void playerWithNoUnavailabilityHasNoImpact() {
        Group g = new Group(1L, "Grupp 1", 1, 4, 5, 6, 1, 0, 100_000);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", new TimeKey(TimeKey.NO_DATE, 4, 21 * 60, 22 * 60 + 30), "Torsdag 21.00", 5L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);
        PlayerAssignment p = new PlayerAssignment(1L, 100L, "Kalle", 60_000, 3, null, new long[0], new long[0], g, false);

        verifier.verifyThat(GroupPlanConstraintProvider::timeAvailabilityHard)
                .given(p, gs)
                .hasNoImpact();
    }

    @Test
    void playerUnavailableAtADifferentTimeSlotThanTheGroupsHasNoImpact() {
        Group g = new Group(1L, "Grupp 1", 1, 4, 5, 6, 1, 0, 100_000);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", new TimeKey(TimeKey.NO_DATE, 4, 18 * 60, 19 * 60 + 30), "Torsdag 18.00", 1L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);
        PlayerAssignment p = new PlayerAssignment(1L, 100L, "Kalle", 60_000, 3, null, new long[] {5L}, new long[0], g, false);

        verifier.verifyThat(GroupPlanConstraintProvider::timeAvailabilityHard)
                .given(p, gs)
                .hasNoImpact();
    }
}
