package se.klubb.groupplanner.suggest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import se.klubb.groupplanner.suggest.CommentSuggestionParser.RawMatch;

/** Table-driven coverage of every {@link CommentRuleLexicon} cue, plus the parser-level rules
 *  (negation guard, family-exclusive matching, clause splitting) called out in the WP2 spec. */
class CommentSuggestionParserTest {

    @ParameterizedTest
    @MethodSource("everyCue")
    void everyLexiconCueFiresItsKind(String comment, SuggestionKind expectedKind) {
        List<RawMatch> matches = CommentSuggestionParser.parse(comment);
        assertThat(matches).as("comment: " + comment).anyMatch(m -> m.kind() == expectedKind);
    }

    static Stream<Arguments> everyCue() {
        return Stream.of(
                // PLAY_WITH
                Arguments.of("Vill gärna spela tillsammans med Anna Svensson.", SuggestionKind.PLAY_WITH),
                Arguments.of("Vill helst spela med Anna Svensson.", SuggestionKind.PLAY_WITH),
                Arguments.of("Vill spela med Anna Svensson.", SuggestionKind.PLAY_WITH),
                Arguments.of("Vill gärna vara i samma grupp som Anna Svensson.", SuggestionKind.PLAY_WITH),
                Arguments.of("Kommer gärna tillsammans med Anna Svensson.", SuggestionKind.PLAY_WITH),
                Arguments.of("Vill vara i grupp med Anna Svensson.", SuggestionKind.PLAY_WITH),
                Arguments.of("Vill helst vara ihop med Anna Svensson.", SuggestionKind.PLAY_WITH),
                // MUST_PLAY_WITH
                Arguments.of("Måste spela med Anna Svensson.", SuggestionKind.MUST_PLAY_WITH),
                Arguments.of("Måste få spela med Anna Svensson.", SuggestionKind.MUST_PLAY_WITH),
                Arguments.of("Kommer endast om jag får spela med Anna Svensson.", SuggestionKind.MUST_PLAY_WITH),
                // AVOID_PLAY_WITH
                Arguments.of("Vill inte spela med Anna Svensson.", SuggestionKind.AVOID_PLAY_WITH),
                Arguments.of("Helst inte samma grupp som Anna Svensson.", SuggestionKind.AVOID_PLAY_WITH),
                Arguments.of("Ej samma grupp som Anna Svensson.", SuggestionKind.AVOID_PLAY_WITH),
                Arguments.of("Fungerar inte ihop med Anna Svensson.", SuggestionKind.AVOID_PLAY_WITH),
                // COACH_WISH / COACH_AVOID
                Arguments.of("Vill gärna ha Erik som tränare.", SuggestionKind.COACH_WISH),
                Arguments.of("Önskar Erik som tränare.", SuggestionKind.COACH_WISH),
                Arguments.of("Vill inte ha Erik som tränare.", SuggestionKind.COACH_AVOID),
                // TIME_CANNOT / TIME_PREFER
                Arguments.of("Kan inte torsdagar.", SuggestionKind.TIME_CANNOT),
                Arguments.of("Kan ej torsdagar.", SuggestionKind.TIME_CANNOT),
                Arguments.of("Är inte på torsdagar.", SuggestionKind.TIME_CANNOT),
                Arguments.of("Föredrar torsdagar.", SuggestionKind.TIME_PREFER),
                Arguments.of("Vill helst på torsdagar.", SuggestionKind.TIME_PREFER),
                Arguments.of("Passar bäst torsdagar.", SuggestionKind.TIME_PREFER),
                // flags
                Arguments.of("Ny i klubben.", SuggestionKind.NEW_TO_CLUB),
                Arguments.of("Är nybörjare.", SuggestionKind.NEW_TO_CLUB),
                Arguments.of("Känner ingen ännu.", SuggestionKind.NEW_TO_CLUB),
                Arguments.of("Vill gå ner en nivå.", SuggestionKind.LEVEL_CHANGE),
                Arguments.of("Vill gå upp en nivå.", SuggestionKind.LEVEL_CHANGE),
                Arguments.of("Vill ha en lättare grupp.", SuggestionKind.LEVEL_CHANGE),
                Arguments.of("Vill ha en svårare grupp.", SuggestionKind.LEVEL_CHANGE),
                Arguments.of("Blir mer tävlingsinriktad i höst.", SuggestionKind.LEVEL_CHANGE),
                Arguments.of("Har en skada i axeln.", SuggestionKind.INJURY_NOTE),
                Arguments.of("Har ont i knäet.", SuggestionKind.INJURY_NOTE),
                Arguments.of("Går på rehab just nu.", SuggestionKind.INJURY_NOTE),
                Arguments.of("Blev opererad i vintras.", SuggestionKind.INJURY_NOTE));
    }

    @Test
    void negationGuardSuppressesPlainPlayWithWhenAvoidCueItselfDoesNotMatch() {
        // "inte direkt spela tillsammans med" - AVOID's own contiguous cue does not match ("direkt"
        // breaks it), but PLAY_WITH's cue matches "spela tillsammans med" - the negation guard (3
        // tokens back) must suppress it.
        List<RawMatch> matches = CommentSuggestionParser.parse("Vill inte direkt spela tillsammans med Anna Svensson.");
        assertThat(matches).noneMatch(m -> m.kind() == SuggestionKind.PLAY_WITH);
    }

