package se.klubb.groupplanner.importer.parse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SwedishTimeParserTest {

    @Test
    void blankIsValid() {
        assertThat(SwedishTimeParser.isValidTimeExpression(null)).isTrue();
        assertThat(SwedishTimeParser.isValidTimeExpression("")).isTrue();
        assertThat(SwedishTimeParser.isValidTimeExpression("   ")).isTrue();
    }

    @Test
    void bareTimeIsValid() {
        assertThat(SwedishTimeParser.isValidTimeExpression("18:00")).isTrue();
        assertThat(SwedishTimeParser.isValidTimeExpression("9.30")).isTrue();
        assertThat(SwedishTimeParser.isValidTimeExpression("18:00:00")).isTrue();
    }

    @Test
    void ejPrefixShorthandIsValid() {
        assertThat(SwedishTimeParser.isValidTimeExpression("ej 21")).isTrue();
        assertThat(SwedishTimeParser.isValidTimeExpression("Ej 18:00")).isTrue();
    }

    @Test
    void commaSeparatedListIsValidWhenEveryTokenIs() {
        assertThat(SwedishTimeParser.isValidTimeExpression("18:00, 19:30")).isTrue();
        assertThat(SwedishTimeParser.isValidTimeExpression("18:00,19:30;21:00")).isTrue();
    }

    @Test
    void outOfRangeHourOrMinuteIsInvalid() {
        assertThat(SwedishTimeParser.isValidTimeExpression("25:00")).isFalse();
        assertThat(SwedishTimeParser.isValidTimeExpression("18:99")).isFalse();
        assertThat(SwedishTimeParser.isValidTimeExpression("ej 25")).isFalse();
    }

    @Test
    void unstructuredTextIsInvalid() {
        assertThat(SwedishTimeParser.isValidTimeExpression("arton")).isFalse();
        assertThat(SwedishTimeParser.isValidTimeExpression("kanske ikväll")).isFalse();
    }

    @Test
    void oneInvalidTokenInAListMakesTheWholeExpressionInvalid() {
        assertThat(SwedishTimeParser.isValidTimeExpression("18:00, arton")).isFalse();
    }

    @Test
    void commaIsAListSeparatorNeverATimeSeparator() {
        // "9,30" is inherently ambiguous (the time 9:30 vs the list "9 and 30"); the grammar
        // reads it as a list, whose second token (30) is not a valid hour, so the whole value is
        // flagged for manual review instead of being silently guessed at (M3 review finding 8).
        assertThat(SwedishTimeParser.isValidTimeExpression("9,30")).isFalse();
        // The unambiguous bare-time spellings still parse.
        assertThat(SwedishTimeParser.isValidTimeExpression("9.30")).isTrue();
        assertThat(SwedishTimeParser.isValidTimeExpression("9:30")).isTrue();
        // And a comma between two genuinely valid tokens is still a list.
        assertThat(SwedishTimeParser.isValidTimeExpression("9,21")).isTrue(); // hours 9 and 21
    }
}
