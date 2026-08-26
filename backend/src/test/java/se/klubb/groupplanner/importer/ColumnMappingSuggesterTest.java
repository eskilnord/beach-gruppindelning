package se.klubb.groupplanner.importer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Synonym-table + fuzzy-match suggestion tests (spec §8.4). Deliberately exercises Swedish AND
 * English header spellings plus minor variations, never the real file's exact column set
 * (CLAUDE.md: "never hardcode column names from the real file").
 */
class ColumnMappingSuggesterTest {

    @Test
    void swedishSynonymsMapToTheRightTarget() {
        assertThat(ColumnMappingSuggester.suggest("Förnamn")).contains(MappingTargetKind.FIRST_NAME);
        assertThat(ColumnMappingSuggester.suggest("Efternamn")).contains(MappingTargetKind.LAST_NAME);
        assertThat(ColumnMappingSuggester.suggest("E-post")).contains(MappingTargetKind.EMAIL);
        assertThat(ColumnMappingSuggester.suggest("Mobil")).contains(MappingTargetKind.PHONE);
        assertThat(ColumnMappingSuggester.suggest("Rank")).contains(MappingTargetKind.RANKING_POINTS);
        assertThat(ColumnMappingSuggester.suggest("Kommentar")).contains(MappingTargetKind.COMMENT);
        assertThat(ColumnMappingSuggester.suggest("Tidigare grupp")).contains(MappingTargetKind.PREVIOUS_GROUP_NAME);
        assertThat(ColumnMappingSuggester.suggest("Tränare")).contains(MappingTargetKind.COACH_NAME);
    }

    @Test
    void englishSynonymsMapToTheRightTarget() {
        assertThat(ColumnMappingSuggester.suggest("First Name")).contains(MappingTargetKind.FIRST_NAME);
        assertThat(ColumnMappingSuggester.suggest("Last Name")).contains(MappingTargetKind.LAST_NAME);
        assertThat(ColumnMappingSuggester.suggest("Email")).contains(MappingTargetKind.EMAIL);
        assertThat(ColumnMappingSuggester.suggest("Phone")).contains(MappingTargetKind.PHONE);
    }

    @Test
    void caseAndPunctuationInsensitive() {
        assertThat(ColumnMappingSuggester.suggest("  FÖRNAMN  ")).contains(MappingTargetKind.FIRST_NAME);
        assertThat(ColumnMappingSuggester.suggest("e_post")).contains(MappingTargetKind.EMAIL);
    }

    @Test
    void minorMisspellingsStillFuzzyMatch() {
        // One transposed/extra letter vs. the synonym table, close enough for Jaro-Winkler.
        assertThat(ColumnMappingSuggester.suggest("Förnman")).contains(MappingTargetKind.FIRST_NAME);
        assertThat(ColumnMappingSuggester.suggest("Emial")).contains(MappingTargetKind.EMAIL);
    }

    @Test
    void unrelatedHeaderTextYieldsNoSuggestion() {
        // "Skonummer" (shoe size) doesn't resemble any synonym closely enough to fuzzy-match -
        // it should stay unmapped ("ignore" by default in the wizard).
        assertThat(ColumnMappingSuggester.suggest("Skonummer")).isEmpty();
        assertThat(ColumnMappingSuggester.suggest("xyz123")).isEmpty();
    }

    @Test
    void explicitIgnoreColumnsCarrySwedishReasons() {
        assertThat(ColumnMappingSuggester.suggest("Personnummer")).contains(MappingTargetKind.IGNORE);
        assertThat(ColumnMappingSuggester.suggestDetailed("Personnummer"))
                .get()
                .extracting(ColumnSuggestion::reason)
                .asString()
                .contains("integritetsskydd");

        assertThat(ColumnMappingSuggester.suggest("Tid")).contains(MappingTargetKind.IGNORE);
        assertThat(ColumnMappingSuggester.suggestDetailed("Tid"))
                .get()
                .extracting(ColumnSuggestion::reason)
                .asString()
                .contains("under Deltagare");

        assertThat(ColumnMappingSuggester.suggest("RankInfo")).contains(MappingTargetKind.IGNORE);
        assertThat(ColumnMappingSuggester.suggest("AnmäldAktivitet")).contains(MappingTargetKind.IGNORE);
        assertThat(ColumnMappingSuggester.suggest("Varningar")).contains(MappingTargetKind.IGNORE);
    }

    @Test
    void blankHeaderYieldsNoSuggestion() {
        assertThat(ColumnMappingSuggester.suggest("")).isEmpty();
        assertThat(ColumnMappingSuggester.suggest(null)).isEmpty();
    }
}
