package se.klubb.groupplanner.solver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.common.time.TimeKey;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.LateTimePolicy;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.solver.domain.SavedPlanResourceUsage;
import se.klubb.groupplanner.solver.domain.TrainingBlock;
import se.klubb.groupplanner.solver.domain.UsageType;

/**
 * THE Anna example, spec §13.2 verbatim (design M-S2 gate c): "Anna tränar Herr 4 kl. 18.00–19.30 /
 * Anna deltar i Dam 2 kl. 19.30–21.00" is allowed; "...kl. 18.00–19.30" for both is not. Anna
 * coaching in a LOCKED Herr-plan materializes a {@code PERSON} {@link SavedPlanResourceUsage} fact
 * (§10.24a {@code savedPlanPersonBlocked}); the Dam-plan solve must never place her as PLAYER at
 * 18.00, and must place her freely at 19.30 (docs/design/04-solver.md §6.2's "coaches emitting
 * PERSON usages" note — the assembly-level wiring for this is covered separately by {@code
 * solver.assemble.SavedPlanUsageAssemblyTest}; this test exercises the CONSTRAINT/solver behavior
 * directly against a hand-built fact, the same style as {@code LockRespectTest}).
 */
class CrossPlanBlockingTest {

    private static final int STEP_LIMIT = 1000;
    private static final long ANNA_PERSON_ID = 999L;

    @Test
    void annaCannotBePlacedAsPlayerAtEighteenButCanAtNineteenThirty() {
        long startMillis = System.currentTimeMillis();

        Group dam1 = new Group(1L, "Dam 1", 1, 0, 1, 2, 0, 50_000, 50_000); // 18.00
        Group dam2 = new Group(2L, "Dam 2", 2, 0, 1, 2, 0, 50_000, 50_000); // 19.30
        TrainingBlock at1800 = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 1L);
        TrainingBlock at1930 = new TrainingBlock(2L, 2L, "Bana 2", timeKey(19 * 60 + 30, 21 * 60), "19.30", 2L);
        List<GroupSchedule> schedules = List.of(
                new GroupSchedule(1L, dam1, at1800, true),
                new GroupSchedule(2L, dam2, at1930, true));

        PlayerAssignment anna = new PlayerAssignment(
                1L, ANNA_PERSON_ID, "Anna", 50_000, 3, null, new long[0], new long[0], null, false);

        // Materialized from the LOCKED Herr-plan's saved_plan_resource_usage row (coach role - see
        // design §6.2: coaches emit PERSON usages too, which is exactly what makes 10.17 hold
        // cross-plan and is what THIS test exercises for the Dam-plan solve).
        SavedPlanResourceUsage annaCoachingHerrAt1800 = new SavedPlanResourceUsage(
                UsageType.PERSON, ANNA_PERSON_ID, -1L, timeKey(18 * 60, 19 * 60 + 30), "Herr torsdag", "COACH");

        GroupPlanSolution problem = new GroupPlanSolution(
                "dam-plan", List.of(anna), schedules, List.of(), List.of(dam1, dam2), List.of(at1800, at1930),
                List.of(), List.of(), List.of(), List.of(annaCoachingHerrAt1800), LateTimePolicy.DISABLED,
                ConstraintWeightOverrides.none());

        GroupPlanSolution solved = solverFactory().buildSolver().solve(problem);
        assertThat(System.currentTimeMillis() - startMillis).isLessThan(10_000L);

        assertThat(solved.getScore().hardScore()).isZero();
        PlayerAssignment result = solved.getPlayerAssignments().get(0);
        assertThat(result.getGroup())
                .as("Anna must land in Dam 2 (19.30) - Dam 1 (18.00) is hard-blocked by her Herr-plan coaching commitment")
                .isEqualTo(dam2);
    }

    /** Companion test proving the constraint is not a false negative: FORCING Anna into the blocked
     * 18.00 group (via an explicit player lock — spec §15.1's "pin wins over solver, user owns it")
     * produces a visible, persistent hard violation instead of the solver silently ignoring it. */
    @Test
    void forcingAnnaIntoTheBlockedEighteenGroupViaAnExplicitLockProducesAVisibleHardViolation() {
        long startMillis = System.currentTimeMillis();

        Group dam1 = new Group(1L, "Dam 1", 1, 0, 1, 2, 0, 50_000, 50_000);
        TrainingBlock at1800 = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 1L);
        GroupSchedule gs1 = new GroupSchedule(1L, dam1, at1800, true);

        PlayerAssignment annaPinnedAt1800 = new PlayerAssignment(
                1L, ANNA_PERSON_ID, "Anna", 50_000, 3, null, new long[0], new long[0], dam1, true);

        SavedPlanResourceUsage annaCoachingHerrAt1800 = new SavedPlanResourceUsage(
                UsageType.PERSON, ANNA_PERSON_ID, -1L, timeKey(18 * 60, 19 * 60 + 30), "Herr torsdag", "COACH");

        GroupPlanSolution problem = new GroupPlanSolution(
                "dam-plan", List.of(annaPinnedAt1800), List.of(gs1), List.of(), List.of(dam1), List.of(at1800),
                List.of(), List.of(), List.of(), List.of(annaCoachingHerrAt1800), LateTimePolicy.DISABLED,
                ConstraintWeightOverrides.none());

        GroupPlanSolution solved = solverFactory().buildSolver().solve(problem);
        assertThat(System.currentTimeMillis() - startMillis).isLessThan(10_000L);

        assertThat(solved.getScore().hardScore()).isEqualTo(-1); // savedPlanPersonBlocked fires exactly once.
        assertThat(solved.getPlayerAssignments().get(0).getGroup())
                .as("the lock is still honored - the solver never overrides a user's explicit pin")
                .isEqualTo(dam1);
    }

    /** See {@code WeightOverrideFlipTest.solverFactory()}'s javadoc: a moveCountLimit backstop
     * (checked mid-step, unlike stepCountLimit/unimprovedStepCountLimit) is required for these
     * hand-built fixtures where every free entity's optimum leaves only worsening moves available. */
    private static SolverFactory<GroupPlanSolution> solverFactory() {
        TerminationConfig termination = new TerminationConfig()
                .withStepCountLimit(STEP_LIMIT)
                .withMoveCountLimit(200_000L);
        SolverConfig config = SolverConfig.createFromXmlResource("solverConfig.xml")
                .withEnvironmentMode(EnvironmentMode.PHASE_ASSERT)
                .withTerminationConfig(termination);
        return SolverFactory.create(config);
    }

    private static TimeKey timeKey(int startMin, int endMin) {
        return new TimeKey(TimeKey.NO_DATE, 4, startMin, endMin);
    }
}
