package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.CoachProfile;

/**
 * {@code coach_profile} access via {@link JdbcClient} (ADR-004). Minimal CRUD only (M3, for the
 * import wizard's "isCoach" mapping target); full Tränarvyn CRUD (availability, level fit, ...)
 * arrives in M5.
 */
@Repository
public class CoachProfileRepository {

    private final JdbcClient jdbcClient;

    public CoachProfileRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<CoachProfile> findByActivityPlanId(String activityPlanId) {
        return jdbcClient.sql("SELECT * FROM coach_profile WHERE activity_plan_id = :activityPlanId ORDER BY id")
                .param("activityPlanId", activityPlanId)
                .query(CoachProfileRepository::mapRow)
                .list();
    }

    public Optional<CoachProfile> findByPersonIdAndActivityPlanId(String personId, String activityPlanId) {
        return jdbcClient.sql(
                        "SELECT * FROM coach_profile WHERE person_id = :personId AND activity_plan_id = :activityPlanId")
                .param("personId", personId)
                .param("activityPlanId", activityPlanId)
                .query(CoachProfileRepository::mapRow)
                .optional();
    }

    public Optional<CoachProfile> findById(String id) {
        return jdbcClient.sql("SELECT * FROM coach_profile WHERE id = :id")
                .param("id", id)
                .query(CoachProfileRepository::mapRow)
                .optional();
    }

    public CoachProfile insert(CoachProfile profile) {
        jdbcClient.sql("""
                        INSERT INTO coach_profile
                            (id, person_id, activity_plan_id, coach_level, can_coach_min_level, can_coach_max_level,
                             max_groups_per_day, max_groups_per_week, can_also_train_as_participant, notes)
                        VALUES
                            (:id, :personId, :activityPlanId, :coachLevel, :canCoachMinLevel, :canCoachMaxLevel,
                             :maxGroupsPerDay, :maxGroupsPerWeek, :canAlsoTrainAsParticipant, :notes)
                        """)
                .param("id", profile.id())
                .param("personId", profile.personId())
                .param("activityPlanId", profile.activityPlanId())
                .param("coachLevel", profile.coachLevel())
                .param("canCoachMinLevel", profile.canCoachMinLevel())
                .param("canCoachMaxLevel", profile.canCoachMaxLevel())
                .param("maxGroupsPerDay", profile.maxGroupsPerDay())
                .param("maxGroupsPerWeek", profile.maxGroupsPerWeek())
                .param("canAlsoTrainAsParticipant", profile.canAlsoTrainAsParticipant() ? 1 : 0)
                .param("notes", profile.notes())
                .update();
        return profile;
    }

    private static CoachProfile mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new CoachProfile(
                rs.getString("id"),
                rs.getString("person_id"),
                rs.getString("activity_plan_id"),
                NullableColumns.nullableDouble(rs, "coach_level"),
                NullableColumns.nullableDouble(rs, "can_coach_min_level"),
                NullableColumns.nullableDouble(rs, "can_coach_max_level"),
                NullableColumns.nullableInt(rs, "max_groups_per_day"),
                NullableColumns.nullableInt(rs, "max_groups_per_week"),
                rs.getInt("can_also_train_as_participant") != 0,
                rs.getString("notes"));
    }
}
