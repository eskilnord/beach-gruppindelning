package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.TrainingGroup;

/**
 * {@code training_group} CRUD via {@link JdbcClient} (ADR-004, spec §7.4). Rows are created/updated
 * by {@code se.klubb.groupplanner.solver.assemble.GroupGenerator} (M6a); {@code
 * assignedTrainingBlockId}/{@code locked} are written back after a solve by {@code
 * se.klubb.groupplanner.solver.run.SolveCoordinator}.
 */
@Repository
public class TrainingGroupRepository {

    private final JdbcClient jdbcClient;

    public TrainingGroupRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Deterministic order (M6a determinism rule): by {@code group_order} then {@code id}. */
    public List<TrainingGroup> findByActivityPlanId(String activityPlanId) {
        return jdbcClient.sql("SELECT * FROM training_group WHERE activity_plan_id = :activityPlanId "
                        + "ORDER BY group_order, id")
                .param("activityPlanId", activityPlanId)
                .query(TrainingGroupRepository::mapRow)
                .list();
    }

    public Optional<TrainingGroup> findById(String id) {
        return jdbcClient.sql("SELECT * FROM training_group WHERE id = :id")
                .param("id", id)
                .query(TrainingGroupRepository::mapRow)
                .optional();
    }

    public int countByActivityPlanId(String activityPlanId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM training_group WHERE activity_plan_id = :activityPlanId")
                .param("activityPlanId", activityPlanId)
                .query(Integer.class)
                .single();
    }

    public TrainingGroup insert(TrainingGroup group) {
        jdbcClient.sql("""
                        INSERT INTO training_group
                            (id, activity_plan_id, name, group_order, category, min_size, target_size, max_size,
                             required_coach_count, level_min, level_max, assigned_training_block_id, locked)
                        VALUES
                            (:id, :activityPlanId, :name, :groupOrder, :category, :minSize, :targetSize, :maxSize,
                             :requiredCoachCount, :levelMin, :levelMax, :assignedTrainingBlockId, :locked)
                        """)
                .param("id", group.id())
                .param("activityPlanId", group.activityPlanId())
                .param("name", group.name())
                .param("groupOrder", group.groupOrder())
                .param("category", group.category())
                .param("minSize", group.minSize())
                .param("targetSize", group.targetSize())
                .param("maxSize", group.maxSize())
                .param("requiredCoachCount", group.requiredCoachCount())
                .param("levelMin", group.levelMin())
                .param("levelMax", group.levelMax())
                .param("assignedTrainingBlockId", group.assignedTrainingBlockId())
                .param("locked", group.locked() ? 1 : 0)
                .update();
        return group;
    }

    /** Full-row replace, used by {@code GroupGenerator} re-generation and post-solve writeback. */
    public TrainingGroup update(TrainingGroup group) {
        jdbcClient.sql("""
                        UPDATE training_group
                        SET name = :name, group_order = :groupOrder, category = :category, min_size = :minSize,
                            target_size = :targetSize, max_size = :maxSize, required_coach_count = :requiredCoachCount,
                            level_min = :levelMin, level_max = :levelMax,
                            assigned_training_block_id = :assignedTrainingBlockId, locked = :locked
                        WHERE id = :id
                        """)
                .param("id", group.id())
                .param("name", group.name())
                .param("groupOrder", group.groupOrder())
                .param("category", group.category())
                .param("minSize", group.minSize())
                .param("targetSize", group.targetSize())
                .param("maxSize", group.maxSize())
                .param("requiredCoachCount", group.requiredCoachCount())
                .param("levelMin", group.levelMin())
                .param("levelMax", group.levelMax())
                .param("assignedTrainingBlockId", group.assignedTrainingBlockId())
                .param("locked", group.locked() ? 1 : 0)
                .update();
        return group;
    }

    /** Post-solve writeback of just the resolved training block (SolveCoordinator). Scoped to
     * {@code locked = 0} for consistency with the player/coach writebacks (review fix 7): a locked
     * group's schedule was {@code @PlanningPin}'d so the solver never changed it anyway, and the
     * write side defensively refuses to touch the row regardless. */
    public void updateAssignedTrainingBlock(String groupId, String trainingBlockId) {
        jdbcClient.sql("UPDATE training_group SET assigned_training_block_id = :trainingBlockId WHERE id = :id AND locked = 0")
                .param("id", groupId)
                .param("trainingBlockId", trainingBlockId)
                .update();
    }

    public boolean deleteById(String id) {
        int rows = jdbcClient.sql("DELETE FROM training_group WHERE id = :id").param("id", id).update();
        return rows > 0;
    }

    /** §15.2 "Lås gruppens tid/bana" (spec §15.2) — pins {@link
     * se.klubb.groupplanner.solver.domain.GroupSchedule#isPinned()} via {@code
     * training_group.locked} (unlike the player/coach writeback methods, this one is deliberately
     * NOT scoped to {@code locked = 0}: it is the very method that SETS the lock). */
    public void lockToBlock(String groupId, String trainingBlockId) {
        jdbcClient.sql("UPDATE training_group SET assigned_training_block_id = :trainingBlockId, locked = 1 WHERE id = :id")
                .param("id", groupId)
                .param("trainingBlockId", trainingBlockId)
                .update();
    }

    /** Unlocks a group's schedule (keeps its current training block; a future solve is free to
     * move it). */
    public void unlockBlock(String groupId) {
        jdbcClient.sql("UPDATE training_group SET locked = 0 WHERE id = :id")
                .param("id", groupId)
                .update();
    }

    private static TrainingGroup mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TrainingGroup(
                rs.getString("id"),
                rs.getString("activity_plan_id"),
                rs.getString("name"),
                NullableColumns.nullableInt(rs, "group_order"),
                rs.getString("category"),
                NullableColumns.nullableInt(rs, "min_size"),
                NullableColumns.nullableInt(rs, "target_size"),
                NullableColumns.nullableInt(rs, "max_size"),
                rs.getInt("required_coach_count"),
                NullableColumns.nullableDouble(rs, "level_min"),
                NullableColumns.nullableDouble(rs, "level_max"),
                rs.getString("assigned_training_block_id"),
                rs.getInt("locked") != 0);
    }
}
