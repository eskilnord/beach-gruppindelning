package se.klubb.groupplanner.importer;

import se.klubb.groupplanner.importer.parse.CellType;
import se.klubb.groupplanner.importer.parse.ParsedCell;
import se.klubb.groupplanner.importer.parse.SwedishNumberParser;

/**
 * Resolves a numeric-target cell to a {@link Double} per docs/plan.md's red-team correction: a
 * xlsx {@link CellType#NUMERIC} cell's typed value is used as-is; any other (text-typed) cell goes
 * through {@link SwedishNumberParser} for locale-aware parsing. Returns {@code null} both for blank
 * cells (nothing to parse) and for genuinely unparseable text (spec §8.6 "ogiltiga tal") - callers
 * distinguish the two via {@link ParsedCell#isBlank()} on the same cell.
 */
public final class NumericValue {

    private NumericValue() {
    }

    public static Double resolve(ParsedCell cell) {
        if (cell == null || cell.isBlank()) {
            return null;
        }
        if (cell.cellType() == CellType.NUMERIC && cell.typedValue() instanceof Double d) {
            return d;
        }
        return SwedishNumberParser.parse(cell.rawString());
    }
}
