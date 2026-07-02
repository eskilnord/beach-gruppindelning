package se.klubb.groupplanner.solver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.common.time.TimeKey;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.LateTimePolicy;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.solver.domain.TrainingBlock;

/**
 * Pinned-overflow contract, AS REWRITTEN by the verifier (docs/design/05-solver-verification.md
 * MAJOR finding 3): the design doc's original §2.2 claim ("player pinned to a group over max -&gt;
 * hard violation stays visible") is false as literally stated — with only SOME members of an
 * over-max group pinned, the solver simply evicts an unpinned member (cheaper, lexicographically,
 * than a hard violation) and restores {@code hardScore == 0}. A hard violation only survives when
 * the overflow is structurally unresolvable: every member of the over-max group is pinned, so
 * {@code @PlanningPin} makes moving any of them impossible.
 */
class PinnedOverflowTest {

    private static final int STEP_LIMIT = 1000;

    @Test
    void resolvableOverflowIsFixedByEvictingAnUnpinnedMember() {
        Group g1 = new Group(1L, "Grupp 1", 1, 1, 2, 2, 0, 0, 100_000); // max 2
        Group g2 = new Group(2L, "Grupp 2", 2, 1, 2, 3, 0, 0, 100_000); // max 3, room to receive the evicted player
        TrainingBlock b1 = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 1L);
        TrainingBlock b2 = new TrainingBlock(2L, 2L, "Bana 2", timeKey(19 * 60 + 30, 21 * 60), "19.30", 2L);

        // 3 players start in g1 (over its max of 2); only p1 is pinned there.
        PlayerAssignment p1Pinned = new PlayerAssignment(1L, 1L, "Pinned", 60_000, 3, null, new long[0], new long[0], g1, true);
        PlayerAssignment p2 = new PlayerAssignment(2L, 2L, "Unpinned-A", 60_000, 3, null, new long[0], new long[0], g1, false);
        PlayerAssignment p3 = new PlayerAssignment(3L, 3L, "Unpinned-B", 60_000, 3, null, new long[0], new long[0], g1, false);
        List<GroupSchedule> schedules = List.of(
                new GroupSchedule(1L, g1, b1, false),
                new GroupSchedule(2L, g2, b2, false));

        GroupPlanSolution problem = new GroupPlanSolution(
                "test-plan", List.of(p1Pinned, p2, p3), schedules, List.of(), List.of(g1, g2), List.of(b1, b2), List.of(),
                List.of(), List.of(), List.of(), LateTimePolicy.DISABLED, ConstraintWeightOverrides.none());

        GroupPlanSolution solved = TestSolverFactory.solve(problem, STEP_LIMIT);

        assertThat(solved.getScore().hardScore()).isZero();
        PlayerAssignment stillPinned = byId(solved, 1L);
        assertThat(stillPinned.getGroup()).isEqualTo(g1); // pin honored: never moved.
        long countInG1 = solved.getPlayerAssignments().stream().filter(p -> p.getGroup() == g1).count();
        assertThat(countInG1).isEqualTo(2); // back at max - one unpinned member moved out.
    }

    @Test
    void unresolvableOverflowWhenEveryMemberIsPinnedLeavesAVisibleHardViolation() {
        Group g1 = new Group(1L, "Grupp 1", 1, 1, 2, 2, 0, 0, 100_000); // max 2
        TrainingBlock b1 = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 1L);

        // All 3 players pinned to the same max-2 group - @PlanningPin makes every one of them
        // structurally immovable, so the overflow cannot be resolved by any move.
        PlayerAssignment p1 = new PlayerAssignment(1L, 1L, "A", 60_000, 3, null, new long[0], new long[0], g1, true);
        PlayerAssignment p2 = new PlayerAssignment(2L, 2L, "B", 60_000, 3, null, new long[0], new long[0], g1, true);
        PlayerAssignment p3 = new PlayerAssignment(3L, 3L, "C", 60_000, 3, null, new long[0], new long[0], g1, true);
        List<GroupSchedule> schedules = List.of(new GroupSchedule(1L, g1, b1, false));

        GroupPlanSolution problem = new GroupPlanSolution(
                "test-plan", List.of(p1, p2, p3), schedules, List.of(), List.of(g1), List.of(b1), List.of(),
                List.of(), List.of(), List.of(), LateTimePolicy.DISABLED, ConstraintWeightOverrides.none());

        GroupPlanSolution solved = TestSolverFactory.solve(problem, STEP_LIMIT);

        assertThat(solved.getScore().hardScore()).isEqualTo(-1); // count(3) - max(2) = 1 match.
        assertThat(byId(solved, 1L).getGroup()).isEqualTo(g1);
        assertThat(byId(solved, 2L).getGroup()).isEqualTo(g1);
        assertThat(byId(solved, 3L).getGroup()).isEqualTo(g1);
    }

    private static PlayerAssignment byId(GroupPlanSolution solution, long id) {
        return solution.getPlayerAssignments().stream().filter(p -> p.getId() == id).findFirst().orElseThrow();
    }

    private static TimeKey timeKey(int startMin, int endMin) {
        return new TimeKey(TimeKey.NO_DATE, 4, startMin, endMin);
    }
}