    @Test
    void explicitNegationProducesOnlyAvoidNeverPlayWith() {
        List<RawMatch> matches = CommentSuggestionParser.parse("Vill inte spela med Anna Svensson.");
        assertThat(matches).extracting(RawMatch::kind).containsExactly(SuggestionKind.AVOID_PLAY_WITH);
    }

    @Test
    void mustBeatsPlayWithNeverBothFireForTheSameClause() {
        List<RawMatch> matches = CommentSuggestionParser.parse("Måste spela med Anna Svensson.");
        assertThat(matches).extracting(RawMatch::kind).containsExactly(SuggestionKind.MUST_PLAY_WITH);
    }

    @Test
    void multipleWishesInOneCommentEachProduceTheirOwnMatch() {
        List<RawMatch> matches = CommentSuggestionParser.parse(
                "Vill gärna spela med Anna Svensson. Helst inte samma grupp som Bertil Karlsson. Kan inte torsdagar.");
        assertThat(matches).extracting(RawMatch::kind).containsExactlyInAnyOrder(
                SuggestionKind.PLAY_WITH, SuggestionKind.AVOID_PLAY_WITH, SuggestionKind.TIME_CANNOT);
    }

    @Test
    void aakOAndCaseAreHandledCaseInsensitively() {
        List<RawMatch> matches = CommentSuggestionParser.parse("VILL GÄRNA SPELA MED Åsa Öhman.");
        assertThat(matches).anyMatch(m -> m.kind() == SuggestionKind.PLAY_WITH);
    }

    @Test
    void noCueCommentProducesNoMatches() {
        List<RawMatch> matches = CommentSuggestionParser.parse("Ser fram emot terminen, blir kul!");
        assertThat(matches).isEmpty();
    }

    @Test
    void blankOrNullCommentProducesNoMatches() {
        assertThat(CommentSuggestionParser.parse(null)).isEmpty();
        assertThat(CommentSuggestionParser.parse("")).isEmpty();
        assertThat(CommentSuggestionParser.parse("   ")).isEmpty();
    }

    @Test
    void nameWindowCapturesTextAfterCueForNameExpectingRules() {
        List<RawMatch> matches = CommentSuggestionParser.parse("Vill gärna spela med Anna Svensson.");
        RawMatch match = matches.stream().filter(m -> m.kind() == SuggestionKind.PLAY_WITH).findFirst().orElseThrow();
        assertThat(match.nameWindowText()).contains("Anna Svensson");
    }

    // ─────────────────────────────────────────────────────────── review fix MAJOR 1: coach polarity

    @Test
    void willEjHaSomTranareResolvesToCoachAvoidNeverCoachWish() {
        List<RawMatch> matches = CommentSuggestionParser.parse("Vill ej ha Erik som tränare.");
        assertThat(matches).extracting(RawMatch::kind).containsExactly(SuggestionKind.COACH_AVOID);
    }

    @Test
    void willAldrigHaSomTranareResolvesToCoachAvoidNeverCoachWish() {
        List<RawMatch> matches = CommentSuggestionParser.parse("Vill aldrig ha Erik som tränare.");
        assertThat(matches).extracting(RawMatch::kind).containsExactly(SuggestionKind.COACH_AVOID);
    }

    @Test
    void onskarEjSomTranareResolvesToCoachAvoidNeverCoachWish() {
        List<RawMatch> matches = CommentSuggestionParser.parse("Önskar ej Erik som tränare.");
        assertThat(matches).extracting(RawMatch::kind).containsExactly(SuggestionKind.COACH_AVOID);
    }

    // ─────────────────────────────────────────────── review fix MAJOR 2: clause splitter vs "18.30"

    @Test
    void clauseBoundaryNeverSplitsInsideADecimalStyleTime() {
        List<RawMatch> matches = CommentSuggestionParser.parse("Kan inte torsdagar efter 18.30.");
        RawMatch match = matches.stream().filter(m -> m.kind() == SuggestionKind.TIME_CANNOT).findFirst().orElseThrow();
        assertThat(match.clauseText()).contains("18.30").doesNotContain("18 ").doesNotContain(". 30");
    }

    @Test
    void clauseBoundaryStillSplitsOnATrailingSentencePeriodAfterDigits() {
        List<RawMatch> matches = CommentSuggestionParser.parse("Kan inte torsdagar efter 18.30. Vill gärna spela med Anna Svensson.");
        assertThat(matches).extracting(RawMatch::kind).containsExactlyInAnyOrder(SuggestionKind.TIME_CANNOT, SuggestionKind.PLAY_WITH);
        RawMatch timeMatch = matches.stream().filter(m -> m.kind() == SuggestionKind.TIME_CANNOT).findFirst().orElseThrow();
        assertThat(timeMatch.clauseText()).doesNotContain("Anna Svensson");
    }
}
