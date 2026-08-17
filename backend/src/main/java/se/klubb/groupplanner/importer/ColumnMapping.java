package se.klubb.groupplanner.importer;

import se.klubb.groupplanner.api.error.BadRequestException;

/**
 * One column's mapping target (spec §8.4). {@code target} is serialized on the wire as a plain
 * string: one of the {@link MappingTargetKind} wire names, or {@code "customField:<fieldKey>"} for
 * {@link MappingTargetKind#CUSTOM_FIELD}.
 */
public record ColumnMapping(int columnIndex, MappingTargetKind kind, String customFieldKey) {

    /**
     * The synthetic "Grupp i filen" column (WP1, {@link BlockStructureDetector}): not a real column
     * in the source file - its value is derived per-row from the detected group-block structure
     * instead of read from {@code sheet.cellAt(rowIndex, columnIndex)}. Only valid when the sheet
     * has a detected {@link BlockStructureDetector.BlockStructure} (see {@code ImportController}).
     */
    public static final int BLOCK_GROUP_COLUMN_INDEX = -1;

    private static final String CUSTOM_FIELD_PREFIX = "customField:";

    public ColumnMapping {
        if (kind == MappingTargetKind.CUSTOM_FIELD && (customFieldKey == null || customFieldKey.isBlank())) {
            throw new BadRequestException("customField mapping requires a field key, e.g. 'customField:wantsCoach'");
        }
        if (kind != MappingTargetKind.CUSTOM_FIELD && customFieldKey != null) {
            throw new BadRequestException("customFieldKey is only valid for target 'customField:<key>'");
        }
    }

    /** Parses the wire-format target string (spec §8.4 grammar) for a given column index. */
    public static ColumnMapping fromTargetString(int columnIndex, String target) {
        if (target == null || target.isBlank()) {
            throw new BadRequestException("Column " + columnIndex + " is missing a mapping target");
        }
        if (target.startsWith(CUSTOM_FIELD_PREFIX)) {
            String key = target.substring(CUSTOM_FIELD_PREFIX.length());
            return new ColumnMapping(columnIndex, MappingTargetKind.CUSTOM_FIELD, key);
        }
        MappingTargetKind kind;
        try {
            kind = MappingTargetKind.fromWireName(target);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown mapping target '" + target + "' for column " + columnIndex);
        }
        return new ColumnMapping(columnIndex, kind, null);
    }

    /** Renders back to the wire-format target string. */
    public String targetString() {
        if (kind == MappingTargetKind.CUSTOM_FIELD) {
            return CUSTOM_FIELD_PREFIX + customFieldKey;
        }
        return kind.wireName();
    }
}
