package se.klubb.groupplanner.solver.run;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.solver.assemble.AssembledProblem;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.LateTimePolicy;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;

/**
 * {@link LiveSolutionRegistry} projection + lifecycle (v0.3.0 WI-2) — pure unit test, no Spring
 * context or real Timefold solve needed: constructs a {@link GroupPlanSolution} by hand (same idiom
 * as {@code solver.WaitlistContractTest}) and drives the registry directly.
 */
class LiveSolutionRegistryTest {

    private static final String PLAN_ID = "plan-1";

    private static Group group(long id, String name) {
        return new Group(id, name, (int) id, 0, 10, 12, 0, 0, 100_000);
    }

    private static PlayerAssignment player(long id, String name, int levelScaled, Group group) {
        return new PlayerAssignment(id, id, name, levelScaled, 3, null, new long[0], new long[0], group, false);
    }

    private static AssembledProblem assembledOf(GroupPlanSolution solution) {
        Map<Long, String> participantDbIdByLongId = solution.getPlayerAssignments().stream()
                .collect(java.util.stream.Collectors.toMap(PlayerAssignment::getId, pa -> "participant-db-" + pa.getId()));
        Map<Long, String> groupDbIdByLongId = solution.getGroups().stream()
                .collect(java.util.stream.Collectors.toMap(Group::id, g -> "group-db-" + g.id()));
        return new AssembledProblem(solution, participantDbIdByLongId, Map.of(), groupDbIdByLongId, Map.of(), 0);
    }

    @Test
    void projectsGroupsWaitlistAndNamesFromAConstructedSolution() {
        Group g1 = group(1L, "Grupp 1");
        Group g2 = group(2L, "Grupp 2");
        PlayerAssignment alice = player(1L, "Alice", 55_000, g1);
        PlayerAssignment bob = player(2L, "Bob", 62_500, g2);
        PlayerAssignment onWaitlist = player(3L, "Carla", 40_000, null);
        GroupSchedule schedule1 = new GroupSchedule(1L, g1, null, false);
        GroupSchedule schedule2 = new GroupSchedule(2L, g2, null, false);

        GroupPlanSolution solution = new GroupPlanSolution(
                PLAN_ID, List.of(alice, bob, onWaitlist), List.of(schedule1, schedule2), List.of(), List.of(g1, g2),
                List.of(), List.of(), List.of(), List.of(), List.of(), LateTimePolicy.DISABLED, ConstraintWeightOverrides.none());
        solution.setScore(HardMediumSoftLongScore.of(0, -300, -12));
        AssembledProblem assembled = assembledOf(solution);

        LiveSolutionRegistry registry = new LiveSolutionRegistry();
        registry.onBestSolution(PLAN_ID, "run-1", solution, assembled);

        LiveSolutionRegistry.LiveSnapshot snapshot = registry.get(PLAN_ID).orElseThrow();
        assertThat(snapshot.runId()).isEqualTo("run-1");
        assertThat(snapshot.hard()).isZero();
        assertThat(snapshot.medium()).isEqualTo(-300);
        assertThat(snapshot.soft()).isEqualTo(-12);
        assertThat(snapshot.feasible()).isTrue();

        assertThat(snapshot.groups()).hasSize(2);
        assertThat(snapshot.groups()).extracting(LiveSolutionRegistry.LiveGroup::groupId)
                .containsExactly("group-db-1", "group-db-2"); // sorted by groupOrder, not insertion order.
        LiveSolutionRegistry.LiveGroup projectedG1 = snapshot.groups().get(0);
        assertThat(projectedG1.name()).isEqualTo("Grupp 1");
        assertThat(projectedG1.players()).hasSize(1);
        assertThat(projectedG1.players().get(0).participantProfileId()).isEqualTo("participant-db-1");
        assertThat(projectedG1.players().get(0).displayName()).isEqualTo("Alice");
        assertThat(projectedG1.players().get(0).levelScaled()).isEqualTo(55_000);

        assertThat(snapshot.waitlist()).hasSize(1);
        assertThat(snapshot.waitlist().get(0).displayName()).isEqualTo("Carla");
    }

