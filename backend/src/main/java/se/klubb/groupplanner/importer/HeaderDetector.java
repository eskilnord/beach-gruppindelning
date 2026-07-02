package se.klubb.groupplanner.importer;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import se.klubb.groupplanner.importer.parse.ParsedCell;
import se.klubb.groupplanner.importer.parse.ParsedSheet;

/**
 * Header-row heuristic (spec §8.3 step 4, M3 brief): "first row where >=60% of cells are non-empty
 * distinct strings and the row below looks like data". Scans only the first {@link #MAX_SCAN_ROWS}
 * rows (a header never legitimately lives deeper than that) and is always overridable by the user
 * (see {@code ImportSession#setHeaderRow}) - this is a suggestion, never a silent decision.
 */
public final class HeaderDetector {

    static final int MAX_SCAN_ROWS = 10;
    static final double MIN_DISTINCT_NON_EMPTY_RATIO = 0.6;

    private HeaderDetector() {
    }

    /** Best-guess header row index (0-based); falls back to row 0 if nothing scores highly enough. */
    public static int detect(ParsedSheet sheet) {
        int scanLimit = Math.min(MAX_SCAN_ROWS - 1, Math.max(0, sheet.rowCount() - 1));
        for (int rowIndex = 0; rowIndex <= scanLimit; rowIndex++) {
            if (looksLikeHeaderRow(sheet, rowIndex) && rowBelowLooksLikeData(sheet, rowIndex)) {
                return rowIndex;
            }
        }
        return 0;
    }

    private static boolean looksLikeHeaderRow(ParsedSheet sheet, int rowIndex) {
        var row = sheet.rowAt(rowIndex);
        if (row.isEmpty()) {
            return false;
        }
        Set<String> distinctNonEmpty = new HashSet<>();
        for (ParsedCell cell : row) {
            if (!cell.isBlank()) {
                distinctNonEmpty.add(cell.rawString().strip().toLowerCase(Locale.ROOT));
            }
        }
        double ratio = (double) distinctNonEmpty.size() / row.size();
        return ratio >= MIN_DISTINCT_NON_EMPTY_RATIO;
    }

    private static boolean rowBelowLooksLikeData(ParsedSheet sheet, int headerRowIndex) {
        int belowIndex = headerRowIndex + 1;
        if (belowIndex >= sheet.rowCount()) {
            return false; // Nothing below at all - can't be a real header for any data.
        }
        return !sheet.isRowEntirelyBlank(belowIndex);
    }
}
