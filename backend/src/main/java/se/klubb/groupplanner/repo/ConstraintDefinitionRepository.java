package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.ConstraintDefinition;

/**
 * Read-only access to the 24 standard constraints seeded by
 * {@code V2__seed_constraints_and_standard_fields.sql}. Per-plan weight overrides
 * ({@code constraint_weight_config}) arrive in M6b.
 */
@Repository
public class ConstraintDefinitionRepository {

    private final JdbcClient jdbcClient;

    public ConstraintDefinitionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<ConstraintDefinition> findAll() {
        return jdbcClient.sql("SELECT * FROM constraint_definition ORDER BY constraint_category, key")
                .query(ConstraintDefinitionRepository::mapRow)
                .list();
    }

    private static ConstraintDefinition mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ConstraintDefinition(
                rs.getString("key"),
                rs.getString("label"),
                rs.getString("description"),
                rs.getString("constraint_category"),
                rs.getInt("default_weight"),
                rs.getString("hard_or_soft"),
                rs.getInt("enabled") != 0);
    }
}
