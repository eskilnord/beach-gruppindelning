package se.klubb.groupplanner.exporter;

import java.util.List;

/**
 * One placed player's export row (spec §20.2). {@code comment} is {@code null} unless the caller
 * explicitly opted in ({@code includeComments=true}, §20.3) — {@link ExportDataAssembler} never
 * even reads {@code participant_profile.imported_comment}/{@code internal_note} into this field
 * otherwise, so there is nothing for a writer to accidentally serialize (the definitive §23.9 leak
 * test scans the produced file's bytes for this reason: structural absence, not a rendering-time
 * filter).
 */
public record ExportPlayer(
        String displayName,
        Double rankingPoints,
        Double estimatedLevel,
        String previousGroupName,
        String comment,
        List<String> warnings) {
}
