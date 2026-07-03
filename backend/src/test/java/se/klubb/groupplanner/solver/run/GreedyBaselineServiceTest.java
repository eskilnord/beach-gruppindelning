package se.klubb.groupplanner.solver.run;

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
 * Deterministic tie-break coverage for the §9.5 greedy baseline (docs/design/05-solver-verification
 * .md minor finding): blocks sorted {@code (day/epochDay, startMinute, courtId, blockId)} — NOT the
 * design's original {@code (startMinute, courtId)}, which silently omitted day and would misorder a
 * multi-day fixture; coach ties broken by {@code (distance, coachProfileId)}.
 */
class GreedyBaselineServiceTest {

    private final GreedyBaselineService service = new GreedyBaselineService();

    @Test
    void playersAreSlicedByLevelDescendingIntoGroupsByAscendingGroupOrder() {
        Group g1 = group(1);
        Group g2 = group(2);
        TrainingBlock b1 = block(1L, 1, TimeKey.NO_DATE, 4, 18 * 60);
        TrainingBlock b2 = block(2L, 2, TimeKey.NO_DATE, 4, 19 * 60 + 30);
        List<GroupSchedule> schedules = List.of(new GroupSchedule(1L, g1, null, false), new GroupSchedule(2L, g2, null, false));
        List<PlayerAssignment> players = List.of(
                player(1L, 70_000), player(2L, 90_000), player(3L, 100_000), player(4L, 80_000));

        GroupPlanSolution problem = new GroupPlanSolution(
                "test-plan", players, schedules, List.of(), List.of(g1, g2), List.of(b1, b2), List.of(),
                List.of(), List.of(), List.of(), LateTimePolicy.DISABLED, ConstraintWeightOverrides.none());

        GroupPlanSolution result = service.run(problem);

        // Top 2 levels (100k id3, 90k id2) -> group order 1 (lowest groupOrder = "best" group).
        assertThat(groupOrderOf(result, 3L)).isEqualTo(1);
        assertThat(groupOrderOf(result, 2L)).isEqualTo(1);
        assertThat(groupOrderOf(result, 1L)).isEqualTo(2);
        assertThat(groupOrderOf(result, 4L)).isEqualTo(2);
    }

    @Test
    void blocksAreSortedByDayFirstNotJustStartMinute() {
        Group g1 = group(1);
        Group g2 = group(2);
        // blockLaterDayEarlyStart: epochDay 10, start 100 (early in the day, but a LATER day).
        // blockEarlierDayLateStart: epochDay 5, start 900 (later in the day, but an EARLIER day).
        TrainingBlock blockLaterDayEarlyStart = block(1L, 1, 10, 4, 100);
        TrainingBlock blockEarlierDayLateStart = block(2L, 2, 5, 1, 900);
        List<GroupSchedule> schedules = List.of(new GroupSchedule(1L, g1, null, false), new GroupSchedule(2L, g2, null, false));
        List<PlayerAssignment> players = List.of(player(1L, 50_000));

        GroupPlanSolution problem = new GroupPlanSolution(
                "test-plan", players, schedules, List.of(), List.of(g1, g2),
                List.of(blockLaterDayEarlyStart, blockEarlierDayLateStart), List.of(), List.of(), List.of(), List.of(),
                LateTimePolicy.DISABLED, ConstraintWeightOverrides.none());

        GroupPlanSolution result = service.run(problem);

        // Day ordering wins: the EARLIER-DAY block (day 5) goes to groupOrder 1, despite its later
        // start-of-day minute; the LATER-DAY block (day 10) goes to groupOrder 2 despite starting
        // earlier in its own day.
        GroupSchedule schedule1 = scheduleOf(result, 1);
        GroupSchedule schedule2 = scheduleOf(result, 2);
        assertThat(schedule1.getTrainingBlock()).isEqualTo(blockEarlierDayLateStart);
        assertThat(schedule2.getTrainingBlock()).isEqualTo(blockLaterDayEarlyStart);
    }

