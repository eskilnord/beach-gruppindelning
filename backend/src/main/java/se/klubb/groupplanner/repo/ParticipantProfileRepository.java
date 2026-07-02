package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.ParticipantProfile;

/**
 * {@code participant_profile} CRUD via {@link JdbcClient} (ADR-004).
 *
 * <p>Never selects/exposes {@code imported_comment}/{@code internal_note} for anything other than
 * this row's own read/write path — callers building solver input or snapshots must not reuse this
 * repository's mapping for those purposes without re-reading the confidentiality rules in
 * CLAUDE.md.
 */
@Repository
public class ParticipantProfileRepository {

    private final JdbcClient jdbcClient;

    public ParticipantProfileRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<ParticipantProfile> findByActivityPlanId(String activityPlanId) {
        return jdbcClient.sql("SELECT * FROM participant_profile WHERE activity_plan_id = :activityPlanId ORDER BY id")
                .param("activityPlanId", activityPlanId)
                .query(ParticipantProfileRepository::mapRow)
                .list();
    }

    /** Used by the import wizard commit (M3) to detect a re-import and update rather than duplicate. */
    public Optional<ParticipantProfile> findByPersonIdAndActivityPlanId(String personId, String activityPlanId) {
        return jdbcClient.sql(
                        "SELECT * FROM participant_profile WHERE person_id = :personId AND activity_plan_id = :activityPlanId")
                .param("personId", personId)
                .param("activityPlanId", activityPlanId)
                .query(ParticipantProfileRepository::mapRow)
                .optional();
    }

    public Optional<ParticipantProfile> findById(String id) {
        return jdbcClient.sql("SELECT * FROM participant_profile WHERE id = :id")
                .param("id", id)
                .query(ParticipantProfileRepository::mapRow)
                .optional();
    }

    public ParticipantProfile insert(ParticipantProfile profile) {
        jdbcClient.sql("""
                        INSERT INTO participant_profile
                            (id, person_id, activity_plan_id, ranking_points, ranking_source,
                             previous_group_name, previous_group_level, estimated_level, level_confidence,
                             manual_level_score, imported_comment, internal_note, manual_review_flag, waitlisted)
                        VALUES
                            (:id, :personId, :activityPlanId, :rankingPoints, :rankingSource,
                             :previousGroupName, :previousGroupLevel, :estimatedLevel, :levelConfidence,
                             :manualLevelScore, :importedComment, :internalNote, :manualReviewFlag, :waitlisted)
                        """)
                .param("id", profile.id())
                .param("personId", profile.personId())
                .param("activityPlanId", profile.activityPlanId())
                .param("rankingPoints", profile.rankingPoints())
                .param("rankingSource", profile.rankingSource())
                .param("previousGroupName", profile.previousGroupName())
                .param("previousGroupLevel", profile.previousGroupLevel())
                .param("estimatedLevel", profile.estimatedLevel())
                .param("levelConfidence", profile.levelConfidence())
                .param("manualLevelScore", profile.manualLevelScore())
                .param("importedComment", profile.importedComment())
                .param("internalNote", profile.internalNote())
                .param("manualReviewFlag", profile.manualReviewFlag() ? 1 : 0)
                .param("waitlisted", profile.waitlisted() ? 1 : 0)
                .update();
        return profile;
    }

    public ParticipantProfile update(ParticipantProfile profile) {
        jdbcClient.sql("""
                        UPDATE participant_profile
                        SET ranking_points = :rankingPoints, ranking_source = :rankingSource,
                            previous_group_name = :previousGroupName, previous_group_level = :previousGroupLevel,
                            estimated_level = :estimatedLevel, level_confidence = :levelConfidence,
                            manual_level_score = :manualLevelScore, imported_comment = :importedComment,
                            internal_note = :internalNote, manual_review_flag = :manualReviewFlag,
                            waitlisted = :waitlisted
                        WHERE id = :id
                        """)
                .param("id", profile.id())
                .param("rankingPoints", profile.rankingPoints())
                .param("rankingSource", profile.rankingSource())
                .param("previousGroupName", profile.previousGroupName())
                .param("previousGroupLevel", profile.previousGroupLevel())
                .param("estimatedLevel", profile.estimatedLevel())
                .param("levelConfidence", profile.levelConfidence())
                .param("manualLevelScore", profile.manualLevelScore())
                .param("importedComment", profile.importedComment())
                .param("internalNote", profile.internalNote())
                .param("manualReviewFlag", profile.manualReviewFlag() ? 1 : 0)
                .param("waitlisted", profile.waitlisted() ? 1 : 0)
                .update();
        return profile;
    }

