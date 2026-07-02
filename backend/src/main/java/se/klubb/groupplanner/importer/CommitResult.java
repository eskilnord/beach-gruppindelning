package se.klubb.groupplanner.importer;

import java.util.List;

/** Result of a successful {@code POST /sessions/{sid}/commit} (spec §8.3 step 8). */
public record CommitResult(int imported, int skipped, List<String> warnings, String importRunId, String savedTemplateId) {
}
