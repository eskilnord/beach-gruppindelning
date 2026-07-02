package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.CustomFieldValue;
import se.klubb.groupplanner.util.Uuid7;

/**
 * {@code custom_field_value} access via {@link JdbcClient} (ADR-004). Only the operations the
 * import wizard needs (M3): upsert one value, list values for an entity. Full field-value CRUD for
 * the Deltagarvy structuring drawer arrives in M4.
 */
@Repository
public class CustomFieldValueRepository {

    private final JdbcClient jdbcClient;

    public CustomFieldValueRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Inserts a value, or replaces the existing one for the same {@code (fieldDefinitionId,
     * entityType, entityId)} triple (the table's UNIQUE constraint, spec §7.14) — makes re-running
     * an import commit for the same plan/rows idempotent rather than failing on a constraint
     * violation.
     */
    public CustomFieldValue upsert(String fieldDefinitionId, String entityType, String entityId, String valueJson) {
        Optional<String> existingId = jdbcClient.sql(
                        "SELECT id FROM custom_field_value WHERE field_definition_id = :fieldDefinitionId "
                                + "AND entity_type = :entityType AND entity_id = :entityId")
                .param("fieldDefinitionId", fieldDefinitionId)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .query(String.class)
                .optional();

        String id = existingId.orElseGet(Uuid7::generate);
        jdbcClient.sql("""
                        INSERT INTO custom_field_value (id, field_definition_id, entity_type, entity_id, value_json)
                        VALUES (:id, :fieldDefinitionId, :entityType, :entityId, :valueJson)
                        ON CONFLICT (field_definition_id, entity_type, entity_id)
                        DO UPDATE SET value_json = excluded.value_json
                        """)
                .param("id", id)
                .param("fieldDefinitionId", fieldDefinitionId)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("valueJson", valueJson)
                .update();
        return new CustomFieldValue(id, fieldDefinitionId, entityType, entityId, valueJson);
    }

    public List<CustomFieldValue> findByEntity(String entityType, String entityId) {
        // ORDER BY id (M6a determinism rule, ADR-007): se.klubb.groupplanner.solver.assemble
        // .SolverInputAssembler iterates this result to build wish facts, and SQL without an
        // explicit ORDER BY has no guaranteed row order.
        return jdbcClient.sql("SELECT * FROM custom_field_value WHERE entity_type = :entityType AND entity_id = :entityId ORDER BY id")
                .param("entityType", entityType)
                .param("entityId", entityId)
                .query(CustomFieldValueRepository::mapRow)
                .list();
    }

    private static CustomFieldValue mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new CustomFieldValue(
                rs.getString("id"),
                rs.getString("field_definition_id"),
                rs.getString("entity_type"),
                rs.getString("entity_id"),
                rs.getString("value_json"));
    }
}
