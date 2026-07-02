package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.SavedPlan;

/**
 * {@code saved_plan} CRUD via {@link JdbcClient} (ADR-004, V6__soft_constraints_locks_saved_plan.sql).
 */
@Repository
public class SavedPlanRepository {

    private final JdbcClient jdbcClient;

    public SavedPlanRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<SavedPlan> findByActivityPlanId(String activityPlanId) {
        return jdbcClient.sql("SELECT * FROM saved_plan WHERE activity_plan_id = :activityPlanId ORDER BY created_at, id")
                .param("activityPlanId", activityPlanId)
                .query(SavedPlanRepository::mapRow)
                .list();
    }

    public Optional<SavedPlan> findById(String id) {
        return jdbcClient.sql("SELECT * FROM saved_plan WHERE id = :id")
                .param("id", id)
                .query(SavedPlanRepository::mapRow)
                .optional();
    }

    /**
     * Every LOCKED saved plan in the same season as {@code seasonPlanId}, excluding {@code
     * excludeActivityPlanId} (the plan currently being assembled/solved) - the exact source set
     * design §6.2 specifies for cross-plan blocking (§10.24). Deterministic order (by id) for
     * consistency with every other solver-input list.
     */
    public List<SavedPlan> findLockedInSeasonExcludingPlan(String seasonPlanId, String excludeActivityPlanId) {
        return jdbcClient.sql("""
                        SELECT sp.* FROM saved_plan sp
                        JOIN activity_plan ap ON ap.id = sp.activity_plan_id
                        WHERE ap.season_plan_id = :seasonPlanId
                          AND sp.activity_plan_id != :excludeActivityPlanId
                          AND sp.status = :lockedStatus
                        ORDER BY sp.id
                        """)
                .param("seasonPlanId", seasonPlanId)
                .param("excludeActivityPlanId", excludeActivityPlanId)
                .param("lockedStatus", SavedPlan.STATUS_LOCKED)
                .query(SavedPlanRepository::mapRow)
                .list();
    }

    public SavedPlan insert(SavedPlan plan) {
        jdbcClient.sql("""
                        INSERT INTO saved_plan
                            (id, activity_plan_id, name, status, snapshot_json, score, optimization_run_id,
                             created_at, updated_at)
                        VALUES
                            (:id, :activityPlanId, :name, :status, :snapshotJson, :score, :optimizationRunId,
                             :createdAt, :updatedAt)
                        """)
                .param("id", plan.id())
                .param("activityPlanId", plan.activityPlanId())
                .param("name", plan.name())
                .param("status", plan.status())
                .param("snapshotJson", plan.snapshotJson())
                .param("score", plan.score())
                .param("optimizationRunId", plan.optimizationRunId())
                .param("createdAt", plan.createdAt())
                .param("updatedAt", plan.updatedAt())
                .update();
        return plan;
    }

    public boolean deleteById(String id) {
        int rows = jdbcClient.sql("DELETE FROM saved_plan WHERE id = :id").param("id", id).update();
        return rows > 0;
    }

    private static SavedPlan mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SavedPlan(
                rs.getString("id"),
                rs.getString("activity_plan_id"),
                rs.getString("name"),
                rs.getString("status"),
                rs.getString("snapshot_json"),
                rs.getString("score"),
                rs.getString("optimization_run_id"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
