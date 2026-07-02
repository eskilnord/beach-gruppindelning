package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.SavedPlanResourceUsage;

/**
 * {@code saved_plan_resource_usage} CRUD via {@link JdbcClient} (ADR-004,
 * V6__soft_constraints_locks_saved_plan.sql).
 */
@Repository
public class SavedPlanResourceUsageRepository {

    private final JdbcClient jdbcClient;

    public SavedPlanResourceUsageRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<SavedPlanResourceUsage> findBySavedPlanId(String savedPlanId) {
        return jdbcClient.sql("SELECT * FROM saved_plan_resource_usage WHERE saved_plan_id = :savedPlanId ORDER BY id")
                .param("savedPlanId", savedPlanId)
                .query(SavedPlanResourceUsageRepository::mapRow)
                .list();
    }

    /** Bulk-loads usage rows for several saved plans at once (cross-plan blocking assembly, §6.2) -
     * deterministic order (by saved_plan_id then id) so the resulting fact list is reproducible. */
    public List<SavedPlanResourceUsage> findBySavedPlanIds(List<String> savedPlanIds) {
        if (savedPlanIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("SELECT * FROM saved_plan_resource_usage WHERE saved_plan_id IN (:savedPlanIds) "
                        + "ORDER BY saved_plan_id, id")
                .param("savedPlanIds", savedPlanIds)
                .query(SavedPlanResourceUsageRepository::mapRow)
                .list();
    }

    public SavedPlanResourceUsage insert(SavedPlanResourceUsage usage) {
        jdbcClient.sql("""
                        INSERT INTO saved_plan_resource_usage
                            (id, saved_plan_id, person_id, role, day_of_week, date, start_time, end_time, court_id)
                        VALUES
                            (:id, :savedPlanId, :personId, :role, :dayOfWeek, :date, :startTime, :endTime, :courtId)
                        """)
                .param("id", usage.id())
                .param("savedPlanId", usage.savedPlanId())
                .param("personId", usage.personId())
                .param("role", usage.role())
                .param("dayOfWeek", usage.dayOfWeek())
                .param("date", usage.date())
                .param("startTime", usage.startTime())
                .param("endTime", usage.endTime())
                .param("courtId", usage.courtId())
                .update();
        return usage;
    }

    public void deleteBySavedPlanId(String savedPlanId) {
        jdbcClient.sql("DELETE FROM saved_plan_resource_usage WHERE saved_plan_id = :savedPlanId")
                .param("savedPlanId", savedPlanId)
                .update();
    }

    private static SavedPlanResourceUsage mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SavedPlanResourceUsage(
                rs.getString("id"),
                rs.getString("saved_plan_id"),
                rs.getString("person_id"),
                rs.getString("role"),
                rs.getString("day_of_week"),
                rs.getString("date"),
                rs.getString("start_time"),
                rs.getString("end_time"),
                rs.getString("court_id"));
    }
}
