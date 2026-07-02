package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.FieldDefinition;

/**
 * Read access to field definitions (spec §9.2) seeded by
 * {@code V2__seed_constraints_and_standard_fields.sql}, plus the minimal write path the M3 import
 * wizard needs (creating a hidden global field the first time a "coach wish" column is imported,
 * see {@code se.klubb.groupplanner.importer.ImportCommitService}). Full per-plan custom field CRUD
 * (Fältbyggaren) arrives in M4.
 */
@Repository
public class FieldDefinitionRepository {

    private final JdbcClient jdbcClient;

    public FieldDefinitionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Global standard fields ({@code activity_plan_id IS NULL}), in spec §9.2 order. */
    public List<FieldDefinition> findStandardFields() {
        return jdbcClient.sql("SELECT * FROM field_definition WHERE activity_plan_id IS NULL ORDER BY sort_order, key")
                .query(FieldDefinitionRepository::mapRow)
                .list();
    }

    /** All fields visible to a plan: its own custom fields plus every global/standard field. */
    public List<FieldDefinition> findVisibleToPlan(String activityPlanId) {
        return jdbcClient.sql(
                        "SELECT * FROM field_definition WHERE activity_plan_id IS NULL OR activity_plan_id = :activityPlanId "
                                + "ORDER BY sort_order, key")
                .param("activityPlanId", activityPlanId)
                .query(FieldDefinitionRepository::mapRow)
                .list();
    }

    /**
     * Resolves a field {@code key} the way the import mapping wizard does: the plan's own custom
     * field with that key takes precedence, falling back to the global/standard field of the same
     * key.
     */
    public Optional<FieldDefinition> findByKeyVisibleToPlan(String activityPlanId, String key) {
        return jdbcClient.sql(
                        "SELECT * FROM field_definition WHERE key = :key "
                                + "AND (activity_plan_id = :activityPlanId OR activity_plan_id IS NULL) "
                                + "ORDER BY (activity_plan_id IS NULL) ASC LIMIT 1")
                .param("key", key)
                .param("activityPlanId", activityPlanId)
                .query(FieldDefinitionRepository::mapRow)
                .optional();
    }

    public Optional<FieldDefinition> findGlobalByKey(String key) {
        return jdbcClient.sql("SELECT * FROM field_definition WHERE key = :key AND activity_plan_id IS NULL")
                .param("key", key)
                .query(FieldDefinitionRepository::mapRow)
                .optional();
    }

    public FieldDefinition insert(FieldDefinition field) {
        jdbcClient.sql("""
                        INSERT INTO field_definition
                            (id, activity_plan_id, key, label, field_type, is_standard, storage_kind, column_name,
                             affects_optimization, constraint_type, hard_or_soft, weight, direction,
                             explanation_text, options_json, sort_order)
                        VALUES
                            (:id, :activityPlanId, :key, :label, :fieldType, :isStandard, :storageKind, :columnName,
                             :affectsOptimization, :constraintType, :hardOrSoft, :weight, :direction,
                             :explanationText, :optionsJson, :sortOrder)
                        """)
                .param("id", field.id())
                .param("activityPlanId", field.activityPlanId())
                .param("key", field.key())
                .param("label", field.label())
                .param("fieldType", field.fieldType())
                .param("isStandard", field.isStandard() ? 1 : 0)
                .param("storageKind", field.storageKind())
                .param("columnName", field.columnName())
                .param("affectsOptimization", field.affectsOptimization() ? 1 : 0)
                .param("constraintType", field.constraintType())
                .param("hardOrSoft", field.hardOrSoft())
                .param("weight", field.weight())
                .param("direction", field.direction())
                .param("explanationText", field.explanationText())
                .param("optionsJson", field.optionsJson())
                .param("sortOrder", field.sortOrder())
                .update();
        return field;
    }

    private static FieldDefinition mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new FieldDefinition(
                rs.getString("id"),
                rs.getString("activity_plan_id"),
                rs.getString("key"),
                rs.getString("label"),
                rs.getString("field_type"),
                rs.getInt("is_standard") != 0,
                rs.getString("storage_kind"),
                rs.getString("column_name"),
                rs.getInt("affects_optimization") != 0,
                rs.getString("constraint_type"),
                rs.getString("hard_or_soft"),
                NullableColumns.nullableInt(rs, "weight"),
                rs.getString("direction"),
                rs.getString("explanation_text"),
                rs.getString("options_json"),
                NullableColumns.nullableInt(rs, "sort_order"));
    }
}
