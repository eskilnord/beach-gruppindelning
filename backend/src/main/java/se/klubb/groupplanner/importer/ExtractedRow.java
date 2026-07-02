package se.klubb.groupplanner.importer;

import java.util.Map;
import se.klubb.groupplanner.importer.parse.ParsedCell;

/**
 * The mapped values of one data row, pulled out of the raw grid according to the session's current
 * {@link ColumnMapping}s (spec §8.4). Numeric targets ({@code rankingPoints}, {@code
 * previousGroupLevel}, {@code manualLevelScore}) keep the whole {@link ParsedCell} (not just its
 * text) so {@link NumericValue#resolve(ParsedCell)} can use the xlsx-typed value when the cell is
 * genuinely {@code NUMERIC} and only fall back to locale-aware text parsing for text-typed cells -
 * docs/plan.md's red-team correction ("map numeric targets from typed values; locale-aware parsing
 * ... only for text-typed cells").
 *
 * <p>{@code customFieldRaw}/{@code customFieldCell} key on the target field's key and support
 * (rare) multiple columns mapped to the same custom field by concatenating their raw text - {@link
 * #customFieldCell} then loses per-cell typing in that case, which only matters for the "ogiltiga
 * tider" check (falls back to text-only parsing, still correct, just less precise).
 */
public record ExtractedRow(
        int rowIndex,
        String firstName,
        String lastName,
        String displayName,
        String email,
        String phone,
        String externalId,
        ParsedCell rankingPointsCell,
        String previousGroupName,
        ParsedCell previousGroupLevelCell,
        ParsedCell manualLevelScoreCell,
        String comment,
        String internalNote,
        String coachName,
        boolean isCoach,
        Map<String, String> customFieldRaw,
        Map<String, ParsedCell> customFieldCell) {

    public boolean hasAnyName() {
        return isNonBlank(firstName) || isNonBlank(lastName) || isNonBlank(displayName);
    }

    public static boolean isNonBlank(String s) {
        return s != null && !s.isBlank();
    }
}
