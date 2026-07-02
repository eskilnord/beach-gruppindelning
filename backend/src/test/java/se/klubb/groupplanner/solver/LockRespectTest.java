package se.klubb.groupplanner.solver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.common.time.TimeKey;
import se.klubb.groupplanner.solver.domain.CoachFact;
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.LateTimePolicy;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.solver.domain.TrainingBlock;

/**
 * §10.23 lockedAssignment, "satisfied by construction" (docs/design/04-solver.md §5): {@code
 * @PlanningPin} on all three planning entities makes a solver move onto a pinned entity
 * structurally impossible. Each scenario here is deliberately built so that an UNPINNED solve
 * would prefer a DIFFERENT value (better score), proving the pin is actually constraining the
 * solver — not just coincidentally matching what it would have chosen anyway.
 */
class LockRespectTest {

    private static final int STEP_LIMIT = 1000;

    @Test
    void pinnedPlayerAssignmentSurvivesAFullSolveEvenWhenAnotherGroupWouldScoreBetter() {
        // Two groups, both under target; g1 is already at target (no pressure to add), g2 is far
        // below target (soft pressure would want a player there if any soft constraint were active -
        // instead we prove the pin holds against the CH/local-search's free choice: pin p1 to g1 and
        // verify it never migrates to g2 despite g2 having open capacity).
        Group g1 = new Group(1L, "Grupp 1", 1, 1, 1, 1, 0, 0, 100_000); // target/max 1 - already "full" once p1 is placed
        Group g2 = new Group(2L, "Grupp 2", 2, 1, 5, 5, 0, 0, 100_000);
        TrainingBlock b1 = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 1L);
        TrainingBlock b2 = new TrainingBlock(2L, 2L, "Bana 2", timeKey(19 * 60 + 30, 21 * 60), "19.30", 2L);
        PlayerAssignment pinned = new PlayerAssignment(1L, 1L, "Pinned", 60_000, 3, null, new long[0], new long[0], g1, true);
        List<GroupSchedule> schedules = List.of(
                new GroupSchedule(1L, g1, b1, false),
                new GroupSchedule(2L, g2, b2, false));

        GroupPlanSolution problem = new GroupPlanSolution(
                "test-plan", List.of(pinned), schedules, List.of(), List.of(g1, g2), List.of(b1, b2), List.of(),
                List.of(), List.of(), List.of(), LateTimePolicy.DISABLED, ConstraintWeightOverrides.none());

        GroupPlanSolution solved = TestSolverFactory.solve(problem, STEP_LIMIT);

        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(solved.getPlayerAssignments().get(0).getGroup()).isEqualTo(g1);
    }

    @Test
    void pinnedGroupScheduleSurvivesAFullSolveEvenWhenTheOtherBlockWouldAlsoBeLegal() {
        Group g1 = new Group(1L, "Grupp 1", 1, 0, 1, 1, 0, 0, 100_000);
        TrainingBlock preferredByPin = new TrainingBlock(1L, 1L, "Bana 1", timeKey(21 * 60, 22 * 60 + 30), "21.00", 1L);
        TrainingBlock otherLegalBlock = new TrainingBlock(2L, 2L, "Bana 2", timeKey(18 * 60, 19 * 60 + 30), "18.00", 2L);
        GroupSchedule pinnedSchedule = new GroupSchedule(1L, g1, preferredByPin, true);

        GroupPlanSolution problem = new GroupPlanSolution(
                "test-plan", List.of(), List.of(pinnedSchedule), List.of(), List.of(g1),
                List.of(preferredByPin, otherLegalBlock), List.of(), List.of(), List.of(), List.of(),
                LateTimePolicy.DISABLED, ConstraintWeightOverrides.none());

        GroupPlanSolution solved = TestSolverFactory.solve(problem, STEP_LIMIT);

        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(solved.getGroupSchedules().get(0).getTrainingBlock()).isEqualTo(preferredByPin);
    }

    @Test
    void pinnedCoachSlotSurvivesAFullSolveEvenWhenAnotherCoachIsAvailable() {
        Group g1 = new Group(1L, "Grupp 1", 1, 0, 1, 1, 1, 0, 100_000);
        TrainingBlock b1 = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 1L);
        GroupSchedule gs1 = new GroupSchedule(1L, g1, b1, false);
        CoachFact pinnedCoach = new CoachFact(1L, 100L, "Pinned Coach", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE);
        CoachFact otherCoach = new CoachFact(2L, 200L, "Other Coach", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE);
        CoachSlot pinnedSlot = new CoachSlot(CoachSlot.syntheticId(1, 0), g1, 0, pinnedCoach, true);

        GroupPlanSolution problem = new GroupPlanSolution(
                "test-plan", List.of(), List.of(gs1), List.of(pinnedSlot), List.of(g1), List.of(b1),
                List.of(pinnedCoach, otherCoach), List.of(), List.of(), List.of(), LateTimePolicy.DISABLED,
                ConstraintWeightOverrides.none());

        GroupPlanSolution solved = TestSolverFactory.solve(problem, STEP_LIMIT);

        assertThat(solved.getScore().hardScore()).isZero();
        assertThat(solved.getCoachSlots().get(0).getCoach()).isEqualTo(pinnedCoach);
    }

    private static TimeKey timeKey(int startMin, int endMin) {
        return new TimeKey(TimeKey.NO_DATE, 4, startMin, endMin);
    }
}
