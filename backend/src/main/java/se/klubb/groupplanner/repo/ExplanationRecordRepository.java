package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.ExplanationRecord;
import se.klubb.groupplanner.util.Uuid7;

/**
 * {@code explanation_record} access via {@link JdbcClient} (ADR-004, V7__explainability.sql). Write-
 * through persistence log only — see {@link ExplanationRecord}'s javadoc for why the serving path
 * never reads back through this repository.
 */
@Repository
public class ExplanationRecordRepository {

    private final JdbcClient jdbcClient;

    public ExplanationRecordRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Insert-or-replace for the same {@code (optimizationRunId, participantProfileId)} pair (the
     * table's UNIQUE constraint) — a re-computed explanation (e.g. after cache eviction) simply
     * overwrites the previous audit row rather than accumulating duplicates. */
    public void upsert(
            String optimizationRunId,
            String participantProfileId,
            String selectedGroupId,
            String positiveFactorsJson,
            String negativeFactorsJson,
            String alternativeGroupsJson,
            String brokenPreferencesJson,
            String scoreImpactJson) {
        String id = jdbcClient.sql(
                        "SELECT id FROM explanation_record WHERE optimization_run_id = :runId AND participant_profile_id = :pid")
                .param("runId", optimizationRunId)
                .param("pid", participantProfileId)
                .query(String.class)
                .optional()
                .orElseGet(Uuid7::generate);
        jdbcClient.sql("""
                        INSERT INTO explanation_record
                            (id, optimization_run_id, participant_profile_id, selected_group_id, positive_factors_json,
                             negative_factors_json, alternative_groups_json, broken_preferences_json, score_impact_json,
                             created_at)
                        VALUES
                            (:id, :runId, :pid, :selectedGroupId, :positiveFactorsJson, :negativeFactorsJson,
                             :alternativeGroupsJson, :brokenPreferencesJson, :scoreImpactJson, :createdAt)
                        ON CONFLICT (optimization_run_id, participant_profile_id)
                        DO UPDATE SET selected_group_id = excluded.selected_group_id,
                            positive_factors_json = excluded.positive_factors_json,
                            negative_factors_json = excluded.negative_factors_json,
                            alternative_groups_json = excluded.alternative_groups_json,
                            broken_preferences_json = excluded.broken_preferences_json,
                            score_impact_json = excluded.score_impact_json,
                            created_at = excluded.created_at
                        """)
                .param("id", id)
                .param("runId", optimizationRunId)
                .param("pid", participantProfileId)
                .param("selectedGroupId", selectedGroupId)
                .param("positiveFactorsJson", positiveFactorsJson)
                .param("negativeFactorsJson", negativeFactorsJson)
                .param("alternativeGroupsJson", alternativeGroupsJson)
                .param("brokenPreferencesJson", brokenPreferencesJson)
                .param("scoreImpactJson", scoreImpactJson)
                .param("createdAt", Instant.now().toString())
                .update();
    }

    public Optional<ExplanationRecord> findByRunAndParticipant(String optimizationRunId, String participantProfileId) {
        return jdbcClient.sql(
                        "SELECT * FROM explanation_record WHERE optimization_run_id = :runId AND participant_profile_id = :pid")
                .param("runId", optimizationRunId)
                .param("pid", participantProfileId)
                .query(ExplanationRecordRepository::mapRow)
                .optional();
    }

    private static ExplanationRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ExplanationRecord(
                rs.getString("id"),
                rs.getString("optimization_run_id"),
                rs.getString("participant_profile_id"),
                rs.getString("selected_group_id"),
                rs.getString("positive_factors_json"),
                rs.getString("negative_factors_json"),
                rs.getString("alternative_groups_json"),
                rs.getString("broken_preferences_json"),
                rs.getString("score_impact_json"),
                rs.getString("created_at"));
    }
}
