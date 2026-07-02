package se.klubb.groupplanner.importer.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class JaroWinklerTest {

    @Test
    void identicalStringsScoreOne() {
        assertThat(JaroWinkler.similarity("nils åström", "nils åström")).isEqualTo(1.0);
    }

    @Test
    void completelyDifferentStringsScoreLow() {
        assertThat(JaroWinkler.similarity("abc", "xyz")).isLessThan(0.5);
    }

    @Test
    void emptyStringsScoreZeroUnlessBothEmpty() {
        assertThat(JaroWinkler.similarity("", "abc")).isEqualTo(0.0);
        assertThat(JaroWinkler.similarity(null, "abc")).isEqualTo(0.0);
    }

    @Test
    void similarNamesScoreAboveThreshold() {
        // A one-character typo in a longer name should stay well above the 0.92 matching threshold.
        double score = JaroWinkler.similarity("nils lindberg", "nils lindberrg");
        assertThat(score).isGreaterThanOrEqualTo(0.92);
    }

    @Test
    void commonPrefixBoostsScoreOverPlainJaro() {
        double withPrefix = JaroWinkler.similarity("martha", "marhta");
        assertThat(withPrefix).isCloseTo(0.9611, within(0.01));
    }
}
