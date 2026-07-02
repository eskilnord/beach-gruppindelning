package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.CoachAssignment;
import se.klubb.groupplanner.util.Uuid7;

/**
 * {@code coach_assignment} CRUD via {@link JdbcClient} (ADR-004, spec §7.12). Written back after a
 * solve by {@code se.klubb.groupplanner.solver.run.SolveCoordinator} (source={@code solver}), or
 * left untouched for a locked/pinned {@code CoachSlot}.
 */
@Repository
public class CoachAssignmentRepository {

    private final JdbcClient jdbcClient;

    public CoachAssignmentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<CoachAssignment> findByGroupId(String groupId) {
        return jdbcClient.sql("SELECT * FROM coach_assignment WHERE group_id = :groupId ORDER BY id")
                .param("groupId", groupId)
                .query(CoachAssignmentRepository::mapRow)
                .list();
    }

    /** Every assignment for groups in one plan (join through training_group), deterministic order. */
    public List<CoachAssignment> findByActivityPlanId(String activityPlanId) {
        return jdbcClient.sql("""
                        SELECT ca.* FROM coach_assignment ca
                        JOIN training_group tg ON tg.id = ca.group_id
                        WHERE tg.activity_plan_id = :activityPlanId
                        ORDER BY tg.group_order, ca.id
                        """)
                .param("activityPlanId", activityPlanId)
                .query(CoachAssignmentRepository::mapRow)
                .list();
    }

    public CoachAssignment insert(String coachProfileId, String groupId, boolean locked, String source) {
        CoachAssignment assignment = new CoachAssignment(Uuid7.generate(), coachProfileId, groupId, locked, source);
        jdbcClient.sql("""
                        INSERT INTO coach_assignment (id, coach_profile_id, group_id, locked, source)
                        VALUES (:id, :coachProfileId, :groupId, :locked, :source)
                        """)
                .param("id", assignment.id())
                .param("coachProfileId", assignment.coachProfileId())
                .param("groupId", assignment.groupId())
                .param("locked", assignment.locked() ? 1 : 0)
                .param("source", assignment.source())
                .update();
        return assignment;
    }

    /** Clears every assignment for one group — used before writing the solver's resolved coach set
     * back (full-replace semantics, mirroring {@code CoachTimeSlotRepository}'s availability PUT). */
    public void deleteByGroupId(String groupId) {
        jdbcClient.sql("DELETE FROM coach_assignment WHERE group_id = :groupId")
                .param("groupId", groupId)
                .update();
    }

    /** Clears only the UNLOCKED assignments for one group (se.klubb.groupplanner.solver.run
     * .SolveCoordinator post-solve writeback): a locked row is a {@code @PlanningPin}'d CoachSlot
     * the solver never touched, so its row must survive untouched; every unlocked row is about to be
     * replaced by the solver's resolved result. */
    public void deleteUnlockedByGroupId(String groupId) {
        jdbcClient.sql("DELETE FROM coach_assignment WHERE group_id = :groupId AND locked = 0")
                .param("groupId", groupId)
                .update();
    }

    public boolean deleteById(String id) {
        int rows = jdbcClient.sql("DELETE FROM coach_assignment WHERE id = :id").param("id", id).update();
        return rows > 0;
    }

    public Optional<CoachAssignment> findByGroupIdAndCoachProfileId(String groupId, String coachProfileId) {
        return jdbcClient.sql("SELECT * FROM coach_assignment WHERE group_id = :groupId AND coach_profile_id = :coachProfileId")
                .param("groupId", groupId)
                .param("coachProfileId", coachProfileId)
                .query(CoachAssignmentRepository::mapRow)
                .optional();
    }

    /**
     * §15.3 "Lås tränare" (spec §15.3) — locks a coach to a group, creating the {@code
     * coach_assignment} row if none exists yet. Per design §5 ("pins the lowest-index free slot,
     * deterministically"): {@code SolverInputAssembler} maps {@code CoachSlot} slot index to the
     * Nth {@code coach_assignment} row (by id order) for a group, so a brand-new locked row for a
     * group with no prior coach assignment naturally becomes slot 0 — the lowest-index slot — with
     * zero extra bookkeeping. An explicit numbered {@code slotIndex} beyond that is accepted at the
     * API layer for validation only (must be &lt; the group's requiredCoachCount); the schema has no
     * dedicated slot-index column (see backend/docs/m6b-notes.md for the full reasoning).
     */
    public void lockToGroup(String coachProfileId, String groupId) {
        Optional<CoachAssignment> existing = findByGroupIdAndCoachProfileId(groupId, coachProfileId);
        if (existing.isEmpty()) {
            insert(coachProfileId, groupId, true, CoachAssignment.SOURCE_LOCKED);
            return;
        }
        jdbcClient.sql("UPDATE coach_assignment SET locked = 1, source = :source WHERE id = :id")
                .param("id", existing.get().id())
                .param("source", CoachAssignment.SOURCE_LOCKED)
                .update();
    }

    /** Unlocks a specific coach's assignment to a group (keeps the assignment; a future solve is
     * free to move it). No-op if no such assignment exists. */
    public void unlockForCoachAndGroup(String coachProfileId, String groupId) {
        jdbcClient.sql("UPDATE coach_assignment SET locked = 0 WHERE group_id = :groupId AND coach_profile_id = :coachProfileId")
                .param("groupId", groupId)
                .param("coachProfileId", coachProfileId)
                .update();
    }

    private static CoachAssignment mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new CoachAssignment(
                rs.getString("id"),
                rs.getString("coach_profile_id"),
                rs.getString("group_id"),
                rs.getInt("locked") != 0,
                rs.getString("source"));
    }
}
