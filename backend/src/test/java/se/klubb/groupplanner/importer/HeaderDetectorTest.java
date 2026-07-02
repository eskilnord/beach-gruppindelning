package se.klubb.groupplanner.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.importer.fixture.MessyWorkbookBuilder;
import se.klubb.groupplanner.importer.parse.ParsedCell;
import se.klubb.groupplanner.importer.parse.ParsedSheet;
import se.klubb.groupplanner.importer.parse.ParsedWorkbook;
import se.klubb.groupplanner.importer.parse.XlsxParser;

class HeaderDetectorTest {

    @Test
    void detectsHeaderAtRowZeroOnTheMessyFixture() throws Exception {
        MessyWorkbookBuilder.BuiltWorkbook built = MessyWorkbookBuilder.build();
        ParsedWorkbook workbook = XlsxParser.parse(new ByteArrayInputStream(built.bytes()));
        ParsedSheet sheet = workbook.sheets().get(0);

        assertThat(HeaderDetector.detect(sheet)).isEqualTo(built.headerRowIndex());
    }

    @Test
    void detectsHeaderRowBelowSomeIntroRows() {
        ParsedSheet sheet = sheetOf(
                List.of(cell("Träningsanmälan VT26")),
                List.of(), // blank row
                List.of(cell("Förnamn"), cell("Efternamn"), cell("Rank"), cell("Epost")),
                List.of(cell("Nils"), cell("Åström"), cell("940"), cell("nils@example.se")));

        assertThat(HeaderDetector.detect(sheet)).isEqualTo(2);
    }

    @Test
    void fallsBackToRowZeroWhenNothingScoresHighEnough() {
        ParsedSheet sheet = sheetOf(
                List.of(cell("x")),
                List.of(cell("y")));

        assertThat(HeaderDetector.detect(sheet)).isEqualTo(0);
    }

    private static ParsedSheet sheetOf(List<ParsedCell>... rows) {
        return new ParsedSheet("Test", List.of(rows));
    }

    private static ParsedCell cell(String text) {
        return ParsedCell.ofString(text);
    }
}
