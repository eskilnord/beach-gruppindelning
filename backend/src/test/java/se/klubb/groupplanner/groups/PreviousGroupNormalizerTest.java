package se.klubb.groupplanner.groups;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Table-driven coverage of {@link PreviousGroupNormalizer#parse(String)}, per the B1 spec table. */
class PreviousGroupNormalizerTest {

    @ParameterizedTest
    @MethodSource("cases")
    void parsesAccordingToSpecTable(
            String raw, String expectedRawDisplay, String expectedCanonicalName, String expectedCategoryPart,
            Integer expectedGroupOrder, String expectedTermLabel, Integer expectedTermKey) {
        PreviousGroupRef ref = PreviousGroupNormalizer.parse(raw);
        assertThat(ref).as("raw: " + raw).isNotNull();
        assertThat(ref.rawDisplay()).as("rawDisplay for: " + raw).isEqualTo(expectedRawDisplay);
        assertThat(ref.canonicalName()).as("canonicalName for: " + raw).isEqualTo(expectedCanonicalName);
        assertThat(ref.categoryPart()).as("categoryPart for: " + raw).isEqualTo(expectedCategoryPart);
        assertThat(ref.groupOrder()).as("groupOrder for: " + raw).isEqualTo(expectedGroupOrder);
        assertThat(ref.termLabel()).as("termLabel for: " + raw).isEqualTo(expectedTermLabel);
        assertThat(ref.termKey()).as("termKey for: " + raw).isEqualTo(expectedTermKey);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                // newest-term-wins selection between pipe-separated segments
                Arguments.of(
                        "Torsdag Herr 1 (Vårtermin 2025) |Torsdag Herr 2",
                        "Torsdag Herr 1 (Vårtermin 2025)", "torsdag herr 1", "torsdag herr", 1,
                        "Vårtermin 2025", 4050),
                Arguments.of(
                        "Torsdag Herr 2 (Hösttermin 2024) |Torsdag Herr 1 (Vårtermin 2025)",
                        "Torsdag Herr 1 (Vårtermin 2025)", "torsdag herr 1", "torsdag herr", 1,
                        "Vårtermin 2025", 4050),
                Arguments.of(
                        "Torsdag Herr 1 (Hösttermin 2025)",
                        "Torsdag Herr 1 (Hösttermin 2025)", "torsdag herr 1", "torsdag herr", 1,
                        "Hösttermin 2025", 4051),
                // no term suffix, plain trailing ordinal
                Arguments.of(
                        "Torsdag Herr 12", "Torsdag Herr 12", "torsdag herr 12", "torsdag herr", 12, null, null),
                // abbreviated term forms
                Arguments.of(
                        "Torsdag Dam 2 (VT25)", "Torsdag Dam 2 (VT25)", "torsdag dam 2", "torsdag dam", 2,
                        "VT25", 4050),
                Arguments.of(
                        "Herr 3 - HT 2025", "Herr 3 - HT 2025", "herr 3", "herr", 3, "HT 2025", 4051),
                // groupOrder rule (a) (trailing standalone integer) - NOT rule (b): "3" is a trailing
                // standalone digit, so categoryPart "grupp" proves rule (a) resolved this, not the
                // grupp/nivå/lag keyword rule.
                Arguments.of("Grupp 3", "Grupp 3", "grupp 3", "grupp", 3, null, null),
                // bare numeric segment, padded whitespace trimmed; stripping "4" would leave an empty
                // remainder, so categoryPart is null even though the order came via rule (a).
                Arguments.of("  4  ", "4", "4", null, 4, null, null),
                // trailing empty segment from a dangling pipe is dropped
                Arguments.of(
                        "Torsdag Herr 1|", "Torsdag Herr 1", "torsdag herr 1", "torsdag herr", 1, null, null),
                // no derivable groupOrder at all
                Arguments.of("Nybörjargrupp", "Nybörjargrupp", "nybörjargrupp", null, null, null, null),
                // non-term parenthetical is kept, not mistaken for a term suffix; and no digit remains
                // once the parenthetical is (only for extraction purposes) set aside, so order is null
                Arguments.of(
                        "Torsdag Herr (nybörjare)", "Torsdag Herr (nybörjare)", "torsdag herr (nybörjare)",
                        null, null, null, null),
                // year guard: trailing "2024" must not be read as groupOrder 24
                Arguments.of("Herr 2024", "Herr 2024", "herr 2024", null, null, null, null),
                // range guard: 99 > 60, and no grupp/nivå/lag keyword or leading digit to fall back to
                Arguments.of(
                        "Torsdag Herr 99", "Torsdag Herr 99", "torsdag herr 99", null, null, null, null),
                // --- FIX 1: undelimited (whitespace-only-separated) trailing term is now stripped ---
                Arguments.of(
                        "Torsdag Herr 1 VT25", "Torsdag Herr 1 VT25", "torsdag herr 1", "torsdag herr", 1,
                        "VT25", 4050),
                Arguments.of(
                        "Herr 1 HT25", "Herr 1 HT25", "herr 1", "herr", 1, "HT25", 4051),
                // --- FIX 2: (?iu) makes rule (b) match Swedish-cased keywords like "NIVÅ" ---
                Arguments.of(
                        "NIVÅ 4 Torsdag", "NIVÅ 4 Torsdag", "nivå 4 torsdag", null, 4, null, null),
                // --- FIX 3(a): word boundary + no-trailing-digit guard on term keyword/year ---
                // "ht" inside "Night" must not be read as a term at all.
                Arguments.of(
                        "Beach Night 2025", "Beach Night 2025", "beach night 2025", null, null, null, null),
                // --- FIX 3(b)+(c): segment selection uses the MAX termKey among all term occurrences
                // in a segment, and picking a segment whose winning term is trailing still strips only
                // that trailing term (the earlier "VT24" is left as part of the category text).
                Arguments.of(
                        "VT24 äldre, Herr 1 (HT25)|Herr 2 (VT25)", "VT24 äldre, Herr 1 (HT25)",
                        "vt24 äldre herr 1", "vt24 äldre herr", 1, "HT25", 4051),
                // --- FIX 3(b): segment selection between pipe segments also uses MAX per-segment key ---
                Arguments.of(
                        "Torsdag Herr 1 VT25|Torsdag Herr 2 (HT24)", "Torsdag Herr 1 VT25",
                        "torsdag herr 1", "torsdag herr", 1, "VT25", 4050),
                // typo'd year ("20255") must not be recognized as a term at all, so the other segment's
                // valid HT24 term wins selection outright
                Arguments.of(
                        "Torsdag Herr 1 (vt 20255)|Torsdag Herr 2 (HT24)", "Torsdag Herr 2 (HT24)",
                        "torsdag herr 2", "torsdag herr", 2, "HT24", 4049),
                // --- FIX 3(c): leading/mid-string term is stripped from groupOrder/canonicalName input
                // (never poisoning them), while termLabel/termKey are still populated from it ---
                Arguments.of(
                        "Vårtermin 2025 Torsdag Herr 2", "Vårtermin 2025 Torsdag Herr 2", "torsdag herr 2",
                        "torsdag herr", 2, "Vårtermin 2025", 4050),
                // --- FIX 4: Unicode whitespace (nbsp) is normalized to a regular space up front, so a
                // trailing nbsp doesn't block ordinal extraction or leak into canonicalName ---
                Arguments.of(
                        "Torsdag Herr 1 ", "Torsdag Herr 1", "torsdag herr 1", "torsdag herr", 1, null,
                        null),
                // --- FIX 5: rule (a) matching-but-invalid (99 > 60) falls through to rule (b), rather
                // than short-circuiting to "no groupOrder" ---
                Arguments.of(
                        "Grupp 3 Herr 99", "Grupp 3 Herr 99", "grupp 3 herr 99", null, 3, null, null),
                // --- FIX 6: a single trailing letter after the ordinal is tolerated ---
                Arguments.of("Herr 1B", "Herr 1B", "herr 1b", "herr", 1, null, null),
                // --- FIX 8: a trailing NON-term parenthetical still allows rule (a) extraction (tried
                // against the text with the parenthetical set aside) - canonicalName keeps it though ---
                Arguments.of(
                        "Torsdag Herr 1 (nybörjare)", "Torsdag Herr 1 (nybörjare)",
                        "torsdag herr 1 (nybörjare)", null, 1, null, null),
                // --- FIX 9 coverage additions ---
                // rule (c): leading integer, with a non-keyword word after it
                Arguments.of("12 Herr", "12 Herr", "12 herr", null, 12, null, null),
                // a lone abbreviated term with nothing else: stripping it would leave nothing, so the
                // documented legacy positional heuristic applies (year digits double as the order) -
                // termLabel/termKey are still populated from the term match itself
                Arguments.of("vt 25", "vt 25", "vt 25", "vt", 25, "vt 25", 4050),
                // same lone-term case, no space, 4-digit year: the year is too long for rule (a)'s 1-2
                // digit trailing-integer window (and its digits aren't isolable as a 1-2 digit token),
                // so there is no fallback order here
                Arguments.of("HT2024", "HT2024", "ht2024", null, null, "HT2024", 4049),
                Arguments.of(
                        "hosttermin 2025", "hosttermin 2025", "hosttermin 2025", null, null,
                        "hosttermin 2025", 4051),
                // double space between keyword and year is tolerated by the term regex, and collapsed
                // in canonicalName (but preserved verbatim in termLabel, like other term text)
                Arguments.of(
                        "Hösttermin  2025", "Hösttermin  2025", "hösttermin 2025", null, null,
                        "Hösttermin  2025", 4051),
                // leading-pipe input: the empty leading segment is dropped by the split/trim step
                Arguments.of("|Herr 2", "Herr 2", "herr 2", "herr", 2, null, null),
                Arguments.of("---", "---", "", null, null, null, null));
    }

    @Test
    void blankNullAndPipesOnlyAllParseToNull() {
        assertThat(PreviousGroupNormalizer.parse(null)).isNull();
        assertThat(PreviousGroupNormalizer.parse("")).isNull();
        assertThat(PreviousGroupNormalizer.parse("   ")).isNull();
        assertThat(PreviousGroupNormalizer.parse("|")).isNull();
        assertThat(PreviousGroupNormalizer.parse(" | | ")).isNull();
    }

    @Test
    void tieOnIdenticalTermKeysPicksLeftmostSegment() {
        PreviousGroupRef ref = PreviousGroupNormalizer.parse(
                "Torsdag Herr 1 (Vårtermin 2025) |Torsdag Herr 2 (Vårtermin 2025)");
        assertThat(ref.rawDisplay()).isEqualTo("Torsdag Herr 1 (Vårtermin 2025)");
        assertThat(ref.canonicalName()).isEqualTo("torsdag herr 1");
    }

    @Test
    void whitespaceOnlySegmentsAreDroppedFromTheSplit() {
        PreviousGroupRef ref = PreviousGroupNormalizer.parse("Torsdag Herr 1 |   |Torsdag Herr 2");
        // No segment carries a term, so the positional fallback (leftmost) applies once blanks are
        // removed - the whitespace-only middle segment must not become the "leftmost" pick.
        assertThat(ref.rawDisplay()).isEqualTo("Torsdag Herr 1");
    }

    @Test
    void dashVariantBetweenTermKeywordAndYearIsRecognized() {
        PreviousGroupRef ref = PreviousGroupNormalizer.parse("Torsdag Herr 1 (vårtermin-25)");
        assertThat(ref.termLabel()).isEqualTo("vårtermin-25");
        assertThat(ref.termKey()).isEqualTo(2025 * 2);
        assertThat(ref.canonicalName()).isEqualTo("torsdag herr 1");
    }

    @Test
    void termKeyArithmeticMatchesYearTimesTwoPlusAutumnFlag() {
        assertThat(PreviousGroupNormalizer.parse("Torsdag Herr 1 (Vårtermin 2025)").termKey()).isEqualTo(4050);
        assertThat(PreviousGroupNormalizer.parse("Torsdag Herr 1 (Hösttermin 2024)").termKey()).isEqualTo(4049);
    }

    @Test
    void categoryPartIsSetOnlyWhenGroupOrderCameFromTheTrailingIntegerRule() {
        PreviousGroupRef viaTrailingInt = PreviousGroupNormalizer.parse("Torsdag Herr 3");
        assertThat(viaTrailingInt.groupOrder()).isEqualTo(3);
        assertThat(viaTrailingInt.categoryPart()).isEqualTo("torsdag herr");

        PreviousGroupRef viaKeywordRule = PreviousGroupNormalizer.parse("Nivå 4 Torsdag Herr");
        assertThat(viaKeywordRule.groupOrder()).isEqualTo(4);
        assertThat(viaKeywordRule.categoryPart()).isNull();
    }

    @Test
    void publicCanonicalizeNameMatchesInternalCanonicalization() {
        assertThat(PreviousGroupNormalizer.canonicalizeName("Torsdag  Herr, 3"))
                .isEqualTo(PreviousGroupNormalizer.parse("Torsdag Herr 3").canonicalName());
    }
}
