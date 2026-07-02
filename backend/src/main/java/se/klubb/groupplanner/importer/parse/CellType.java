package se.klubb.groupplanner.importer.parse;

/**
 * The kind of value a {@link ParsedCell} carries, independent of source format (xlsx cells have a
 * native POI type; CSV cells are always {@link #STRING} or {@link #BLANK} since CSV has no typed
 * cells - see docs/plan.md's red-team correction: "locale-aware parsing ... only for text-typed
 * cells").
 */
public enum CellType {
    /** Plain text. */
    STRING,
    /** A number (xlsx numeric cell not formatted as a date/time). */
    NUMERIC,
    /** A calendar date (xlsx cell formatted as a date, with a non-zero day component). */
    DATE,
    /** A time-of-day only (xlsx cell formatted as a date/time but with no calendar-date component). */
    TIME,
    /** A boolean cell. */
    BOOLEAN,
    /** Empty cell / missing column in a ragged row. */
    BLANK,
    /** A formula error, or any cell that could not be read without throwing (never propagated). */
    ERROR
}
