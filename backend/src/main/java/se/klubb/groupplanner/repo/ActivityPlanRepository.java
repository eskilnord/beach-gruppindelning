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
                             created_at, updated_at)
                        VALUES
                            (:id, :seasonPlanId, :name, :category, :status,
                             :defaultGroupTargetSize, :defaultGroupMinSize, :defaultGroupMaxSize,
                             :createdAt, :updatedAt)
                        """)
                .param("id", plan.id())
                .param("seasonPlanId", plan.seasonPlanId())
                .param("name", plan.name())
                .param("category", plan.category())
                .param("status", plan.status())
                .param("defaultGroupTargetSize", plan.defaultGroupTargetSize())
                .param("defaultGroupMinSize", plan.defaultGroupMinSize())
                .param("defaultGroupMaxSize", plan.defaultGroupMaxSize())
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
                .param("updatedAt", plan.updatedAt().toString())
                .update();
        return plan;
    }

    public boolean deleteById(String id) {
        int rows = jdbcClient.sql("DELETE FROM activity_plan WHERE id = :id").param("id", id).update();
        return rows > 0;
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
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }
}
