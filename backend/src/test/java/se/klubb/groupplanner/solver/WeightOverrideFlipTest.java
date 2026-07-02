package se.klubb.groupplanner.solver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.analysis.ConstraintAnalysis;
import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import ai.timefold.solver.core.api.solver.ScoreAnalysisFetchPolicy;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.common.time.TimeKey;
import se.klubb.groupplanner.solver.constraints.ConstraintKeys;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.LateTimePolicy;
import se.klubb.groupplanner.solver.domain.PersonPairWish;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.solver.domain.TrainingBlock;
import se.klubb.groupplanner.solver.domain.WishType;

/**
 * Design M-S2 gate (d): "same dataset, one weight change flips a documented trade-off
 * deterministically". Fixture: two groups (target 3, max 4, min 0) with two players (C,D / E,F)
 * PINNED two-per-group — each group needs exactly ONE more player to sit exactly at target. Two
 * free players (A,B) share a {@code WANT_SAME} wish. Two mutually exclusive outcomes exist for the
 * free pair:
 *
 * <ul>
 *   <li><b>Split</b> (one to each group): both groups land exactly at target (groupSizeTarget cost
 *       0), but the friend wish is broken (sameGroupSoft cost = 1 x weight).
 *   <li><b>Unite</b> (both into the same group): that group overflows to 4 (+1 over target), the
 *       other stays at 2 (-1 under target) — groupSizeTarget cost = 2 x weight(50) = 100 — but the
 *       friend wish is satisfied (sameGroupSoft cost 0).
 * </ul>
 *
 * <p>At the default weights (groupSizeTarget 50, sameGroupSoft 80) splitting is cheaper (80 &lt;
 * 2x50=100), so the solver keeps the pair apart. Cranking {@code sameGroupSoft} above 100 (e.g.
 * 150) flips the trade-off: uniting becomes cheaper, and the solver unites them, deterministically
 * paying the groupSizeTarget cost instead. Every other constraint is deliberately made inert (equal
 * levels, no time/coach data, LateTimePolicy disabled) so this is a clean two-constraint trade-off.
 */
class WeightOverrideFlipTest {

    private static final int STEP_LIMIT = 2000;
    private static final long PARTICIPANT_A = 5L;
    private static final long PARTICIPANT_B = 6L;

    @Test
    void crankingSameGroupSoftWeightFlipsTheFriendUnionVsSizeBalanceTradeOff() {
        // Wall-clock sanity gate (m6a-notes.md "Review fix 1" pathology): with only 2 free entities
        // this fixture reaches its optimum almost immediately, after which it is exactly the
        // "converged fixture" pathology (every remaining move strictly worsens the score) - see
        // solverFactory()'s javadoc for why a moveCountLimit backstop is required here.
        long startMillis = System.currentTimeMillis();

        GroupPlanSolution defaultSolved = solve(ConstraintWeightOverrides.none());
        assertThat(defaultSolved.getScore().hardScore()).isZero();
        assertThat(groupOrderOf(defaultSolved, PARTICIPANT_A))
                .as("under default weights (80 < 2x50) splitting the pair is cheaper")
                .isNotEqualTo(groupOrderOf(defaultSolved, PARTICIPANT_B));

        // Note: groupBy(PlayerAssignment::getGroup, count()) always produces one tuple PER GROUP
        // regardless of whether its deviation is 0 - matchCount() is therefore always 2 here either
        // way. The trade-off flip shows up in the constraint's SCORE contribution (weight x
        // matchWeight, zero when a group sits exactly at target), which is what "applied" means.
        ScoreAnalysis<HardMediumSoftLongScore> defaultAnalysis = analyze(defaultSolved);
        assertThat(scoreOf(defaultAnalysis, ConstraintKeys.SAME_GROUP_SOFT)).isEqualTo(HardMediumSoftLongScore.ofSoft(-80));
        assertThat(scoreOf(defaultAnalysis, ConstraintKeys.GROUP_SIZE_TARGET)).isEqualTo(HardMediumSoftLongScore.ZERO);

        GroupPlanSolution crankedSolved = solve(ConstraintWeightOverrides.of(Map.of(
                ConstraintKeys.SAME_GROUP_SOFT, HardMediumSoftLongScore.ofSoft(150))));
        assertThat(crankedSolved.getScore().hardScore()).isZero();
        assertThat(groupOrderOf(crankedSolved, PARTICIPANT_A))
                .as("cranked sameGroupSoft (150 > 2x50=100) makes uniting cheaper")
                .isEqualTo(groupOrderOf(crankedSolved, PARTICIPANT_B));

        ScoreAnalysis<HardMediumSoftLongScore> crankedAnalysis = analyze(crankedSolved);
        assertThat(scoreOf(crankedAnalysis, ConstraintKeys.SAME_GROUP_SOFT)).isEqualTo(HardMediumSoftLongScore.ZERO);
        assertThat(scoreOf(crankedAnalysis, ConstraintKeys.GROUP_SIZE_TARGET)).isEqualTo(HardMediumSoftLongScore.ofSoft(-100));

        assertThat(System.currentTimeMillis() - startMillis).isLessThan(10_000L);
    }

    private static GroupPlanSolution solve(ConstraintWeightOverrides<HardMediumSoftLongScore> overrides) {
        return solverFactory().buildSolver().solve(buildProblem(overrides));
    }

