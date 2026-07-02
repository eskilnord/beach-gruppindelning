package se.klubb.groupplanner.domain;

/**
 * A write-through persistence log of one computed person-level explanation (spec §7.18,
 * V7__explainability.sql). One row per {@code (optimizationRunId, participantProfileId)} pair — an
 * audit/history trail (design §11.6: "cached ExplanationRecords are not deleted... just flagged"),
 * NOT the read-through serving cache: {@code se.klubb.groupplanner.explain.ExplanationService} always
 * serves from its in-memory {@code ExplanationCache} (keyed by {@code (runId, planRevision,
 * participantId)}, per design §11.4's lazy-computation-with-cache decision) and writes here
 * fire-and-forget on every fresh compute, for durability/audit across process restarts. See
 * backend/docs/m7-notes.md for the full reasoning.
 *
 * <p>The JSON columns hold the SAME DTOs {@code ExplanationService} returns over the wire
 * (positive/negative factors, alternatives, broken wishes, score impact) — never re-parsed back into
 * the cache by this milestone.
 */
public record ExplanationRecord(
        String id,
        String optimizationRunId,
        String participantProfileId,
        String selectedGroupId,
        String positiveFactorsJson,
        String negativeFactorsJson,
        String alternativeGroupsJson,
        String brokenPreferencesJson,
        String scoreImpactJson,
        String createdAt) {
}
