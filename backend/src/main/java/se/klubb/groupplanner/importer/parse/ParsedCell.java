package se.klubb.groupplanner.importer.parse;

/**
 * One worksheet/CSV cell, carrying both its display text and (for xlsx numeric/date/time/boolean
 * cells) its typed value - per docs/plan.md's red-team correction: "Keep typed values: for
 * NUMERIC/DATE cells retain raw double / java.time value alongside the DataFormatter string; map
 * numeric targets from typed values; locale-aware parsing (`1 234,5`, NBSP thousands) only for
 * text-typed cells."
 *
 * <p>{@code typedValue} is one of {@link Double} ({@link CellType#NUMERIC}), {@link
 * java.time.LocalTime} ({@link CellType#TIME}), {@link java.time.LocalDate} ({@link
 * CellType#DATE}), {@link Boolean} ({@link CellType#BOOLEAN}), {@link String} ({@link
 * CellType#STRING}, same as {@code rawString}), or {@code null} ({@link CellType#BLANK}/{@link
 * CellType#ERROR}).
 *
 * <p>{@code rawString} is never {@code null} (empty string for blank cells) - it is always the
 * {@code DataFormatter}-rendered display text (sv-SE locale for xlsx), used for preview grids and
 * as the fallback text every column-mapping target can read from regardless of the cell's native
 * type.
 */
public record ParsedCell(String rawString, Object typedValue, CellType cellType) {

    private static final ParsedCell BLANK = new ParsedCell("", null, CellType.BLANK);

    public static ParsedCell blank() {
        return BLANK;
    }

    public static ParsedCell ofString(String text) {
        return new ParsedCell(text == null ? "" : text, text, CellType.STRING);
    }

    public boolean isBlank() {
        return cellType == CellType.BLANK || rawString.isBlank();
    }
}
