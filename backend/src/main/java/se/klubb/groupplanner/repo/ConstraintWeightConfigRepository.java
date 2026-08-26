package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.ConstraintWeightConfig;
import se.klubb.groupplanner.util.Uuid7;

/**
 * {@code constraint_weight_config} access via {@link JdbcClient} (ADR-004) — per-plan overrides of
 * {@code constraint_definition}'s defaults (spec §7.16, §9.4). M4: {@code GET|PUT
 * /api/plans/{planId}/constraint-weights} (see {@code se.klubb.groupplanner.fields
 * .ConstraintWeightService} for the default-merge logic).
 */
@Repository
public class ConstraintWeightConfigRepository {

    private final JdbcClient jdbcClient;

    public ConstraintWeightConfigRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<ConstraintWeightConfig> findByActivityPlanId(String activityPlanId) {
        return jdbcClient.sql("SELECT * FROM constraint_weight_config WHERE activity_plan_id = :activityPlanId")
                .param("activityPlanId", activityPlanId)
                .query(ConstraintWeightConfigRepository::mapRow)
                .list();
    }

    public Optional<ConstraintWeightConfig> findByActivityPlanIdAndKey(String activityPlanId, String constraintKey) {
        return jdbcClient.sql(
                        "SELECT * FROM constraint_weight_config WHERE activity_plan_id = :activityPlanId "
                                + "AND constraint_key = :constraintKey")
                .param("activityPlanId", activityPlanId)
                .param("constraintKey", constraintKey)
                .query(ConstraintWeightConfigRepository::mapRow)
                .optional();
    }

    /**
     * Inserts an override, or replaces the existing one for the same {@code (activityPlanId,
     * constraintKey)} pair (the table's UNIQUE constraint) — callers always pass a fully-resolved
     * row (see {@code ConstraintWeightService}, which merges the request onto the
     * currently-effective values before calling this), so there is no partial-update path here.
     * {@code updated_at} (v0.6.0 milestone B7, V14) is stamped with {@link Instant#now()} on every
     * insert/update, same pattern as {@code AppSettingsRepository#upsert}.
     */
    public ConstraintWeightConfig upsert(String activityPlanId, String constraintKey, String hardOrSoft, int weight, boolean enabled) {
        Optional<String> existingId = jdbcClient.sql(
                        "SELECT id FROM constraint_weight_config WHERE activity_plan_id = :activityPlanId "
                                + "AND constraint_key = :constraintKey")
                .param("activityPlanId", activityPlanId)
                .param("constraintKey", constraintKey)
                .query(String.class)
                .optional();
        String id = existingId.orElseGet(Uuid7::generate);
        String updatedAt = Instant.now().toString();

        jdbcClient.sql("""
                        INSERT INTO constraint_weight_config (id, activity_plan_id, constraint_key, hard_or_soft, weight, enabled, updated_at)
                        VALUES (:id, :activityPlanId, :constraintKey, :hardOrSoft, :weight, :enabled, :updatedAt)
                        ON CONFLICT (activity_plan_id, constraint_key)
                        DO UPDATE SET hard_or_soft = excluded.hard_or_soft, weight = excluded.weight, enabled = excluded.enabled,
                            updated_at = excluded.updated_at
                        """)
                .param("id", id)
                .param("activityPlanId", activityPlanId)
                .param("constraintKey", constraintKey)
                .param("hardOrSoft", hardOrSoft)
                .param("weight", weight)
                .param("enabled", enabled ? 1 : 0)
                .param("updatedAt", updatedAt)
                .update();
        return new ConstraintWeightConfig(id, activityPlanId, constraintKey, hardOrSoft, weight, enabled, updatedAt);
    }

    private static ConstraintWeightConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ConstraintWeightConfig(
                rs.getString("id"),
                rs.getString("activity_plan_id"),
                rs.getString("constraint_key"),
                rs.getString("hard_or_soft"),
                rs.getInt("weight"),
                rs.getInt("enabled") != 0,
                rs.getString("updated_at"));
    }
}
