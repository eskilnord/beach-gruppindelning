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

    private static PlayerAssignment mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new PlayerAssignment(
                rs.getString("id"),
                rs.getString("participant_profile_id"),
                rs.getString("group_id"),
                rs.getInt("locked") != 0,
                rs.getString("source"));
    }
}
