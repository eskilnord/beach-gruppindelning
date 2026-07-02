package se.klubb.groupplanner.exporter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Shared byte-level rendering for any "one header row + N data rows" tabular export ({@link
 * FlatExporter}, {@code AnonymizedExportService}) — a single first-row-is-header list-of-lists goes
 * either to an xlsx sheet or to a {@code ;}-delimited UTF-8-with-BOM csv (docs/design/02-product-data
 * -ui.md §7: "opens correctly in Swedish Excel").
 */
final class TabularSheetWriter {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private TabularSheetWriter() {
    }

    static byte[] xlsx(String sheetName, List<List<String>> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName);
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r);
                List<String> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    row.createCell(c).setCellValue(values.get(c));
                }
            }
            int columnCount = rows.isEmpty() ? 0 : rows.get(0).size();
            for (int c = 0; c < columnCount; c++) {
                sheet.autoSizeColumn(c);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write xlsx export", e);
        }
    }

    static byte[] csv(List<List<String>> rows) {
        CSVFormat format = CSVFormat.Builder.create(CSVFormat.DEFAULT).setDelimiter(';').setQuote('"').get();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(UTF8_BOM);
            try (OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                    CSVPrinter printer = new CSVPrinter(writer, format)) {
                for (List<String> row : rows) {
                    printer.printRecord(row);
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write csv export", e);
        }
    }
}
