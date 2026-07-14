package se.klubb.groupplanner.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.importer.parse.ParsedCell;
import se.klubb.groupplanner.importer.parse.ParsedSheet;
import se.klubb.groupplanner.importer.parse.ParsedWorkbook;

/**
 * Unit tests for {@link ImportSession}'s sheet-scoped state, in particular the row-decision map
 * (bug: decisions used to be keyed only by rowIndex and leaked across sheets when the user switched
 * the selected sheet).
 */
class ImportSessionTest {

    private static ParsedSheet sheet(String name, int rowCount) {
        List<List<ParsedCell>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            rows.add(List.of(ParsedCell.ofString("row" + i)));
        }
        return new ParsedSheet(name, rows);
    }

    private static ImportSession session(ParsedSheet... sheets) {
        ParsedWorkbook workbook = new ParsedWorkbook(List.of(sheets));
        Instant now = Instant.now();
        return new ImportSession("sess-1", "plan-1", "test.xlsx", workbook, now, now.plusSeconds(3600));
    }

    @Test
    void decisionsAreScopedPerSheetNotSharedAcrossSheets() {
        ImportSession session = session(sheet("A", 10), sheet("B", 10));

        session.setDecision("A", 5, RowDecision.skip());

        assertThat(session.decision("A", 5)).contains(RowDecision.skip());
        assertThat(session.decision("B", 5)).isEmpty();
    }

    @Test
    void switchingSheetsAndBackPreservesEachSheetsOwnDecisions() {
        ImportSession session = session(sheet("A", 10), sheet("B", 10));

        session.setHeaderRow("A", 0);
        session.setDecision("A", 5, RowDecision.skip());
        session.setHeaderRow("B", 0);
        session.setDecision("B", 5, RowDecision.createNew());

        assertThat(session.decision("B", 5)).contains(RowDecision.createNew());

        session.setHeaderRow("A", 0);

        assertThat(session.decision("A", 5)).contains(RowDecision.skip());
    }

    @Test
    void movingASheetsHeaderRowClearsThatSheetsDecisionsSinceRowIndicesShift() {
        ImportSession session = session(sheet("A", 10), sheet("B", 10));

        session.setHeaderRow("A", 0);
        session.setDecision("A", 5, RowDecision.skip());
        session.setDecision("B", 5, RowDecision.createNew());

        session.setHeaderRow("A", 1); // Header moved - existing rowIndex-keyed decisions for A are stale.

        assertThat(session.decision("A", 5)).isEmpty();
        assertThat(session.decision("B", 5)).contains(RowDecision.createNew()); // Other sheet untouched.
    }
}
