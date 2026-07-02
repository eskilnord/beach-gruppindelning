package se.klubb.groupplanner.importer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.klubb.groupplanner.importer.parse.ParsedCell;
import se.klubb.groupplanner.importer.parse.ParsedSheet;

/** Builds an {@link ExtractedRow} from the raw grid plus the session's current column mappings. */
public final class RowExtractor {

    private RowExtractor() {
    }

    public static ExtractedRow extract(ParsedSheet sheet, int rowIndex, List<ColumnMapping> mappings) {
        String firstName = null;
        String lastName = null;
        String displayName = null;
        String email = null;
        String phone = null;
        String externalId = null;
        ParsedCell rankingPointsCell = null;
        String previousGroupName = null;
        ParsedCell previousGroupLevelCell = null;
        ParsedCell manualLevelScoreCell = null;
        String comment = null;
        String internalNote = null;
        String coachName = null;
        boolean isCoach = false;
        Map<String, String> customFieldRaw = new LinkedHashMap<>();
        Map<String, ParsedCell> customFieldCell = new LinkedHashMap<>();

        for (ColumnMapping mapping : mappings) {
            ParsedCell cell = sheet.cellAt(rowIndex, mapping.columnIndex());
            String raw = cell.isBlank() ? null : cell.rawString();
            switch (mapping.kind()) {
                case FIRST_NAME -> firstName = raw;
                case LAST_NAME -> lastName = raw;
                case DISPLAY_NAME -> displayName = raw;
                case EMAIL -> email = raw;
                case PHONE -> phone = raw;
                case EXTERNAL_ID -> externalId = raw;
                case RANKING_POINTS -> rankingPointsCell = cell;
                case PREVIOUS_GROUP_NAME -> previousGroupName = raw;
                case PREVIOUS_GROUP_LEVEL -> previousGroupLevelCell = cell;
                case MANUAL_LEVEL_SCORE -> manualLevelScoreCell = cell;
                case COMMENT -> comment = raw;
                case INTERNAL_NOTE -> internalNote = raw;
                case COACH_NAME -> coachName = raw;
                case IS_COACH -> isCoach = isCoach || isTruthy(raw);
                case CUSTOM_FIELD -> {
                    String key = mapping.customFieldKey();
                    if (raw != null) {
                        customFieldRaw.merge(key, raw, (a, b) -> a + ", " + b);
                        customFieldCell.merge(key, cell, (a, b) -> ParsedCell.ofString(a.rawString() + ", " + b.rawString()));
                    }
                }
                case IGNORE -> {
                    // Nothing to extract.
                }
            }
        }

        return new ExtractedRow(
                rowIndex, firstName, lastName, displayName, email, phone, externalId,
                rankingPointsCell, previousGroupName, previousGroupLevelCell, manualLevelScoreCell,
                comment, internalNote, coachName, isCoach, customFieldRaw, customFieldCell);
    }

    private static boolean isTruthy(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String normalized = raw.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "x", "ja", "yes", "true", "1", "sant", "sann" -> true;
            default -> false;
        };
    }
}
