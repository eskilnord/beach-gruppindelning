package se.klubb.groupplanner.importer.parse;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Parses a {@code .csv} file into a single-sheet {@link ParsedWorkbook}, applying the charset sniff
 * ({@link CharsetDetection}) and delimiter sniff ({@link DelimiterSniffer}) docs/plan.md's red-team
 * correction calls for. CSV has no native cell types, so every non-blank cell is {@link
 * CellType#STRING} - numeric/time interpretation for mapped columns happens later, via {@link
 * SwedishNumberParser}/{@link SwedishTimeParser} on the raw text (same "text-typed cells only" rule
 * xlsx text cells follow).
 */
public final class CsvParser {

    /** Name given to a CSV file's implicit single sheet, so the rest of the pipeline is sheet-agnostic. */
    public static final String SHEET_NAME = "CSV";

    private CsvParser() {
    }

    public static ParsedWorkbook parse(InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        CharsetDetection.Decoded decoded = CharsetDetection.decode(bytes);
        char delimiter = DelimiterSniffer.sniff(decoded.text());

        CSVFormat format = CSVFormat.Builder.create(CSVFormat.DEFAULT)
                .setDelimiter(delimiter)
                .setQuote('"')
                .setIgnoreSurroundingSpaces(true)
                // Some rows are short (missing trailing columns) or wholly empty (blank separator
                // rows in the messy source layout) - never let a ragged row abort the parse.
                .setAllowMissingColumnNames(true)
                .get();

        List<List<ParsedCell>> rows = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(new StringReader(decoded.text()), format)) {
            for (CSVRecord record : parser) {
                List<ParsedCell> row = new ArrayList<>(record.size());
                for (String value : record) {
                    row.add(value == null || value.isEmpty() ? ParsedCell.blank() : ParsedCell.ofString(value));
                }
                rows.add(row);
            }
        }
        return new ParsedWorkbook(List.of(new ParsedSheet(SHEET_NAME, rows)));
    }
}
