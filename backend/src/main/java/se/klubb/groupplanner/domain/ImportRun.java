package se.klubb.groupplanner.domain;

import java.time.Instant;

/**
 * Audit record of one import wizard commit (spec §8.3 step 8 "Importera") — how many rows were
 * imported/skipped, what warnings were shown, and what per-row decisions the user made for
 * ambiguous rows (spec §8.7 person matching). Never persisted until the commit step; nothing
 * before that touches the database (docs/design/02-product-data-ui.md §2: "nothing persisted
 * until commit").
 *
 * <p>{@code warningsJson}/{@code decisionsJson} are JSON-serialized snapshots of the validation
 * warnings and the final per-row decisions at commit time, kept for audit purposes. They contain
 * row indices, statuses and reasons - never {@code importedComment}/{@code internalNote} free text
 * (CLAUDE.md confidentiality rules).
 */
public record ImportRun(
        String id,
        String activityPlanId,
        String fileName,
        String sheetName,
        String importTemplateId,
        int totalRows,
        int importedRows,
        int skippedRows,
        String warningsJson,
        String decisionsJson,
        Instant createdAt) {
}
