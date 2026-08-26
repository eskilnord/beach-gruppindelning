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
    void previousGroupTakesFirstPipeSegment() {
        assertThat(ImportedValueNormalizer.previousGroupName(
                        "Torsdag Herr 1 (Vårtermin 2025) |Torsdag Herr 2"))
                .isEqualTo("Torsdag Herr 1 (Vårtermin 2025)");
        assertThat(ImportedValueNormalizer.previousGroupName("Ensam grupp")).isEqualTo("Ensam grupp");
        assertThat(ImportedValueNormalizer.previousGroupName("|bara efter")).isNull();
        assertThat(ImportedValueNormalizer.previousGroupName(null)).isNull();
    }
}
