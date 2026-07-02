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

    public Optional<FieldDefinition> findById(String id) {
        return jdbcClient.sql("SELECT * FROM field_definition WHERE id = :id")
                .param("id", id)
                .query(FieldDefinitionRepository::mapRow)
                .optional();
    }

    /** This plan's own custom fields only (excludes globals) — the Fältbyggare CRUD listing (M4). */
    public List<FieldDefinition> findCustomFieldsForPlan(String activityPlanId) {
        return jdbcClient.sql("SELECT * FROM field_definition WHERE activity_plan_id = :activityPlanId ORDER BY sort_order, key")
                .param("activityPlanId", activityPlanId)
                .query(FieldDefinitionRepository::mapRow)
                .list();
    }

    /** {@code COALESCE(MAX(sort_order), 100) + 1} among fields visible to the plan — used to append
     * newly-created custom fields after the standard fields in listing order without requiring the
     * caller to pick a sort_order. */
    public int nextSortOrderForPlan(String activityPlanId) {
        Integer max = jdbcClient.sql(
                        "SELECT MAX(sort_order) FROM field_definition WHERE activity_plan_id IS NULL OR activity_plan_id = :activityPlanId")
                .param("activityPlanId", activityPlanId)
                .query(Integer.class)
                .optional()
                .orElse(100);
        return (max == null ? 100 : max) + 1;
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

    /**
     * Full-row update. {@code key}/{@code fieldType}/{@code storageKind}/{@code columnName}/{@code
     * isStandard}/{@code activityPlanId} are treated as immutable identity by callers (validated in
     * {@code se.klubb.groupplanner.fields.FieldDefinitionValidator}, not here) — this method itself
     * updates every column so it stays a simple full-record replace like the rest of this codebase's
     * repositories.
     */
    public FieldDefinition update(FieldDefinition field) {
        jdbcClient.sql("""
                        UPDATE field_definition
                        SET label = :label, affects_optimization = :affectsOptimization,
                            constraint_type = :constraintType, hard_or_soft = :hardOrSoft, weight = :weight,
                            direction = :direction, explanation_text = :explanationText, options_json = :optionsJson,
                            sort_order = :sortOrder
                        WHERE id = :id
                        """)
                .param("id", field.id())
                .param("label", field.label())
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

    /** Cascades to {@code custom_field_value} rows (ON DELETE CASCADE, V2). Only ever called for
     * plan-scoped CUSTOM fields — standard/global fields are never deletable (enforced in the
     * controller). */
    public boolean deleteById(String id) {
        int rows = jdbcClient.sql("DELETE FROM field_definition WHERE id = :id").param("id", id).update();
        return rows > 0;
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
