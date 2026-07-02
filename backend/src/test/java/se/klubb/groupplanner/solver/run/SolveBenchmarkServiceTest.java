package se.klubb.groupplanner.solver.run;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * v0.2.0 review fast-follow 1: the benchmark's wall-clock cap and its proportional
 * steps-actually-completed speed-factor math. The cap path is forced with a tiny budget through the
 * package-visible {@link SolveBenchmarkService#runBenchmark(long)}; the factor arithmetic is
 * unit-tested directly via {@link SolveBenchmarkService#computeSpeedFactor(long, long, long)} (pure
 * static, no solver run needed).
 */
class SolveBenchmarkServiceTest {

    @Test
    void fullCompletionDegeneratesToPlainReferenceOverElapsed() {
        long stepsPlanned = (long) SolveBenchmarkService.BENCHMARK_CHUNKS * SolveBenchmarkService.CHUNK_STEP_COUNT_LIMIT;

        double factor = SolveBenchmarkService.computeSpeedFactor(stepsPlanned, stepsPlanned, SolveBenchmarkService.REFERENCE_MS);

        assertThat(factor).isEqualTo(1.0); // exactly reference speed
    }

    /** Half the steps in the same elapsed time = half the speed — the proportional extrapolation
     * documented on the class javadoc ({@code (stepsDone/stepsPlanned) × (REFERENCE_MS/elapsedMs)}),
     * with the ratio's direction verified: an incomplete run must REDUCE the factor. */
    @Test
    void halfTheStepsCompletedHalvesTheFactor() {
        long stepsPlanned = 140_000L;

        double full = SolveBenchmarkService.computeSpeedFactor(stepsPlanned, stepsPlanned, 10_000);
        double half = SolveBenchmarkService.computeSpeedFactor(stepsPlanned / 2, stepsPlanned, 10_000);

        assertThat(half).isEqualTo(full / 2.0);
        assertThat(half).isLessThan(full);
    }

    @Test
    void zeroStepsCompletedIsFlooredAtTheMinimumFactorNotZero() {
        double factor = SolveBenchmarkService.computeSpeedFactor(0, 140_000L, 30_000);

        assertThat(factor).isEqualTo(SolveBenchmarkService.MIN_SPEED_FACTOR);
    }

    /**
     * The cap path end-to-end with a real solver run: a 1ms budget cannot fit even one 14,000-step
     * chunk, so the first chunk's {@code spentLimit} fires, zero chunks complete, and the factor
     * lands on the documented {@link SolveBenchmarkService#MIN_SPEED_FACTOR} floor — while the whole
     * call still returns promptly (the entire point of the cap) instead of running 140k steps.
     */
    @Test
    void aTinyWallClockBudgetForcesTheCapPathAndReturnsTheFlooredFactorPromptly() {
        SolveBenchmarkService service = new SolveBenchmarkService();

        long start = System.currentTimeMillis();
        SolveBenchmarkService.BenchmarkResult result = service.runBenchmark(1);
        long wall = System.currentTimeMillis() - start;

        assertThat(result.machineSpeedFactor()).isEqualTo(SolveBenchmarkService.MIN_SPEED_FACTOR);
        assertThat(result.benchmarkMs()).isGreaterThanOrEqualTo(1);
        // Generous CI bound: the budget is 1ms but the single capped chunk still pays solver
        // bootstrap + one termination-check granule; what matters is it is nowhere near the
        // uncapped ~2.2s full benchmark.
        assertThat(wall).isLessThan(2_000);
    }

    /**
     * A budget of one second (a handful of ~220ms chunks on the reference machine, fewer on a slow
     * CI runner, possibly zero on a very slow one) exercises the partial path live with only
     * hardware-independent assertions: the cap is respected within one chunk-tail of slack, and the
     * factor always lands within [floor, uncapped-equivalent] — note a PARTIAL run on a FAST machine
     * still correctly reports near-reference speed (the proportional math extrapolates the rate; it
     * does not conflate "capped" with "slow"), so no upper bound tighter than a sanity ceiling can
     * be asserted portably. The exact proportional arithmetic is pinned by the pure unit tests
     * above instead.
     */
    @Test
    void aOneSecondBudgetRespectsTheCapAndStaysWithinTheDocumentedFactorRange() {
        SolveBenchmarkService service = new SolveBenchmarkService();

        long start = System.currentTimeMillis();
        SolveBenchmarkService.BenchmarkResult result = service.runBenchmark(1_000);
        long wall = System.currentTimeMillis() - start;

        assertThat(wall).isLessThan(5_000); // cap + one capped chunk-tail + CI slack; never the full uncapped run
        assertThat(result.machineSpeedFactor()).isGreaterThanOrEqualTo(SolveBenchmarkService.MIN_SPEED_FACTOR);
    }
}
