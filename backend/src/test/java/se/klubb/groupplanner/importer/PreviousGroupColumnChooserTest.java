package se.klubb.groupplanner.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PreviousGroupColumnChooserTest {

    private static PreviousGroupColumnChooser.Candidate real(String header, String... samples) {
        return new PreviousGroupColumnChooser.Candidate(3, header, List.of(samples));
    }

    private static PreviousGroupColumnChooser.Candidate block(String... samples) {
        return new PreviousGroupColumnChooser.Candidate(ColumnMapping.BLOCK_GROUP_COLUMN_INDEX, "Grupp i filen", List.of(samples));
    }

    @Test
    void syntheticColumnWinsOnATie() {
        // Neither candidate carries a parseable term - both fully parse to a group order, so the
        // tie-break default applies: the synthetic block column wins.
        PreviousGroupColumnChooser.Candidate realColumn = real("Tidigare grupp", "Grupp 1", "Grupp 2");
        PreviousGroupColumnChooser.Candidate blockColumn = block("Grupp 1", "Grupp 2", "Grupp 3");

        PreviousGroupColumnChooser.Decision decision =
                PreviousGroupColumnChooser.choose(realColumn, blockColumn, false, false);

        assertThat(decision.chosenColumnIndex()).isEqualTo(ColumnMapping.BLOCK_GROUP_COLUMN_INDEX);
        assertThat(decision.confidence()).isEqualTo(1.0);
        assertThat(decision.loserColumnIndex()).isEqualTo(realColumn.columnIndex());
        assertThat(decision.loserReasonSv()).isNotBlank();
    }

    @Test
    void realColumnWinsWhenItCarriesANewerTerm() {
        // B5 review fix: rule 3 now only applies when BOTH candidates carry a parseable term - so this
        // genuine "real wins" case must give the BLOCK column a term too (an older one), not leave it
        // term-less (see termBearingRealColumnLosesToTermlessBlockLabels below for that regression).
        PreviousGroupColumnChooser.Candidate realColumn =
                real("Tidigare grupp", "Torsdag Herr 1 (Vårtermin 2025)", "Torsdag Herr 2 (Vårtermin 2025)");
        PreviousGroupColumnChooser.Candidate blockColumn = block("Torsdag Herr 1 (Hösttermin 2024)"); // older term.

        PreviousGroupColumnChooser.Decision decision =
                PreviousGroupColumnChooser.choose(realColumn, blockColumn, false, false);

        assertThat(decision.chosenColumnIndex()).isEqualTo(realColumn.columnIndex());
        assertThat(decision.chosenReasonSv()).contains("Tidigare grupp").contains("nyare termin");
        assertThat(decision.loserColumnIndex()).isEqualTo(ColumnMapping.BLOCK_GROUP_COLUMN_INDEX);
    }

    @Test
    void termBearingRealColumnLosesToTermlessBlockLabels() {
        // BLOCKER regression (B5 review): GroupedXlsxWriter NEVER labels a block heading with a term -
        // block labels are bare group names - while a real 'Tidigare grupp' column routinely does. Pre-
        // fix, rule 3 compared "highest term key on either side" and so ALWAYS favored the term-bearing
        // real column over the term-less (but actually CURRENT) block, with the actively false reason
        // "innehåller en nyare termin". Requiring BOTH sides to carry a term before rule 3 may apply
        // fixes this: the real column's term is simply not comparable here, so the decision falls
        // through to the tie-default (rule 4) - the synthetic block column wins.
        PreviousGroupColumnChooser.Candidate realColumn =
                real("Tidigare grupp", "Torsdag Herr 1 (Vårtermin 2025)", "Torsdag Herr 2 (Vårtermin 2025)");
        PreviousGroupColumnChooser.Candidate blockColumn = block("Grupp 1", "Grupp 2"); // no term at all.

        PreviousGroupColumnChooser.Decision decision =
                PreviousGroupColumnChooser.choose(realColumn, blockColumn, false, false);

        assertThat(decision.chosenColumnIndex()).isEqualTo(ColumnMapping.BLOCK_GROUP_COLUMN_INDEX);
        assertThat(decision.chosenReasonSv()).doesNotContain("nyare termin");
        assertThat(decision.loserColumnIndex()).isEqualTo(realColumn.columnIndex());
        assertThat(decision.loserReasonSv()).isNotBlank();
    }

    @Test
    void blockColumnWinsWhenItCarriesANewerTerm() {
        PreviousGroupColumnChooser.Candidate realColumn = real("Tidigare grupp", "Grupp 1", "Grupp 2"); // no term.
        PreviousGroupColumnChooser.Candidate blockColumn =
                block("Torsdag Herr 1 (Vårtermin 2025)", "Torsdag Herr 2 (Vårtermin 2025)");

        PreviousGroupColumnChooser.Decision decision =
                PreviousGroupColumnChooser.choose(realColumn, blockColumn, false, false);

        assertThat(decision.chosenColumnIndex()).isEqualTo(ColumnMapping.BLOCK_GROUP_COLUMN_INDEX);
        assertThat(decision.loserColumnIndex()).isEqualTo(realColumn.columnIndex());
    }

    @Test
    void templatePinOnTheRealColumnWinsRegardlessOfTermEvidence() {
        // Real column has no term at all and would otherwise lose the tie to the synthetic column -
        // but a saved template pin always wins outright (rule 1, unchanged pre-B5 behavior).
        PreviousGroupColumnChooser.Candidate realColumn = real("Tidigare grupp", "Grupp 1");
        PreviousGroupColumnChooser.Candidate blockColumn = block("Grupp 1", "Grupp 2");

        PreviousGroupColumnChooser.Decision decision =
                PreviousGroupColumnChooser.choose(realColumn, blockColumn, true, false);

        assertThat(decision.chosenColumnIndex()).isEqualTo(realColumn.columnIndex());
        assertThat(decision.chosenReasonSv()).contains("importmall");
    }

    @Test
    void templatePinOnTheBlockColumnWinsRegardlessOfTermEvidence() {
        PreviousGroupColumnChooser.Candidate realColumn =
                real("Tidigare grupp", "Torsdag Herr 1 (Vårtermin 2025)"); // has newer term evidence.
        PreviousGroupColumnChooser.Candidate blockColumn = block("Grupp 1");

        PreviousGroupColumnChooser.Decision decision =
                PreviousGroupColumnChooser.choose(realColumn, blockColumn, false, true);

        assertThat(decision.chosenColumnIndex()).isEqualTo(ColumnMapping.BLOCK_GROUP_COLUMN_INDEX);
        assertThat(decision.chosenReasonSv()).contains("importmall");
    }

    @Test
    void onlyTheRealColumnExistsSoItWinsByDefault() {
        PreviousGroupColumnChooser.Candidate realColumn = real("Tidigare grupp", "Grupp 1");

        PreviousGroupColumnChooser.Decision decision = PreviousGroupColumnChooser.choose(realColumn, null, false, false);

        assertThat(decision.chosenColumnIndex()).isEqualTo(realColumn.columnIndex());
        assertThat(decision.loserColumnIndex()).isNull();
        assertThat(decision.loserReasonSv()).isNull();
    }

    @Test
    void onlyTheBlockColumnExistsSoItWinsByDefault() {
        PreviousGroupColumnChooser.Candidate blockColumn = block("Grupp 1");

        PreviousGroupColumnChooser.Decision decision = PreviousGroupColumnChooser.choose(null, blockColumn, false, false);

        assertThat(decision.chosenColumnIndex()).isEqualTo(ColumnMapping.BLOCK_GROUP_COLUMN_INDEX);
        assertThat(decision.loserColumnIndex()).isNull();
    }

    @Test
    void neitherCandidateThrows() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PreviousGroupColumnChooser.choose(null, null, false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void majorityNullOrdinalFallbackPicksTheRealColumnOnATie() {
        // Both candidates tie on term-recency (neither carries a term), but the block labels are
        // plain colors that yield NO derivable group order for a majority of samples, while the real
        // column's values all do - the exception to rule 4 kicks in and the real column wins.
        PreviousGroupColumnChooser.Candidate realColumn = real("Tidigare grupp", "Grupp 1", "Grupp 2", "Grupp 3");
        PreviousGroupColumnChooser.Candidate blockColumn = block("Blå", "Röd", "Gul"); // pure color names, no ordinal.

        PreviousGroupColumnChooser.Decision decision =
                PreviousGroupColumnChooser.choose(realColumn, blockColumn, false, false);

        assertThat(decision.chosenColumnIndex()).isEqualTo(realColumn.columnIndex());
        assertThat(decision.chosenReasonSv()).contains("Tidigare grupp");
    }

    @Test
    void bothCandidatesWithNoSamplesAtAllDefaultToTheSyntheticColumn() {
        // No non-blank samples anywhere on either side (both parse rates are 0.0) - the majority-null
        // guard (which needs the real column's rate >= 0.5) cannot fire, so the tie falls through to
        // the plain rule-4 default: the synthetic column wins.
        PreviousGroupColumnChooser.Candidate realColumn = real("Tidigare grupp");
        PreviousGroupColumnChooser.Candidate blockColumn = block();

        PreviousGroupColumnChooser.Decision decision =
                PreviousGroupColumnChooser.choose(realColumn, blockColumn, false, false);

        assertThat(decision.chosenColumnIndex()).isEqualTo(ColumnMapping.BLOCK_GROUP_COLUMN_INDEX);
    }
}