    @Test
    void equallyFitCoachesAreTieBrokenByLowerCoachProfileId() {
        Group g1 = group(1);
        TrainingBlock b1 = block(1L, 1, TimeKey.NO_DATE, 4, 18 * 60);
        List<GroupSchedule> schedules = List.of(new GroupSchedule(1L, g1, b1, false));
        CoachSlot slot = new CoachSlot(CoachSlot.syntheticId(1, 0), g1, 0, null, false);
        List<PlayerAssignment> players = List.of(player(1L, 50_000));

        // Both coaches fit the group's mean level (50_000) perfectly (distance 0) and have no
        // availability/maxGroups constraint - a genuine tie, broken only by coachProfileId.
        CoachFact higherIdCoach = new CoachFact(20L, 200L, "B", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE, new long[0], new long[0]);
        CoachFact lowerIdCoach = new CoachFact(10L, 100L, "A", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE, new long[0], new long[0]);

        GroupPlanSolution problem = new GroupPlanSolution(
                "test-plan", players, schedules, List.of(slot), List.of(g1), List.of(b1),
                List.of(higherIdCoach, lowerIdCoach), List.of(), List.of(), List.of(), LateTimePolicy.DISABLED,
                ConstraintWeightOverrides.none());

        GroupPlanSolution result = service.run(problem);

        assertThat(result.getCoachSlots().get(0).getCoach().coachProfileId()).isEqualTo(10L);
    }

    @Test
    void aCoachOutsideTheLevelBandLosesToAWorseIdButBetterFitCoach() {
        Group g1 = group(1); // levelMinScaled/MaxScaled 0/100_000, but ACTUAL mean will be 50_000.
        TrainingBlock b1 = block(1L, 1, TimeKey.NO_DATE, 4, 18 * 60);
        List<GroupSchedule> schedules = List.of(new GroupSchedule(1L, g1, b1, false));
        CoachSlot slot = new CoachSlot(CoachSlot.syntheticId(1, 0), g1, 0, null, false);
        List<PlayerAssignment> players = List.of(player(1L, 50_000));

        // lowerIdButFar: id 5 (would win any tie-break) but its band (80_000-100_000) is far from
        // the group's actual mean (50_000) -> nonzero distance. higherIdButFits: id 50, band
        // includes 50_000 -> distance 0. Fit distance must dominate id, proving id is ONLY a
        // tie-breaker, not a primary ranking criterion.
        CoachFact lowerIdButFar = new CoachFact(5L, 500L, "Far", 90_000, 80_000, 100_000, new long[0], Integer.MAX_VALUE, new long[0], new long[0]);
        CoachFact higherIdButFits = new CoachFact(50L, 5000L, "Fits", 50_000, 0, 100_000, new long[0], Integer.MAX_VALUE, new long[0], new long[0]);

        GroupPlanSolution problem = new GroupPlanSolution(
                "test-plan", players, schedules, List.of(slot), List.of(g1), List.of(b1),
                List.of(lowerIdButFar, higherIdButFits), List.of(), List.of(), List.of(), LateTimePolicy.DISABLED,
                ConstraintWeightOverrides.none());

        GroupPlanSolution result = service.run(problem);

        assertThat(result.getCoachSlots().get(0).getCoach().coachProfileId()).isEqualTo(50L);
    }

    private static Group group(int order) {
        return new Group(order, "Grupp " + order, order, 0, 2, 4, 1, 40_000, 60_000);
    }

    private static TrainingBlock block(long id, long courtId, int epochDay, int dayOfWeek, int startMin) {
        return new TrainingBlock(id, courtId, "Bana " + courtId, new TimeKey(epochDay, dayOfWeek, startMin, startMin + 90), "block" + id, id);
    }

    private static PlayerAssignment player(long id, int levelScaled) {
        return new PlayerAssignment(id, id, "P" + id, levelScaled, 3, null, new long[0], new long[0], null, false);
    }

    private static Integer groupOrderOf(GroupPlanSolution solution, long participantId) {
        return solution.getPlayerAssignments().stream()
                .filter(p -> p.getId() == participantId)
                .findFirst()
                .orElseThrow()
                .getGroup()
                .groupOrder();
    }

    private static GroupSchedule scheduleOf(GroupPlanSolution solution, int groupOrder) {
        return solution.getGroupSchedules().stream()
                .filter(gs -> gs.getGroup().groupOrder() == groupOrder)
                .findFirst()
                .orElseThrow();
    }
}
