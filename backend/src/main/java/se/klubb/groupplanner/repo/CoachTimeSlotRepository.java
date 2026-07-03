package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.CoachTimeSlot;

/**
 * {@code coach_time_slot} CRUD via {@link JdbcClient} (ADR-004, spec §7.3 tri-state availability).
 */
@Repository
public class CoachTimeSlotRepository {

    private final JdbcClient jdbcClient;

    public CoachTimeSlotRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<CoachTimeSlot> findByCoachProfileId(String coachProfileId) {
        return jdbcClient.sql("SELECT * FROM coach_time_slot WHERE coach_profile_id = :coachProfileId ORDER BY id")
                .param("coachProfileId", coachProfileId)
                .query(CoachTimeSlotRepository::mapRow)
                .list();
    }

    public List<CoachTimeSlot> findByTimeSlotId(String timeSlotId) {
        return jdbcClient.sql("SELECT * FROM coach_time_slot WHERE time_slot_id = :timeSlotId ORDER BY id")
                .param("timeSlotId", timeSlotId)
                .query(CoachTimeSlotRepository::mapRow)
                .list();
    }

    /**
     * Coaches (by distinct {@code coach_profile_id}) with one specific {@code kind} at every time
     * slot of an activity plan, keyed by time_slot_id -&gt; count. Used by the capacity per-slot
     * breakdown (spec §12.4): "not counted as UNAVAILABLE" is a coach's availability at a slot for
     * capacity/HARD purposes (neutral/unlisted = available — docs/design/04-solver.md), and
     * PREFERRED is surfaced separately. Since WI-B the solver's SOFT layer additionally nudges away
     * from neutral/unlisted ("Okänd") slots (see {@code CoachFact.availableTimeSlotIds}/{@code
     * coachUnknownTimeSlot}), but that does not change this capacity view's semantics.
     */
    public java.util.Map<String, Integer> countKindByTimeSlotForPlan(String activityPlanId, String kind) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        jdbcClient.sql("""
                        SELECT cts.time_slot_id AS time_slot_id, COUNT(DISTINCT cts.coach_profile_id) AS cnt
                        FROM coach_time_slot cts
                        JOIN coach_profile cp ON cp.id = cts.coach_profile_id
                        WHERE cp.activity_plan_id = :activityPlanId AND cts.kind = :kind
                        GROUP BY cts.time_slot_id
                        """)
                .param("activityPlanId", activityPlanId)
                .param("kind", kind)
                .query((rs, rowNum) -> {
                    counts.put(rs.getString("time_slot_id"), rs.getInt("cnt"));
                    return null;
                })
                .list();
        return counts;
    }

    public CoachTimeSlot insert(CoachTimeSlot entry) {
        jdbcClient.sql("""
                        INSERT INTO coach_time_slot (id, coach_profile_id, time_slot_id, kind)
                        VALUES (:id, :coachProfileId, :timeSlotId, :kind)
                        """)
                .param("id", entry.id())
                .param("coachProfileId", entry.coachProfileId())
                .param("timeSlotId", entry.timeSlotId())
                .param("kind", entry.kind())
                .update();
        return entry;
    }

    /** Replaces the full availability set for one coach (spec §7.14-style "set" PUT semantics). */
    public void deleteByCoachProfileId(String coachProfileId) {
        jdbcClient.sql("DELETE FROM coach_time_slot WHERE coach_profile_id = :coachProfileId")
                .param("coachProfileId", coachProfileId)
                .update();
    }

    private static CoachTimeSlot mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new CoachTimeSlot(
                rs.getString("id"),
                rs.getString("coach_profile_id"),
                rs.getString("time_slot_id"),
                rs.getString("kind"));
    }
}
