package se.klubb.groupplanner.importer.parse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.importer.fixture.MessyWorkbookBuilder;

/**
 * Parser unit tests over the messy fixture (M3 brief): mixed cell types in one column, cell
 * comments and threaded comments tolerated, merged cells tolerated, typed numeric/time values
 * preserved, å/ä/ö intact.
 */
class XlsxParserTest {

    private static ParsedWorkbook parseFixture() throws Exception {
        MessyWorkbookBuilder.BuiltWorkbook built = MessyWorkbookBuilder.build();
        return XlsxParser.parse(new ByteArrayInputStream(built.bytes()));
    }

    @Test
    void neverCrashesOnTheMessyFixtureAndReadsAllSheets() throws Exception {
        ParsedWorkbook workbook = parseFixture();
        assertThat(workbook.sheets()).hasSize(1);
        assertThat(workbook.sheetByName(MessyWorkbookBuilder.SHEET_NAME)).isPresent();
    }

    @Test
    void excelTimeCellSurvivesAsTypedLocalTime() throws Exception {
        MessyWorkbookBuilder.BuiltWorkbook built = MessyWorkbookBuilder.build();
        ParsedWorkbook workbook = XlsxParser.parse(new ByteArrayInputStream(built.bytes()));
        ParsedSheet sheet = workbook.sheets().get(0);

        ParsedCell timeCell = sheet.cellAt(built.row("p001"), 6);
        assertThat(timeCell.cellType()).isEqualTo(CellType.TIME);
        assertThat(timeCell.typedValue()).isEqualTo(LocalTime.of(18, 0));
    }

    @Test
    void mixedTypesInSameTidColumn() throws Exception {
        MessyWorkbookBuilder.BuiltWorkbook built = MessyWorkbookBuilder.build();
        ParsedWorkbook workbook = XlsxParser.parse(new ByteArrayInputStream(built.bytes()));
        ParsedSheet sheet = workbook.sheets().get(0);

        // p001: genuine Excel time. p003: Swedish "ej N" shorthand text. p004: genuinely invalid text.
        assertThat(sheet.cellAt(built.row("p001"), 6).cellType()).isEqualTo(CellType.TIME);
        ParsedCell ejCell = sheet.cellAt(built.row("p003"), 6);
        assertThat(ejCell.cellType()).isEqualTo(CellType.STRING);
        assertThat(ejCell.rawString()).isEqualTo("ej 21");
        ParsedCell invalidCell = sheet.cellAt(built.row("p004"), 6);
        assertThat(invalidCell.cellType()).isEqualTo(CellType.STRING);
        assertThat(invalidCell.rawString()).isEqualTo("arton");
    }

    @Test
    void numericCellKeepsTypedDoubleValue() throws Exception {
        MessyWorkbookBuilder.BuiltWorkbook built = MessyWorkbookBuilder.build();
        ParsedWorkbook workbook = XlsxParser.parse(new ByteArrayInputStream(built.bytes()));
        ParsedSheet sheet = workbook.sheets().get(0);

        ParsedCell rankCell = sheet.cellAt(built.row("p001"), 3);
        assertThat(rankCell.cellType()).isEqualTo(CellType.NUMERIC);
        assertThat(rankCell.typedValue()).isEqualTo(940.0);
    }

    @Test
    void textFormattedNumberWithNbspThousandsStaysTextTyped() throws Exception {
        MessyWorkbookBuilder.BuiltWorkbook built = MessyWorkbookBuilder.build();
        ParsedWorkbook workbook = XlsxParser.parse(new ByteArrayInputStream(built.bytes()));
        ParsedSheet sheet = workbook.sheets().get(0);

        ParsedCell rankCell = sheet.cellAt(built.row("p003"), 3);
        assertThat(rankCell.cellType()).isEqualTo(CellType.STRING);
        assertThat(SwedishNumberParser.parse(rankCell.rawString())).isEqualTo(1234.5);
    }

    @Test
    void swedishCharactersSurviveIntact() throws Exception {
        MessyWorkbookBuilder.BuiltWorkbook built = MessyWorkbookBuilder.build();
        ParsedWorkbook workbook = XlsxParser.parse(new ByteArrayInputStream(built.bytes()));
        ParsedSheet sheet = workbook.sheets().get(0);

        assertThat(sheet.cellAt(built.row("p003"), 2).rawString()).isEqualTo("Söderberg");
    }

    @Test
    void blankRowIsDetectedAsEntirelyBlank() throws Exception {
        MessyWorkbookBuilder.BuiltWorkbook built = MessyWorkbookBuilder.build();
        ParsedWorkbook workbook = XlsxParser.parse(new ByteArrayInputStream(built.bytes()));
        ParsedSheet sheet = workbook.sheets().get(0);

        assertThat(sheet.isRowEntirelyBlank(built.row("blankRow1"))).isTrue();
    }

    @Test
    void countRowHasNoNameDataButIsNotEntirelyBlank() throws Exception {
        MessyWorkbookBuilder.BuiltWorkbook built = MessyWorkbookBuilder.build();
        ParsedWorkbook workbook = XlsxParser.parse(new ByteArrayInputStream(built.bytes()));
        ParsedSheet sheet = workbook.sheets().get(0);

        int countRow = built.row("group1CountRow");
        assertThat(sheet.isRowEntirelyBlank(countRow)).isFalse();
        assertThat(sheet.cellAt(countRow, 1).isBlank()).isTrue(); // Förnamn column is blank.
        assertThat(sheet.cellAt(countRow, 0).rawString()).isEqualTo("4 spelare");
    }

    @Test
    void mergedRegionCellsDoNotCrashAndBlankCellsInsideAreReadable() throws Exception {
        MessyWorkbookBuilder.BuiltWorkbook built = MessyWorkbookBuilder.build();
        ParsedWorkbook workbook = XlsxParser.parse(new ByteArrayInputStream(built.bytes()));
        ParsedSheet sheet = workbook.sheets().get(0);

        int headingRow = built.row("kolistaHeaderRow");
        assertThat(sheet.cellAt(headingRow, 0).rawString()).isEqualTo("Kölista");
        // Columns 1-3 are part of the merged region and hold no value of their own - must not crash.
        assertThat(sheet.cellAt(headingRow, 1).isBlank()).isTrue();
    }
}
