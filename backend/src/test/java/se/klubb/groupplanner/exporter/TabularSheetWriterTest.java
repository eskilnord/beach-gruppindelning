package se.klubb.groupplanner.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * CSV formula injection guard (M7 review finding): Excel/LibreOffice interpret a CSV cell starting
 * with {@code =}, {@code +}, {@code -} or {@code @} as a formula even when the cell is quoted, so
 * user-controlled strings (names, comments, group names, solver warnings) flowing into {@link
 * FlatExporter}'s csv path must be neutralized. The xlsx path (explicit text cells) never needs this.
 */
class TabularSheetWriterTest {

    private static final List<String> HEADER = List.of("Namn");

    @Test
    void csvPrefixesFormulaLeadingCellsWithASingleQuoteButLeavesOrdinaryTextAlone() {
        List<List<String>> rows = List.of(
                HEADER,
                List.of("=HYPERLINK(\"x\")"),
                List.of(" +1+1"),
                List.of("\t=cmd"),
                List.of("@SUM"),
                List.of("-2+3"),
                List.of("Anna-Karin"));

        String text = csvText(rows);
        String[] lines = text.split("\\r\\n|\\n");

        assertThat(lines[1]).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(lines[2]).isEqualTo("' +1+1");
        assertThat(lines[3]).isEqualTo("'\t=cmd");
        assertThat(lines[4]).isEqualTo("'@SUM");
        assertThat(lines[5]).isEqualTo("'-2+3");
        assertThat(lines[6]).isEqualTo("Anna-Karin");
    }

    @Test
    void xlsxLeavesEveryCellAsPlainTextEvenWhenItLooksLikeAFormula() throws Exception {
        List<List<String>> rows = List.of(
                HEADER,
                List.of("=HYPERLINK(\"x\")"),
                List.of(" +1+1"),
                List.of("\t=cmd"),
                List.of("@SUM"),
                List.of("-2+3"),
                List.of("Anna-Karin"));

        byte[] bytes = TabularSheetWriter.xlsx("Export", rows);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();

            assertThat(text(sheet.getRow(1), formatter)).isEqualTo("=HYPERLINK(\"x\")");
            assertThat(text(sheet.getRow(2), formatter)).isEqualTo(" +1+1");
            assertThat(text(sheet.getRow(3), formatter)).isEqualTo("\t=cmd");
            assertThat(text(sheet.getRow(4), formatter)).isEqualTo("@SUM");
            assertThat(text(sheet.getRow(5), formatter)).isEqualTo("-2+3");
            assertThat(text(sheet.getRow(6), formatter)).isEqualTo("Anna-Karin");
        }
    }

    private static String csvText(List<List<String>> rows) {
        byte[] bytes = TabularSheetWriter.csv(rows);
        return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    }

    private static String text(Row row, DataFormatter formatter) {
        Cell cell = row.getCell(0);
        return cell == null ? "" : formatter.formatCellValue(cell);
    }
}