    /**
     * Deliberately NOT {@link TestSolverFactory} (m6a-notes.md "Review fix 1" pathology, reproduced
     * empirically here via jstack — a hung run's stack was parked in exactly {@code
     * UniformRandomUnionMoveIterator.next()} via {@code LocalSearchDecider.decideNextStep}, the RCA's
     * documented signature): with only 2 free, mutually-interchangeable entities (A, B), once the
     * "unite" optimum is reached every remaining move strictly worsens the score AND the one escape
     * hatch that keeps the DEFAULT scenario cheap (a same-group no-op swap between A and B) is a
     * SPLIT-state phenomenon that vanishes once they're united — so the single first non-improving
     * local-search step alone can already run for tens of seconds and climbing (confirmed: 17.7s CPU
     * and still selecting moves, single-threaded, before being killed). {@code
     * unimprovedStepCountLimit} does not help because Timefold does not preempt mid-step (verified
     * per the RCA: only step-boundary checks skip a whole step; this pathology is EXPRESSED entirely
     * within one step). {@code moveCountLimit} DOES intervene mid-step (it counts individual move
     * evaluations, not steps), so it is added here as a hard backstop, deliberately scoped to this
     * test file only (not added to the shared {@code TestSolverFactory}, which other tests — incl.
     * the {@code large-120} golden regression test — legitimately need to evaluate many more than
     * 200,000 moves over its full stepCountLimit=20000 budget).
     */
    private static SolverFactory<GroupPlanSolution> solverFactory() {
        TerminationConfig termination = new TerminationConfig()
                .withStepCountLimit(STEP_LIMIT)
                .withMoveCountLimit(200_000L);
        SolverConfig config = SolverConfig.createFromXmlResource("solverConfig.xml")
                .withEnvironmentMode(EnvironmentMode.PHASE_ASSERT)
                .withTerminationConfig(termination);
        return SolverFactory.create(config);
    }

    private static ScoreAnalysis<HardMediumSoftLongScore> analyze(GroupPlanSolution solved) {
        return SolutionManager.<GroupPlanSolution, HardMediumSoftLongScore>create(solverFactory())
                .analyze(solved, ScoreAnalysisFetchPolicy.FETCH_ALL);
    }

    private static GroupPlanSolution buildProblem(ConstraintWeightOverrides<HardMediumSoftLongScore> overrides) {
        Group g1 = new Group(1L, "Grupp 1", 1, 0, 3, 4, 0, 50_000, 50_000);
        Group g2 = new Group(2L, "Grupp 2", 2, 0, 3, 4, 0, 50_000, 50_000);
        TrainingBlock b1 = new TrainingBlock(1L, 1L, "Bana 1", timeKey(18 * 60, 19 * 60 + 30), "18.00", 1L);
        TrainingBlock b2 = new TrainingBlock(2L, 2L, "Bana 2", timeKey(19 * 60 + 30, 21 * 60), "19.30", 2L);
        List<GroupSchedule> schedules = List.of(
                new GroupSchedule(1L, g1, b1, true),
                new GroupSchedule(2L, g2, b2, true));

        // All same level: neutralizes levelBalance/groupOrderByLevel/coachLevelFit as factors.
        PlayerAssignment c = pinned(1L, g1);
        PlayerAssignment d = pinned(2L, g1);
        PlayerAssignment e = pinned(3L, g2);
        PlayerAssignment f = pinned(4L, g2);
        PlayerAssignment a = free(PARTICIPANT_A);
        PlayerAssignment b = free(PARTICIPANT_B);

        PersonPairWish wish = new PersonPairWish(1L, WishType.WANT_SAME, PARTICIPANT_A, PARTICIPANT_B);

        return new GroupPlanSolution(
                "test-plan", List.of(c, d, e, f, a, b), schedules, List.of(), List.of(g1, g2), List.of(b1, b2),
                List.of(), List.of(wish), List.of(), List.of(), LateTimePolicy.DISABLED, overrides);
    }

    private static PlayerAssignment pinned(long id, Group group) {
        return new PlayerAssignment(id, id, "P" + id, 50_000, 3, null, new long[0], new long[0], group, true);
    }

    private static PlayerAssignment free(long id) {
        return new PlayerAssignment(id, id, "P" + id, 50_000, 3, null, new long[0], new long[0], null, false);
    }

    private static Integer groupOrderOf(GroupPlanSolution solution, long participantId) {
        return solution.getPlayerAssignments().stream()
                .filter(p -> p.getId() == participantId)
                .findFirst()
                .orElseThrow()
                .getGroup()
                .groupOrder();
    }

    private static HardMediumSoftLongScore scoreOf(ScoreAnalysis<HardMediumSoftLongScore> analysis, String constraintKey) {
        for (ConstraintAnalysis<HardMediumSoftLongScore> ca : analysis.constraintMap().values()) {
            if (constraintKey.equals(ca.constraintRef().constraintName())) {
                return ca.score();
            }
        }
        throw new AssertionError("No constraint analysis entry for key " + constraintKey);
    }

    private static TimeKey timeKey(int startMin, int endMin) {
        return new TimeKey(TimeKey.NO_DATE, 4, startMin, endMin);
    }
}