    @Test
    void startSeedsSequenceZeroAndOnBestSolutionIncrementsSequenceAndImprovementCount() {
        Group g1 = group(1L, "Grupp 1");
        PlayerAssignment alice = player(1L, "Alice", 55_000, null); // unassigned pre-solve seed.
        GroupSchedule schedule1 = new GroupSchedule(1L, g1, null, false);
        GroupPlanSolution preSolve = new GroupPlanSolution(
                PLAN_ID, List.of(alice), List.of(schedule1), List.of(), List.of(g1), List.of(), List.of(), List.of(),
                List.of(), List.of(), LateTimePolicy.DISABLED, ConstraintWeightOverrides.none());
        AssembledProblem assembled = assembledOf(preSolve);

        LiveSolutionRegistry registry = new LiveSolutionRegistry();
        registry.start(PLAN_ID, "run-1", assembled);

        LiveSolutionRegistry.LiveSnapshot seed = registry.get(PLAN_ID).orElseThrow();
        assertThat(seed.sequence()).isZero();
        assertThat(seed.improvementCount()).isZero();
        assertThat(seed.waitlist()).hasSize(1); // no group assigned yet.

        PlayerAssignment placed = player(1L, "Alice", 55_000, g1);
        GroupPlanSolution afterFirstImprovement = new GroupPlanSolution(
                PLAN_ID, List.of(placed), List.of(schedule1), List.of(), List.of(g1), List.of(), List.of(), List.of(),
                List.of(), List.of(), LateTimePolicy.DISABLED, ConstraintWeightOverrides.none());
        afterFirstImprovement.setScore(HardMediumSoftLongScore.of(0, 0, 0));
        registry.onBestSolution(PLAN_ID, "run-1", afterFirstImprovement, assembled);

        LiveSolutionRegistry.LiveSnapshot first = registry.get(PLAN_ID).orElseThrow();
        assertThat(first.sequence()).isEqualTo(1);
        assertThat(first.improvementCount()).isEqualTo(1);
        assertThat(first.waitlist()).isEmpty();
        assertThat(first.groups().get(0).players()).hasSize(1);

        registry.onBestSolution(PLAN_ID, "run-1", afterFirstImprovement, assembled);
        LiveSolutionRegistry.LiveSnapshot second = registry.get(PLAN_ID).orElseThrow();
        assertThat(second.sequence()).isEqualTo(2);
        assertThat(second.improvementCount()).isEqualTo(2);
    }

    @Test
    void aNewSolveStartOverwritesThePreviousPlansSnapshotWithFreshSequenceAndRunId() {
        Group g1 = group(1L, "Grupp 1");
        PlayerAssignment alice = player(1L, "Alice", 55_000, g1);
        GroupSchedule schedule1 = new GroupSchedule(1L, g1, null, false);
        GroupPlanSolution solved = new GroupPlanSolution(
                PLAN_ID, List.of(alice), List.of(schedule1), List.of(), List.of(g1), List.of(), List.of(), List.of(),
                List.of(), List.of(), LateTimePolicy.DISABLED, ConstraintWeightOverrides.none());
        solved.setScore(HardMediumSoftLongScore.of(0, 0, 0));
        AssembledProblem assembled = assembledOf(solved);

        LiveSolutionRegistry registry = new LiveSolutionRegistry();
        registry.start(PLAN_ID, "run-1", assembled);
        registry.onBestSolution(PLAN_ID, "run-1", solved, assembled);
        registry.onBestSolution(PLAN_ID, "run-1", solved, assembled);
        assertThat(registry.get(PLAN_ID).orElseThrow().sequence()).isEqualTo(2);

        // A brand-new solve for the SAME plan: start() overwrites wholesale, not additively - the
        // old run's sequence/improvementCount must NOT leak into the new run (LiveSolutionRegistry's
        // javadoc: "the plan's entry is simply overwritten wholesale").
        registry.start(PLAN_ID, "run-2", assembled);
        LiveSolutionRegistry.LiveSnapshot fresh = registry.get(PLAN_ID).orElseThrow();
        assertThat(fresh.runId()).isEqualTo("run-2");
        assertThat(fresh.sequence()).isZero();
        assertThat(fresh.improvementCount()).isZero();
    }

    @Test
    void getReturnsEmptyForAPlanThatHasNeverHadALiveSnapshot() {
        LiveSolutionRegistry registry = new LiveSolutionRegistry();
        assertThat(registry.get("never-solved-plan")).isEmpty();
    }