    public boolean deleteById(String id) {
        int rows = jdbcClient.sql("DELETE FROM participant_profile WHERE id = :id").param("id", id).update();
        return rows > 0;
    }

    /**
     * Nulls {@code imported_comment}/{@code internal_note} for one participant (spec §21.2 "ska
     * kunna raderas/anonymiseras" — {@code DELETE /api/plans/{planId}/participants/{pid}/comments}).
     */
    public boolean clearComments(String id) {
        int rows = jdbcClient.sql(
                        "UPDATE participant_profile SET imported_comment = NULL, internal_note = NULL WHERE id = :id")
                .param("id", id)
                .update();
        return rows > 0;
    }

    /**
     * Nulls {@code imported_comment}/{@code internal_note} for every participant in a plan (spec
     * §21.2 plan-wide anonymize — {@code POST /api/plans/{planId}/comments/anonymize}). Returns the
     * number of rows affected (participants whose comments were actually cleared).
     */
    public int clearAllCommentsForPlan(String activityPlanId) {
        return jdbcClient.sql(
                        "UPDATE participant_profile SET imported_comment = NULL, internal_note = NULL "
                                + "WHERE activity_plan_id = :activityPlanId "
                                + "AND (imported_comment IS NOT NULL OR internal_note IS NOT NULL)")
                .param("activityPlanId", activityPlanId)
                .update();
    }

    /**
     * Persists {@code estimatedLevel}/{@code levelConfidence} for one participant — used by {@code
     * se.klubb.groupplanner.level.LevelService} so a plan-wide recompute doesn't have to round-trip
     * every other column through {@link #update}.
     *
     * <p>{@code forceManualReviewFlag} only ever turns {@code manual_review_flag} ON (spec §11.2:
     * no level source at all -&gt; "flagga för manuell bedömning"); it never clears an existing
     * {@code true} flag, since that may have been set by the council for reasons unrelated to level
     * confidence and a routine recompute must not silently un-flag it.
     */
    public void updateComputedLevel(String id, Double estimatedLevel, Double levelConfidence, boolean forceManualReviewFlag) {
        jdbcClient.sql("""
                        UPDATE participant_profile
                        SET estimated_level = :estimatedLevel, level_confidence = :levelConfidence,
                            manual_review_flag = CASE WHEN :forceManualReviewFlag = 1 THEN 1 ELSE manual_review_flag END
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("estimatedLevel", estimatedLevel)
                .param("levelConfidence", levelConfidence)
                .param("forceManualReviewFlag", forceManualReviewFlag ? 1 : 0)
                .update();
    }

    private static ParticipantProfile mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ParticipantProfile(
                rs.getString("id"),
                rs.getString("person_id"),
                rs.getString("activity_plan_id"),
                NullableColumns.nullableDouble(rs, "ranking_points"),
                rs.getString("ranking_source"),
                rs.getString("previous_group_name"),
                NullableColumns.nullableDouble(rs, "previous_group_level"),
                NullableColumns.nullableDouble(rs, "estimated_level"),
                NullableColumns.nullableDouble(rs, "level_confidence"),
                NullableColumns.nullableDouble(rs, "manual_level_score"),
                rs.getString("imported_comment"),
                rs.getString("internal_note"),
                rs.getInt("manual_review_flag") != 0,
                rs.getInt("waitlisted") != 0);
    }
}
