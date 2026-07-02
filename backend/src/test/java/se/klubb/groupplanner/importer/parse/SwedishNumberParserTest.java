package se.klubb.groupplanner.importer.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class SwedishNumberParserTest {

    @Test
    void parsesPlainInteger() {
        assertThat(SwedishNumberParser.parse("940")).isEqualTo(940.0);
    }

    @Test
    void parsesSwedishDecimalComma() {
        assertThat(SwedishNumberParser.parse("12,5")).isCloseTo(12.5, within(1e-9));
    }

    @Test
    void parsesNbspThousandsSeparatorWithDecimalComma() {
        assertThat(SwedishNumberParser.parse("1 234,5")).isCloseTo(1234.5, within(1e-9));
    }

    @Test
    void parsesPlainSpaceThousandsSeparator() {
        assertThat(SwedishNumberParser.parse("1 234")).isCloseTo(1234.0, within(1e-9));
    }

    @Test
    void parsesNarrowNbspThousandsSeparator() {
        assertThat(SwedishNumberParser.parse("1 234,5")).isCloseTo(1234.5, within(1e-9));
    }

    @Test
    void parsesPlainDotDecimal() {
        assertThat(SwedishNumberParser.parse("12.5")).isCloseTo(12.5, within(1e-9));
    }

    @Test
    void returnsNullForBlankOrNull() {
        assertThat(SwedishNumberParser.parse(null)).isNull();
        assertThat(SwedishNumberParser.parse("")).isNull();
        assertThat(SwedishNumberParser.parse("   ")).isNull();
    }

    @Test
    void returnsNullForGenuinelyInvalidText() {
        assertThat(SwedishNumberParser.parse("ett tusen")).isNull();
        assertThat(SwedishNumberParser.parse("ej 21")).isNull();
    }

    @Test
    void rejectsDoubleParseDoubleExoticaAsInvalidNumbers() {
        // Double.parseDouble accepts all of these; a registration sheet never legitimately
        // contains them, so they must flag as "ogiltiga tal" (M3 review finding 6).
        assertThat(SwedishNumberParser.parse("NaN")).isNull();
        assertThat(SwedishNumberParser.parse("Infinity")).isNull();
        assertThat(SwedishNumberParser.parse("-Infinity")).isNull();
        assertThat(SwedishNumberParser.parse("0x1p4")).isNull();
        assertThat(SwedishNumberParser.parse("5f")).isNull();
        assertThat(SwedishNumberParser.parse("5d")).isNull();
        assertThat(SwedishNumberParser.parse("1e99")).isNull();
    }

    @Test
    void signedPlainNumbersStillParse() {
        assertThat(SwedishNumberParser.parse("-3,5")).isEqualTo(-3.5);
        assertThat(SwedishNumberParser.parse("+7")).isEqualTo(7.0);
    }
}
