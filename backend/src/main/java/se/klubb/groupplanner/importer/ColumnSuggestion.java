package se.klubb.groupplanner.importer;

/**
 * A single-column mapping suggestion with a plain-Swedish reason (one-click import / "no black
 * box"): the wizard and the review screen both surface {@link #reason()} so staff can see why a
 * column was mapped or ignored without guessing.
 */
public record ColumnSuggestion(MappingTargetKind kind, String reason, double confidence) {

    public static ColumnSuggestion of(MappingTargetKind kind, String reason, double confidence) {
        return new ColumnSuggestion(kind, reason, confidence);
    }
}
