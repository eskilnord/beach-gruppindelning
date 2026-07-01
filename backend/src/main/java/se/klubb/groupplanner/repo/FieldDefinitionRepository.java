package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.FieldDefinition;

/**
 * Read-only access to the global standard fields (spec §9.2) seeded by
 * {@code V2__seed_constraints_and_standard_fields.sql}. Per-plan custom field CRUD arrives in M4.
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
