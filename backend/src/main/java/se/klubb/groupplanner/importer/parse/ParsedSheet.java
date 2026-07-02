package se.klubb.groupplanner.importer.parse;

import java.util.List;

/**
 * One parsed worksheet (xlsx) or the single implicit sheet of a CSV file: a name and a grid of
 * {@link ParsedCell} rows. Rows may be ragged (different lengths); {@link #cellAt} returns {@link
 * ParsedCell#blank()} for any out-of-range access rather than throwing, so callers never need
 * bounds-check every access - "tolerate ... never crash" (M3 brief).
 */
public record ParsedSheet(String name, List<List<ParsedCell>> rows) {

    public int rowCount() {
        return rows.size();
    }

    /** The widest row in the sheet - the stable column count to use as an iteration bound. */
    public int columnCount() {
        int max = 0;
        for (List<ParsedCell> row : rows) {
            max = Math.max(max, row.size());
        }
        return max;
    }

    public ParsedCell cellAt(int rowIndex, int columnIndex) {
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            return ParsedCell.blank();
        }
        List<ParsedCell> row = rows.get(rowIndex);
        if (columnIndex < 0 || columnIndex >= row.size()) {
            return ParsedCell.blank();
        }
        ParsedCell cell = row.get(columnIndex);
        return cell == null ? ParsedCell.blank() : cell;
    }

    public List<ParsedCell> rowAt(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            return List.of();
        }
        return rows.get(rowIndex);
    }

    /** True if every cell in the row is blank (spec §8.6 "tomma rader"). */
    public boolean isRowEntirelyBlank(int rowIndex) {
        for (ParsedCell cell : rowAt(rowIndex)) {
            if (!cell.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
