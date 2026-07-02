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
import se.klubb.groupplanner.solver.domain.SavedPlanResourceUsage;
import se.klubb.groupplanner.solver.domain.TrainingBlock;
import se.klubb.groupplanner.solver.domain.UsageType;

/**
 * §10.24a savedPlanPersonBlocked, §10.24b savedPlanCoachBlocked, §10.24c savedPlanCourtBlocked.
 * These never fire against real production data in M6a ({@code SolverInputAssembler} always
 * supplies an empty {@code savedPlanResourceUsages} list — see {@link SavedPlanResourceUsage}'s
 * javadoc); exercised here directly against synthetic facts per the M-S1 gate's own instruction
 * ("implement ALL HARD rows... unit-tested now so M6b/M8 only need to wire real facts").
 */
class SavedPlanConstraintsTest {

    private final ConstraintVerifier<GroupPlanConstraintProvider, GroupPlanSolution> verifier = ConstraintVerifier.build(
            new GroupPlanConstraintProvider(), GroupPlanSolution.class, PlayerAssignment.class, GroupSchedule.class, CoachSlot.class);

    @Test
    void playerClashesWithSavedPlanPersonUsagePenalizes() {
        Group g = group(1);
        PlayerAssignment p = new PlayerAssignment(1L, 100L, "Kalle", 60_000, 3, null, new long[0], new long[0], g, false);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 1L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);
        SavedPlanResourceUsage usage = new SavedPlanResourceUsage(
                UsageType.PERSON, 100L, -1L, timeKey(18 * 60, 19 * 60 + 30), "Dam torsdag", "Dam 2");

        verifier.verifyThat(GroupPlanConstraintProvider::savedPlanPersonBlocked)
                .given(p, gs, usage)
                .penalizes(1);
    }

    @Test
    void playerAtNonOverlappingTimeThanSavedPlanUsageHasNoImpact() {
        Group g = group(1);
        PlayerAssignment p = new PlayerAssignment(1L, 100L, "Kalle", 60_000, 3, null, new long[0], new long[0], g, false);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", timeKey(19 * 60 + 30, 21 * 60), "19.30", 2L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);
        SavedPlanResourceUsage usage = new SavedPlanResourceUsage(
                UsageType.PERSON, 100L, -1L, timeKey(18 * 60, 19 * 60 + 30), "Dam torsdag", "Dam 2");

        verifier.verifyThat(GroupPlanConstraintProvider::savedPlanPersonBlocked)
                .given(p, gs, usage)
                .hasNoImpact();
    }

    @Test
    void coachClashesWithSavedPlanPersonUsagePenalizes() {
        CoachFact coach = new CoachFact(1L, 200L, "Anna", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE);
        Group g = group(1);
        CoachSlot cs = new CoachSlot(CoachSlot.syntheticId(1, 0), g, 0, coach, false);
        TrainingBlock block = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 1L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);
        SavedPlanResourceUsage usage = new SavedPlanResourceUsage(
                UsageType.PERSON, 200L, -1L, timeKey(18 * 60, 19 * 60 + 30), "Herr torsdag", "Herr 4");

        verifier.verifyThat(GroupPlanConstraintProvider::savedPlanCoachBlocked)
                .given(cs, gs, usage)
                .penalizes(1);
    }

    @Test
    void courtClashesWithSavedPlanCourtUsagePenalizes() {
        Group g = group(1);
        TrainingBlock block = new TrainingBlock(1L, 7L, "Bana 7", timeKey(18 * 60, 19 * 60 + 30), "18.00", 1L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);
        SavedPlanResourceUsage usage = new SavedPlanResourceUsage(
                UsageType.COURT, -1L, 7L, timeKey(18 * 60, 19 * 60 + 30), "Dam torsdag", "Dam 2");

        verifier.verifyThat(GroupPlanConstraintProvider::savedPlanCourtBlocked)
                .given(gs, usage)
                .penalizes(1);
    }

    @Test
    void courtAtNonOverlappingTimeHasNoImpact() {
        Group g = group(1);
        TrainingBlock block = new TrainingBlock(1L, 7L, "Bana 7", timeKey(19 * 60 + 30, 21 * 60), "19.30", 2L);
        GroupSchedule gs = new GroupSchedule(1L, g, block, false);
        SavedPlanResourceUsage usage = new SavedPlanResourceUsage(
                UsageType.COURT, -1L, 7L, timeKey(18 * 60, 19 * 60 + 30), "Dam torsdag", "Dam 2");

        verifier.verifyThat(GroupPlanConstraintProvider::savedPlanCourtBlocked)
                .given(gs, usage)
                .hasNoImpact();
    }

    private static Group group(int order) {
        return new Group(order, "Grupp " + order, order, 4, 5, 6, 1, 0, 100_000);
    }

    private static TimeKey timeKey(int startMin, int endMin) {
        return new TimeKey(TimeKey.NO_DATE, 4, startMin, endMin);
    }
}
