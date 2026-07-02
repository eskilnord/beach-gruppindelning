package se.klubb.groupplanner.solver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import java.util.ArrayList;
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
 * Waitlist interaction contract (docs/design/04-solver.md §2.2 table) — full solves, not just
 * ConstraintVerifier match checks, since these assert emergent solver BEHAVIOR (who gets shed under
 * overcapacity, that infeasible-time never becomes a hard violation).
 */
class WaitlistContractTest {

    private static final int STEP_LIMIT = 1000;
    // Unimproved-step convergence cutoffs (see TestSolverFactory javadoc / m6a-notes "Review fix 1"):
    // generous where the fixture has real optimization work left after CH, tight where CH is
    // already optimal and every LS step would be a pathologically expensive rejected-move sawtooth.
    private static final int UNIMPROVED_GENEROUS = 200;
    private static final int UNIMPROVED_TIGHT = 10;

    @Test
    void overCapacityShedsExactlyTheLowestPriorityPlayerWithZeroHardViolations() {
        // 2 groups x max 3 = capacity 6; 7 players -> exactly 1 must be unassigned. Lexicographic
        // scoring (medium = 100 x priority dominates any soft consideration - none configured here
        // anyway) means the solver must shed the LOWEST-priority player to minimize medium cost.
        Group g1 = new Group(1L, "Grupp 1", 1, 1, 2, 3, 0, 0, 100_000);
        Group g2 = new Group(2L, "Grupp 2", 2, 1, 2, 3, 0, 0, 100_000);
        TrainingBlock b1 = new TrainingBlock(1L, 1L, "Bana 1", new TimeKey(TimeKey.NO_DATE, 4, 18 * 60, 19 * 60 + 30), "18.00", 1L);
        TrainingBlock b2 = new TrainingBlock(2L, 2L, "Bana 2", new TimeKey(TimeKey.NO_DATE, 4, 19 * 60 + 30, 21 * 60), "19.30", 2L);

        List<PlayerAssignment> players = new ArrayList<>();
        int[] priorities = {5, 5, 5, 4, 4, 3, 1};
        for (int i = 0; i < priorities.length; i++) {
            long id = i + 1;
            players.add(new PlayerAssignment(id, id, "P" + id, 60_000, priorities[i], null, new long[0], new long[0], null, false));
        }
        List<GroupSchedule> schedules = List.of(
                new GroupSchedule(1L, g1, null, false),
                new GroupSchedule(2L, g2, null, false));

        GroupPlanSolution problem = new GroupPlanSolution(
                "test-plan", players, schedules, List.of(), List.of(g1, g2), List.of(b1, b2), List.of(),
                List.of(), List.of(), List.of(), LateTimePolicy.DISABLED, ConstraintWeightOverrides.none());

        GroupPlanSolution solved = TestSolverFactory.solve(problem, STEP_LIMIT, UNIMPROVED_GENEROUS);

        assertThat(solved.getScore().hardScore()).isZero();
        List<PlayerAssignment> unassigned = solved.getPlayerAssignments().stream().filter(p -> p.getGroup() == null).toList();
        assertThat(unassigned).hasSize(1);
        assertThat(unassigned.get(0).getPriority()).isEqualTo(1);
        assertThat(unassigned.get(0).getId()).isEqualTo(7L);
    }

    @Test
    void playerUnavailableAtEveryOfferedTimeIsWaitlistedNotHardViolated() {
        // A single group whose only training block is at timeSlotId=1; the player's
        // unavailableTimeSlotIds covers that (and only that) slot - every block the group could be
        // scheduled into is off-limits, so the solver must waitlist (medium cost only), never
        // violate timeAvailabilityHard.
        Group g = new Group(1L, "Grupp 1", 1, 1, 2, 3, 0, 0, 100_000);
        TrainingBlock b = new TrainingBlock(1L, 1L, "Bana 1", new TimeKey(TimeKey.NO_DATE, 4, 18 * 60, 19 * 60 + 30), "18.00", 1L);
        PlayerAssignment stuck = new PlayerAssignment(1L, 1L, "Kalle", 60_000, 3, null, new long[] {1L}, new long[0], null, false);
        List<GroupSchedule> schedules = List.of(new GroupSchedule(1L, g, null, false));

        GroupPlanSolution problem = new GroupPlanSolution(
                "test-plan", List.of(stuck), schedules, List.of(), List.of(g), List.of(b), List.of(),
                List.of(), List.of(), List.of(), LateTimePolicy.DISABLED, ConstraintWeightOverrides.none());

        GroupPlanSolution solved = TestSolverFactory.solve(problem, STEP_LIMIT, UNIMPROVED_GENEROUS);

        assertThat(solved.getScore().hardScore()).isZero();
        PlayerAssignment result = solved.getPlayerAssignments().get(0);
        assertThat(result.getGroup()).isNull();
        assertThat(solved.getScore().mediumScore()).isEqualTo(-300); // 100 x priority 3
    }

    @Test
    void pinnedToWaitlistStaysUnassignedAcrossAFullSolve() {
        // §2.2 "Pinned with group == null -> allowed: an explicit lock-to-waitlist".
        Group g = new Group(1L, "Grupp 1", 1, 1, 2, 3, 0, 0, 100_000);
        TrainingBlock b = new TrainingBlock(1L, 1L, "Bana 1", new TimeKey(TimeKey.NO_DATE, 4, 18 * 60, 19 * 60 + 30), "18.00", 1L);
        PlayerAssignment pinnedToWaitlist = new PlayerAssignment(1L, 1L, "Kalle", 60_000, 5, null, new long[0], new long[0], null, true);
        PlayerAssignment normal = new PlayerAssignment(2L, 2L, "Anna", 60_000, 3, null, new long[0], new long[0], null, false);
        List<GroupSchedule> schedules = List.of(new GroupSchedule(1L, g, null, false));

        GroupPlanSolution problem = new GroupPlanSolution(
                "test-plan", List.of(pinnedToWaitlist, normal), schedules, List.of(), List.of(g), List.of(b), List.of(),
                List.of(), List.of(), List.of(), LateTimePolicy.DISABLED, ConstraintWeightOverrides.none());

        // Runtime sanity gate (m6a-notes "Review fix 1"): this exact fixture used to take ~106 s at
        // stepCountLimit=1000 - CH is already optimal here, so every second LS step churned
        // 10^5-10^6 rejected move selections. The unimproved-step cutoff must keep it well under 10 s.
        long startMillis = System.currentTimeMillis();
        GroupPlanSolution solved = TestSolverFactory.solve(problem, STEP_LIMIT, UNIMPROVED_TIGHT);
        long elapsedMillis = System.currentTimeMillis() - startMillis;
        assertThat(elapsedMillis).isLessThan(10_000L);

        assertThat(solved.getScore().hardScore()).isZero();
        PlayerAssignment stillPinned = solved.getPlayerAssignments().stream().filter(p -> p.getId() == 1L).findFirst().orElseThrow();
        assertThat(stillPinned.getGroup()).isNull();
        assertThat(stillPinned.isPinned()).isTrue();
        // The unpinned, lower-priority player is free to take the open spot.
        PlayerAssignment other = solved.getPlayerAssignments().stream().filter(p -> p.getId() == 2L).findFirst().orElseThrow();
        assertThat(other.getGroup()).isNotNull();
    }
}
