package se.klubb.groupplanner.solver.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.api.error.BadRequestException;

/** v0.2.0 (SUGGESTED OPTIMIZATION TIME): {@link SolveProfile#CUSTOM}'s validation bounds and
 * proportional {@code unimprovedSpentLimit} safety-net formula, at the unit level (see {@code
 * SuggestDurationControllerIntegrationTest} for the REST-layer 400 coverage this complements). */
class SolveProfileTest {

    @Test
    void tenAndNineHundredAreTheInclusiveValidBounds() {
        SolveProfile.requireValidCustomDuration(10); // must not throw
        SolveProfile.requireValidCustomDuration(900); // must not throw
    }

    @Test
    void nullDurationIsRejected() {
        assertThatThrownBy(() -> SolveProfile.requireValidCustomDuration(null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void nineIsRejectedBelowMinimum() {
        assertThatThrownBy(() -> SolveProfile.requireValidCustomDuration(9)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void nineHundredOneIsRejectedAboveMaximum() {
        assertThatThrownBy(() -> SolveProfile.requireValidCustomDuration(901)).isInstanceOf(BadRequestException.class);
    }

    /** {@code min(durationSeconds/2, 120)} — below the 240s crossover point, half the duration wins. */
    @Test
    void unimprovedSpentLimitIsHalfDurationBelowTheCrossoverPoint() {
        TerminationConfig config = SolveProfile.customTerminationConfig(60);

        assertThat(config.getSpentLimit()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.getUnimprovedSpentLimit()).isEqualTo(Duration.ofSeconds(30));
    }

    /** Above the 240s crossover point, the flat 120s cap wins (documented on {@code
     * SolveProfile.CUSTOM_UNIMPROVED_CAP_SECONDS}: an unbounded flat fraction would let a 900s solve
     * churn for 450s on a converged plan). */
    @Test
    void unimprovedSpentLimitIsCappedAtOneHundredTwentySecondsAboveTheCrossoverPoint() {
        TerminationConfig config = SolveProfile.customTerminationConfig(900);

        assertThat(config.getSpentLimit()).isEqualTo(Duration.ofSeconds(900));
        assertThat(config.getUnimprovedSpentLimit()).isEqualTo(Duration.ofSeconds(120));
    }

    /** Fixed presets' own {@code terminationConfig()}/{@code limitMs()} work exactly as before (no
     * regression from adding CUSTOM) - only {@code CUSTOM} itself must refuse these fixed-shape
     * methods, since it has no single fixed duration to report. */
    @Test
    void fixedPresetsStillWorkNormallyAfterAddingCustom() {
        assertThat(SolveProfile.FAST.terminationConfig()).isNotNull();
        assertThat(SolveProfile.NORMAL.limitMs()).isEqualTo(Duration.ofSeconds(60).toMillis());
        assertThat(SolveProfile.THOROUGH.terminationConfig()).isNotNull();
    }

    @Test
    void customThrowsIfAskedForTheFixedPresetHelpersDirectly() {
        assertThatThrownBy(SolveProfile.CUSTOM::terminationConfig).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(SolveProfile.CUSTOM::limitMs).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fromStringAcceptsCustom() {
        assertThat(SolveProfile.fromString("custom")).isEqualTo(SolveProfile.CUSTOM);
        assertThat(SolveProfile.fromString("CUSTOM")).isEqualTo(SolveProfile.CUSTOM);
    }
}
