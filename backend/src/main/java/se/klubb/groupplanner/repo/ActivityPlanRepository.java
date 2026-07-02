package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.ActivityPlan;

/**
 * {@code activity_plan} CRUD via {@link JdbcClient} (ADR-004).
 */
@Repository
public class ActivityPlanRepository {

    private final JdbcClient jdbcClient;

    public ActivityPlanRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<ActivityPlan> findBySeasonPlanId(String seasonPlanId) {
        return jdbcClient.sql("SELECT * FROM activity_plan WHERE season_plan_id = :seasonPlanId ORDER BY created_at, id")
                .param("seasonPlanId", seasonPlanId)
                .query(ActivityPlanRepository::mapRow)
                .list();
    }

    public Optional<ActivityPlan> findById(String id) {
        return jdbcClient.sql("SELECT * FROM activity_plan WHERE id = :id")
                .param("id", id)
                .query(ActivityPlanRepository::mapRow)
                .optional();
    }

    public ActivityPlan insert(ActivityPlan plan) {
        jdbcClient.sql("""
                        INSERT INTO activity_plan
                            (id, season_plan_id, name, category, status,
                             default_group_target_size, default_group_min_size, default_group_max_size,
                             default_level_min, created_at, updated_at)
                        VALUES
                            (:id, :seasonPlanId, :name, :category, :status,
                             :defaultGroupTargetSize, :defaultGroupMinSize, :defaultGroupMaxSize,
                             :defaultLevelMin, :createdAt, :updatedAt)
                        """)
                .param("id", plan.id())
                .param("seasonPlanId", plan.seasonPlanId())
                .param("name", plan.name())
                .param("category", plan.category())
                .param("status", plan.status())
                .param("defaultGroupTargetSize", plan.defaultGroupTargetSize())
                .param("defaultGroupMinSize", plan.defaultGroupMinSize())
                .param("defaultGroupMaxSize", plan.defaultGroupMaxSize())
                .param("defaultLevelMin", plan.defaultLevelMin())
                .param("createdAt", plan.createdAt().toString())
                .param("updatedAt", plan.updatedAt().toString())
                .update();
        return plan;
    }

    public ActivityPlan update(ActivityPlan plan) {
        jdbcClient.sql("""
                        UPDATE activity_plan
                        SET name = :name, category = :category, status = :status,
                            default_group_target_size = :defaultGroupTargetSize,
                            default_group_min_size = :defaultGroupMinSize,
                            default_group_max_size = :defaultGroupMaxSize,
                            default_level_min = :defaultLevelMin,
                            updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("id", plan.id())
                .param("name", plan.name())
                .param("category", plan.category())
                .param("status", plan.status())
                .param("defaultGroupTargetSize", plan.defaultGroupTargetSize())
                .param("defaultGroupMinSize", plan.defaultGroupMinSize())
                .param("defaultGroupMaxSize", plan.defaultGroupMaxSize())
                .param("defaultLevelMin", plan.defaultLevelMin())
                .param("updatedAt", plan.updatedAt().toString())
                .update();
        return plan;
    }

    public boolean deleteById(String id) {
        int rows = jdbcClient.sql("DELETE FROM activity_plan WHERE id = :id").param("id", id).update();
        return rows > 0;
    }

    /**
     * Current {@code plan_revision} (M7, docs/design/04-solver.md §11.6) — the "currentRevision" half
     * of every explanation/what-if response's staleness envelope. 0 if the plan does not exist
     * (callers are expected to have already validated existence for a 404).
     */
    public int getPlanRevision(String id) {
        return jdbcClient.sql("SELECT plan_revision FROM activity_plan WHERE id = :id")
                .param("id", id)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    /**
     * Bumps and returns the new {@code plan_revision} — the single chokepoint every M7 "invalidation
     * surface" call site uses (manual moves, lock/unlock, participant/field-value edits, and a solve/
     * greedy run's own writeback — see V7__explainability.sql's header comment and
     * backend/docs/m7-notes.md for why a fresh solve must bump it too). Two statements (UPDATE then
     * SELECT) rather than an SQLite {@code RETURNING} clause, for the same JdbcClient
     * update()-vs-query() simplicity every other repository in this codebase already follows.
     */
    public int bumpRevision(String id) {
        jdbcClient.sql("UPDATE activity_plan SET plan_revision = plan_revision + 1 WHERE id = :id")
                .param("id", id)
                .update();
        return getPlanRevision(id);
    }

    private static ActivityPlan mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ActivityPlan(
                rs.getString("id"),
                rs.getString("season_plan_id"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getString("status"),
                NullableColumns.nullableInt(rs, "default_group_target_size"),
                NullableColumns.nullableInt(rs, "default_group_min_size"),
                NullableColumns.nullableInt(rs, "default_group_max_size"),
                NullableColumns.nullableDouble(rs, "default_level_min"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }
}