    /**
     * Race guard (v0.3.0 WI-2 review finding 1): Timefold 1.33.0's {@code ConsumerSupport}
     * schedules the final-best consumer asynchronously without joining it, and flips
     * {@code SolverStatus} to NOT_SOLVING before {@code close()} joins - so run A's final
     * re-projection can arrive AFTER run B's {@code start()} already seeded sequence 0. The stale
     * event (mismatched runId) must be dropped; the current run's own events still apply.
     */
    @Test
    void aStaleBestSolutionEventFromAnOlderRunNeverClobbersTheNewerRunsFrame() {
        Group g1 = group(1L, "Grupp 1");
        PlayerAssignment alice = player(1L, "Alice", 55_000, g1);
        GroupSchedule schedule1 = new GroupSchedule(1L, g1, null, false);
        GroupPlanSolution oldRunsFinalSolution = new GroupPlanSolution(
                PLAN_ID, List.of(alice), List.of(schedule1), List.of(), List.of(g1), List.of(), List.of(), List.of(),
                List.of(), List.of(), LateTimePolicy.DISABLED, ConstraintWeightOverrides.none());
        AssembledProblem assembled = assembledOf(oldRunsFinalSolution);

        LiveSolutionRegistry registry = new LiveSolutionRegistry();
        registry.start(PLAN_ID, "run-B", assembled); // the NEW run's seed (sequence 0, unscored pre-solve).
        // Score set only AFTER the seed was projected - so soft=-42 can only appear in the kept
        // frame if one of the onBestSolution calls below actually applied.
        oldRunsFinalSolution.setScore(HardMediumSoftLongScore.of(0, 0, -42));

        // Run A's late-arriving final re-projection lands after run B already started: dropped.
        registry.onBestSolution(PLAN_ID, "run-A", oldRunsFinalSolution, assembled);
        LiveSolutionRegistry.LiveSnapshot afterStale = registry.get(PLAN_ID).orElseThrow();
        assertThat(afterStale.runId()).isEqualTo("run-B");
        assertThat(afterStale.sequence()).isZero();
        assertThat(afterStale.improvementCount()).isZero();
        assertThat(afterStale.soft()).isZero(); // still B's scoreless seed, not A's -42soft frame.

        // Run B's OWN event applies normally after the stale one was dropped.
        registry.onBestSolution(PLAN_ID, "run-B", oldRunsFinalSolution, assembled);
        LiveSolutionRegistry.LiveSnapshot afterOwn = registry.get(PLAN_ID).orElseThrow();
        assertThat(afterOwn.runId()).isEqualTo("run-B");
        assertThat(afterOwn.sequence()).isEqualTo(1);
        assertThat(afterOwn.improvementCount()).isEqualTo(1);
        assertThat(afterOwn.soft()).isEqualTo(-42);
    }

    /** Review finding 4 (defensive consistency): a group whose DB-id lookup fails is dropped from
     *  the grid - its players must land on the waitlist rather than silently vanishing. */
    @Test
    void playersOfAGroupWithNoDbIdMappingAreRoutedToTheWaitlistNotDropped() {
        Group mapped = group(1L, "Grupp 1");
        Group unmappable = group(2L, "Grupp 2");
        PlayerAssignment alice = player(1L, "Alice", 55_000, mapped);
        PlayerAssignment bob = player(2L, "Bob", 62_500, unmappable);
        GroupPlanSolution solution = new GroupPlanSolution(
                PLAN_ID, List.of(alice, bob), List.of(new GroupSchedule(1L, mapped, null, false)), List.of(),
                List.of(mapped, unmappable), List.of(), List.of(), List.of(), List.of(), List.of(),
                LateTimePolicy.DISABLED, ConstraintWeightOverrides.none());
        solution.setScore(HardMediumSoftLongScore.of(0, 0, 0));
        // Group 2 deliberately missing from the group dbId map (only participants + group 1 mapped).
        AssembledProblem assembled = new AssembledProblem(
                solution,
                Map.of(1L, "participant-db-1", 2L, "participant-db-2"),
                Map.of(),
                Map.of(1L, "group-db-1"),
                Map.of(),
                0);

        LiveSolutionRegistry registry = new LiveSolutionRegistry();
        registry.onBestSolution(PLAN_ID, "run-1", solution, assembled);

        LiveSolutionRegistry.LiveSnapshot snapshot = registry.get(PLAN_ID).orElseThrow();
        assertThat(snapshot.groups()).hasSize(1); // the unmappable group itself is dropped...
        assertThat(snapshot.groups().get(0).groupId()).isEqualTo("group-db-1");
        // ...but Bob is NOT lost with it - he shows on the waitlist.
        assertThat(snapshot.waitlist()).extracting(LiveSolutionRegistry.LivePlayer::displayName).containsExactly("Bob");
    }
}
