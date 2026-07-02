package se.klubb.groupplanner.domain;

import java.time.Instant;

/**
 * A reusable column-mapping for the import wizard (spec §8.3 "Spara som importmall"), looked up by
 * {@code headerHash} so re-importing a structurally identical file can reuse its mapping without a
 * re-mapping step (docs/plan.md: "reusable import templates keyed by header hash").
 *
 * <p>{@code mappingJson} is a JSON array of {@code {columnIndex, target}} objects (see
 * {@code se.klubb.groupplanner.importer.ColumnMapping#toTargetString()} for the {@code target}
 * string grammar), serialized/deserialized by {@code se.klubb.groupplanner.importer.ImportSessionService}.
 */
public record ImportTemplate(
        String id,
        String name,
        String headerHash,
        String mappingJson,
        Instant createdAt) {
}
