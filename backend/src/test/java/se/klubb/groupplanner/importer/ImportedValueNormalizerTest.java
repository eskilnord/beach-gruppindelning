package se.klubb.groupplanner.importer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImportedValueNormalizerTest {

    @Test
    void stripsExcelFloatFormattedWholeNumbers() {
        assertThat(ImportedValueNormalizer.externalId("1924.0")).isEqualTo("1924");
        assertThat(ImportedValueNormalizer.externalId(" 11923.0 ")).isEqualTo("11923");
        assertThat(ImportedValueNormalizer.externalId("-3.0")).isEqualTo("-3");
    }

    @Test
    void leavesNonWholeAndTextIdsAlone() {
        assertThat(ImportedValueNormalizer.externalId("12.5")).isEqualTo("12.5");
        assertThat(ImportedValueNormalizer.externalId("p006")).isEqualTo("p006");
        assertThat(ImportedValueNormalizer.externalId("1924")).isEqualTo("1924");
        assertThat(ImportedValueNormalizer.externalId(null)).isNull();
        assertThat(ImportedValueNormalizer.externalId("  ")).isNull();
    }

    @Test
    void previousGroupTakesNewestTermSegment() {
        // Leftmost segment carries the only recognizable term -> it wins (also the positional
        // fallback's pick, so this case does not by itself distinguish the two strategies).
        assertThat(ImportedValueNormalizer.previousGroupName(
                        "Torsdag Herr 1 (Vårtermin 2025) |Torsdag Herr 2"))
                .isEqualTo("Torsdag Herr 1 (Vårtermin 2025)");
        assertThat(ImportedValueNormalizer.previousGroupName("Ensam grupp")).isEqualTo("Ensam grupp");
        assertThat(ImportedValueNormalizer.previousGroupName(null)).isNull();
        assertThat(ImportedValueNormalizer.previousGroupName("  ")).isNull();
    }

    @Test
    void previousGroupNewestTermWinsOverLeftmostSegment() {
        // Newest-term-wins (PreviousGroupNormalizer): the rightmost segment carries the newer term
        // (Vårtermin 2025 > Hösttermin 2024) so it is picked even though it is not leftmost - this
        // is the behavior change from the old "always take the first pipe segment" contract.
        assertThat(ImportedValueNormalizer.previousGroupName(
                        "Torsdag Herr 1 (Hösttermin 2024) |Torsdag Herr 3 (Vårtermin 2025)"))
                .isEqualTo("Torsdag Herr 3 (Vårtermin 2025)");
    }

    @Test
    void previousGroupWithOnlyBlankSegmentsBeforeThePipeStillYieldsTheNonBlankOne() {
        // A leading blank pipe segment (e.g. a stray "|" prefix) is dropped, not treated as "no
        // value" - the remaining non-blank segment is used. This differs from the old
        // first-pipe-segment contract, which returned null here (the segment before '|' was empty).
        assertThat(ImportedValueNormalizer.previousGroupName("|bara efter")).isEqualTo("bara efter");
    }
}
