package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.PlayerAssignment;
import se.klubb.groupplanner.util.Uuid7;

/**
 * {@code player_assignment} access via {@link JdbcClient} (ADR-004). Minimal CRUD only (M3, so the
 * import wizard can record "imported, awaiting placement" - docs/design/02-product-data-ui.md §2
 * step 8: "create player_assignment(source=imported, group_id=NULL)"); full assignment/move/lock
 * CRUD arrives with the solver milestones.
 */
@Repository
public class PlayerAssignmentRepository {

    private final JdbcClient jdbcClient;

    public PlayerAssignmentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<PlayerAssignment> findByParticipantProfileId(String participantProfileId) {
        return jdbcClient.sql("SELECT * FROM player_assignment WHERE participant_profile_id = :participantProfileId")
                .param("participantProfileId", participantProfileId)
                .query(PlayerAssignmentRepository::mapRow)
                .optional();
    }

    public List<PlayerAssignment> findByGroupId(String groupId) {
        return jdbcClient.sql("SELECT * FROM player_assignment WHERE group_id = :groupId")
                .param("groupId", groupId)
                .query(PlayerAssignmentRepository::mapRow)
                .list();
    }

    /** Every assignment for participants in one plan (join through participant_profile), sorted by
     * the stable participant_profile id (M6a determinism rule). */
    public List<PlayerAssignment> findByActivityPlanId(String activityPlanId) {
        return jdbcClient.sql("""
                        SELECT pa.* FROM player_assignment pa
                        JOIN participant_profile pp ON pp.id = pa.participant_profile_id
                        WHERE pp.activity_plan_id = :activityPlanId
                        ORDER BY pp.id
                        """)
                .param("activityPlanId", activityPlanId)
                .query(PlayerAssignmentRepository::mapRow)
                .list();
    }

    /** Post-solve writeback (se.klubb.groupplanner.solver.run.SolveCoordinator): only touches
     * unlocked rows' group/source — a locked row's group is left exactly as the solver's
     * {@code @PlanningPin} guaranteed it stayed (defensive: the solver never moves a pinned entity
     * anyway, but this repository never trusts that from the write side either). */
    public void updateGroupAndSource(String participantProfileId, String groupId, String source) {
        jdbcClient.sql("""
                        UPDATE player_assignment SET group_id = :groupId, source = :source
                        WHERE participant_profile_id = :participantProfileId AND locked = 0
                        """)
                .param("participantProfileId", participantProfileId)
                .param("groupId", groupId)
                .param("source", source)
                .update();
    }

    /**
     * Creates an unassigned {@code source=imported} row for a participant profile if one doesn't
     * already exist ({@code participant_profile_id} is UNIQUE) - idempotent, so re-running an
     * import commit for the same plan/rows never fails on a constraint violation.
     */
    public PlayerAssignment insertImportedIfAbsent(String participantProfileId) {
        Optional<PlayerAssignment> existing = findByParticipantProfileId(participantProfileId);
        if (existing.isPresent()) {
            return existing.get();
        }
        PlayerAssignment assignment = new PlayerAssignment(
                Uuid7.generate(), participantProfileId, null, false, PlayerAssignment.SOURCE_IMPORTED);
        jdbcClient.sql("""
                        INSERT INTO player_assignment (id, participant_profile_id, group_id, locked, source)
                        VALUES (:id, :participantProfileId, :groupId, :locked, :source)
                        """)
                .param("id", assignment.id())
                .param("participantProfileId", assignment.participantProfileId())
                .param("groupId", assignment.groupId())
                .param("locked", assignment.locked() ? 1 : 0)
                .param("source", assignment.source())
                .update();
        return assignment;
    }

    /**
     * §15.1 "Lås spelare" (spec §15.1) — locks a participant to a group, creating the {@code
     * player_assignment} row if none exists yet (a participant may never have been touched by the
     * solver/import before being explicitly locked). {@code source} becomes {@link
     * PlayerAssignment#SOURCE_LOCKED}.
     */
    public void lockToGroup(String participantProfileId, String groupId) {
        Optional<PlayerAssignment> existing = findByParticipantProfileId(participantProfileId);
        if (existing.isEmpty()) {
            jdbcClient.sql("""
                            INSERT INTO player_assignment (id, participant_profile_id, group_id, locked, source)
                            VALUES (:id, :participantProfileId, :groupId, 1, :source)
                            """)
                    .param("id", Uuid7.generate())
                    .param("participantProfileId", participantProfileId)
                    .param("groupId", groupId)
                    .param("source", PlayerAssignment.SOURCE_LOCKED)
                    .update();
            return;
        }
        jdbcClient.sql("""
                        UPDATE player_assignment SET group_id = :groupId, locked = 1, source = :source
                        WHERE participant_profile_id = :participantProfileId
                        """)
                .param("participantProfileId", participantProfileId)
                .param("groupId", groupId)
                .param("source", PlayerAssignment.SOURCE_LOCKED)
                .update();
    }

    /** Unlocks a participant's assignment (keeps whatever group it currently points at, if any —
     * only the {@code locked} flag flips, so the next solve is free to move it). */
    public void unlock(String participantProfileId) {
        jdbcClient.sql("UPDATE player_assignment SET locked = 0 WHERE participant_profile_id = :participantProfileId")
                .param("participantProfileId", participantProfileId)
                .update();
    }

    private static PlayerAssignment mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new PlayerAssignment(
                rs.getString("id"),
                rs.getString("participant_profile_id"),
                rs.getString("group_id"),
                rs.getInt("locked") != 0,
                rs.getString("source"));
    }
}
