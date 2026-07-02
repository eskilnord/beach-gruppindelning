package se.klubb.groupplanner.importer.parse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Dispatches to {@link XlsxParser} or {@link CsvParser} by file extension (spec §8.1: xlsx/csv). */
public final class WorkbookParsers {

    private WorkbookParsers() {
    }

    public static ParsedWorkbook parse(String fileName, InputStream inputStream) throws IOException {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) {
            return CsvParser.parse(inputStream);
        }
        if (lower.endsWith(".xlsx")) {
            return XlsxParser.parse(inputStream);
        }
        throw new IllegalArgumentException("Unsupported file type (only .xlsx and .csv are supported): " + fileName);
    }
}
