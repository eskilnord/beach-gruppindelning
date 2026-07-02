package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.TrainingBlock;

/**
 * {@code training_block} CRUD via {@link JdbcClient} (ADR-004, spec §6.9/§7.10). Rows are created
 * and reactivated/deactivated by {@code se.klubb.groupplanner.resources.TrainingBlockGenerationService}
 * (never deleted by regeneration — {@code UNIQUE(time_slot_id, court_id)} preserves identity across
 * a court-count change) and individually deactivated by {@code PATCH /api/training-blocks/{id}}
 * (spec §12.3 manual exception).
 */
@Repository
public class TrainingBlockRepository {

    private final JdbcClient jdbcClient;

    public TrainingBlockRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<TrainingBlock> findByTimeSlotId(String timeSlotId) {
        return jdbcClient.sql("SELECT * FROM training_block WHERE time_slot_id = :timeSlotId ORDER BY id")
                .param("timeSlotId", timeSlotId)
                .query(TrainingBlockRepository::mapRow)
                .list();
    }

    public List<TrainingBlock> findByActivityPlanId(String activityPlanId) {
        return jdbcClient.sql("SELECT * FROM training_block WHERE activity_plan_id = :activityPlanId ORDER BY id")
                .param("activityPlanId", activityPlanId)
                .query(TrainingBlockRepository::mapRow)
                .list();
    }

    public int countActiveByActivityPlanId(String activityPlanId) {
        return jdbcClient.sql(
                        "SELECT COUNT(*) FROM training_block WHERE activity_plan_id = :activityPlanId AND active = 1")
                .param("activityPlanId", activityPlanId)
                .query(Integer.class)
                .single();
    }

    /** Active block count per time slot, for a whole plan (capacity per-slot breakdown, spec §12.4). */
    public java.util.Map<String, Integer> countActiveByTimeSlotForPlan(String activityPlanId) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        jdbcClient.sql("""
                        SELECT time_slot_id, COUNT(*) AS cnt FROM training_block
                        WHERE activity_plan_id = :activityPlanId AND active = 1
                        GROUP BY time_slot_id
                        """)
                .param("activityPlanId", activityPlanId)
                .query((rs, rowNum) -> {
                    counts.put(rs.getString("time_slot_id"), rs.getInt("cnt"));
                    return null;
                })
                .list();
        return counts;
    }

    public Optional<TrainingBlock> findByTimeSlotIdAndCourtId(String timeSlotId, String courtId) {
        return jdbcClient.sql("SELECT * FROM training_block WHERE time_slot_id = :timeSlotId AND court_id = :courtId")
                .param("timeSlotId", timeSlotId)
                .param("courtId", courtId)
                .query(TrainingBlockRepository::mapRow)
                .optional();
    }

    public Optional<TrainingBlock> findById(String id) {
        return jdbcClient.sql("SELECT * FROM training_block WHERE id = :id")
                .param("id", id)
                .query(TrainingBlockRepository::mapRow)
                .optional();
    }

    public TrainingBlock insert(TrainingBlock block) {
        jdbcClient.sql("""
                        INSERT INTO training_block
                            (id, time_slot_id, court_id, activity_plan_id, active, locked, deactivation_source)
                        VALUES (:id, :timeSlotId, :courtId, :activityPlanId, :active, :locked, :deactivationSource)
                        """)
                .param("id", block.id())
                .param("timeSlotId", block.timeSlotId())
                .param("courtId", block.courtId())
                .param("activityPlanId", block.activityPlanId())
                .param("active", block.active() ? 1 : 0)
                .param("locked", block.locked() ? 1 : 0)
                .param("deactivationSource", block.deactivationSource())
                .update();
        return block;
    }

    /**
     * Deactivates a block, recording why ({@link TrainingBlock#DEACTIVATION_MANUAL} or
     * {@link TrainingBlock#DEACTIVATION_SHRINK}) so a later regeneration knows whether it may
     * auto-reactivate it (SHRINK: yes; MANUAL §12.3 exception: never).
     */
    public void deactivate(String id, String deactivationSource) {
        jdbcClient.sql("UPDATE training_block SET active = 0, deactivation_source = :deactivationSource WHERE id = :id")
                .param("id", id)
                .param("deactivationSource", deactivationSource)
                .update();
    }

    /** Reactivates a block and clears its {@code deactivation_source} (defined NULL while active). */
    public void reactivate(String id) {
        jdbcClient.sql("UPDATE training_block SET active = 1, deactivation_source = NULL WHERE id = :id")
                .param("id", id)
                .update();
    }

    private static TrainingBlock mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TrainingBlock(
                rs.getString("id"),
                rs.getString("time_slot_id"),
                rs.getString("court_id"),
                rs.getString("activity_plan_id"),
                rs.getInt("active") != 0,
                rs.getInt("locked") != 0,
                rs.getString("deactivation_source"));
    }
}
