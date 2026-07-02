package se.klubb.groupplanner.solver;

import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;

/**
 * Test-only {@code SolverFactory}/{@code Solver} builder: {@code PHASE_ASSERT} + step-count
 * termination + the production {@code solverConfig.xml} wiring (ADR-007 - tests never use
 * wall-clock termination, which cannot reproduce across differing hardware).
 *
 * <p>The {@code unimprovedStepCountLimit} overloads exist because of the converged-fixture
 * pathology documented in backend/docs/m6a-notes.md ("Review fix 1 - RCA"): on a tiny fixture
 * whose Construction Heuristic result is already optimal and whose only doable move strictly
 * worsens the score, Late Acceptance never accepts anything, and every second local-search step
 * burns 10^5-10^6 move selections before the selector infrastructure gives up and takes the best
 * REJECTED move (which the next step instantly reverts). Step-count-only termination then costs
 * ~0.1 s per step (~106 s for stepCountLimit=1000 on a 3-entity problem). An unimproved-step-count
 * limit terminates on convergence instead - still purely step-based, so cross-platform determinism
 * (ADR-007) is unaffected. {@code SolverRegressionTest} deliberately does NOT use it (its golden
 * scores are pinned to plain stepCountLimit=20000 termination).
 */
public final class TestSolverFactory {

    private TestSolverFactory() {
    }

    public static SolverFactory<GroupPlanSolution> create(int stepCountLimit) {
        return create(stepCountLimit, null);
    }

    public static SolverFactory<GroupPlanSolution> create(int stepCountLimit, Integer unimprovedStepCountLimit) {
        TerminationConfig termination = new TerminationConfig().withStepCountLimit(stepCountLimit);
        if (unimprovedStepCountLimit != null) {
            termination = termination.withUnimprovedStepCountLimit(unimprovedStepCountLimit);
        }
        SolverConfig config = SolverConfig.createFromXmlResource("solverConfig.xml")
                .withEnvironmentMode(EnvironmentMode.PHASE_ASSERT)
                .withTerminationConfig(termination);
        return SolverFactory.create(config);
    }

    public static GroupPlanSolution solve(GroupPlanSolution problem, int stepCountLimit) {
        return create(stepCountLimit).buildSolver().solve(problem);
    }

    public static GroupPlanSolution solve(GroupPlanSolution problem, int stepCountLimit, Integer unimprovedStepCountLimit) {
        return create(stepCountLimit, unimprovedStepCountLimit).buildSolver().solve(problem);
    }
}
