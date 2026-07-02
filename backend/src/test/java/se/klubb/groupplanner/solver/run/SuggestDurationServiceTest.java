package se.klubb.groupplanner.solver.run;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.solver.run.SuggestDurationService.ProblemSize;

/**
 * v0.2.0 (SUGGESTED OPTIMIZATION TIME): pure unit coverage of {@link
 * SuggestDurationService#computeSuggestedSeconds}'s formula — monotonicity in both axes (problem
 * size, machine speed) plus sane-bounds/friendly-step-rounding behavior. Package-visible access lets
 * this test exercise the formula directly without a DB-backed plan or a real hardware benchmark (see
 * {@code SuggestDurationControllerIntegrationTest} for the full REST-level/end-to-end coverage).
 */
class SuggestDurationServiceTest {

    private static final double REFERENCE_SPEED = 1.0;

    @Test
    void suggestedSecondsIsAlwaysWithinTheClampedFriendlyStepRange() {
        int tiny = SuggestDurationService.computeSuggestedSeconds(new ProblemSize(0, 0, 0, 0, 0, 0), REFERENCE_SPEED);
        int huge = SuggestDurationService.computeSuggestedSeconds(new ProblemSize(500, 50, 50, 50, 500, 50), REFERENCE_SPEED);

        assertThat(tiny).isBetween(15, 600);
        assertThat(huge).isBetween(15, 600);
        assertThat(java.util.List.of(15, 30, 60, 90, 120, 180, 240, 300, 420, 600)).contains(tiny, huge);
    }

    @Test
    void biggerProblemNeverProducesASmallerSuggestionThanASmallerOne() {
        ProblemSize small = new ProblemSize(10, 2, 2, 1, 2, 4);
        ProblemSize bigger = new ProblemSize(130, 12, 12, 8, 45, 7);

        int smallSeconds = SuggestDurationService.computeSuggestedSeconds(small, REFERENCE_SPEED);
        int biggerSeconds = SuggestDurationService.computeSuggestedSeconds(bigger, REFERENCE_SPEED);

        assertThat(biggerSeconds).isGreaterThanOrEqualTo(smallSeconds);
    }

    @Test
    void growingEachDimensionIndependentlyNeverShrinksTheSuggestion() {
        ProblemSize base = new ProblemSize(20, 4, 4, 2, 5, 3);
        int baseSeconds = SuggestDurationService.computeSuggestedSeconds(base, REFERENCE_SPEED);

        assertThat(SuggestDurationService.computeSuggestedSeconds(
                new ProblemSize(200, 4, 4, 2, 5, 3), REFERENCE_SPEED)).isGreaterThanOrEqualTo(baseSeconds);
        assertThat(SuggestDurationService.computeSuggestedSeconds(
                new ProblemSize(20, 40, 4, 2, 5, 3), REFERENCE_SPEED)).isGreaterThanOrEqualTo(baseSeconds);
        assertThat(SuggestDurationService.computeSuggestedSeconds(
                new ProblemSize(20, 4, 40, 2, 5, 3), REFERENCE_SPEED)).isGreaterThanOrEqualTo(baseSeconds);
        assertThat(SuggestDurationService.computeSuggestedSeconds(
                new ProblemSize(20, 4, 4, 20, 5, 3), REFERENCE_SPEED)).isGreaterThanOrEqualTo(baseSeconds);
        assertThat(SuggestDurationService.computeSuggestedSeconds(
                new ProblemSize(20, 4, 4, 2, 50, 3), REFERENCE_SPEED)).isGreaterThanOrEqualTo(baseSeconds);
        assertThat(SuggestDurationService.computeSuggestedSeconds(
                new ProblemSize(20, 4, 4, 2, 5, 30), REFERENCE_SPEED)).isGreaterThanOrEqualTo(baseSeconds);
    }

    @Test
    void aFasterMachineNeverProducesABiggerSuggestionThanASlowerOneForTheSameProblem() {
        ProblemSize size = new ProblemSize(130, 12, 12, 8, 45, 7);

        int slowMachine = SuggestDurationService.computeSuggestedSeconds(size, 0.5); // half reference speed
        int referenceMachine = SuggestDurationService.computeSuggestedSeconds(size, 1.0);
        int fastMachine = SuggestDurationService.computeSuggestedSeconds(size, 3.0); // 3x reference speed

        assertThat(referenceMachine).isLessThanOrEqualTo(slowMachine);
        assertThat(fastMachine).isLessThanOrEqualTo(referenceMachine);
    }

    /** Sanity check against the task's own worked example ("Baserat på 130 spelare, 12 grupper, 45
     * önskemål och din dators hastighet (1.2x referens) föreslås 180 sekunder") — the formula's
     * constants were tuned so this lands on (or very near) that answer. */
    @Test
    void theTasksOwnWorkedExampleLandsOnOneHundredEightySeconds() {
        ProblemSize size = new ProblemSize(130, 12, 12, 8, 45, 7);

        int suggested = SuggestDurationService.computeSuggestedSeconds(size, 1.2);

        assertThat(suggested).isEqualTo(180);
    }
}
