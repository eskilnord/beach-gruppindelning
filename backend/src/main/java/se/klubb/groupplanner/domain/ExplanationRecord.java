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
 * (positive/negative factors, alternatives, broken wishes, score impact, and — since v0.3.0 WI-5's
 * V9 migration — the second-order "via a coach" indirect factors) — never re-parsed back into
 * the cache by this milestone.
 *
 * <p>{@code indirectFactorsJson} is null for rows written before V9 and a JSON array (possibly
 * empty) afterwards; the broken-wish {@code coachBindingSv} annotation lives INSIDE {@code
 * brokenPreferencesJson}'s serialized {@code BrokenWishView} records, so it needed no column.
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
        String indirectFactorsJson,
        String createdAt) {
}
