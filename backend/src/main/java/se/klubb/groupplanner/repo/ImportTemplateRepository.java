package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.ImportTemplate;

/**
 * {@code import_template} CRUD via {@link JdbcClient} (ADR-004).
 */
@Repository
public class ImportTemplateRepository {

    private final JdbcClient jdbcClient;

    public ImportTemplateRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<ImportTemplate> findAll() {
        return jdbcClient.sql("SELECT * FROM import_template ORDER BY created_at, id")
                .query(ImportTemplateRepository::mapRow)
                .list();
    }

    public Optional<ImportTemplate> findById(String id) {
        return jdbcClient.sql("SELECT * FROM import_template WHERE id = :id")
                .param("id", id)
                .query(ImportTemplateRepository::mapRow)
                .optional();
    }

    /** Most recent template matching a header hash exactly, if any (docs/plan.md: "keyed by header hash"). */
    public Optional<ImportTemplate> findFirstByHeaderHash(String headerHash) {
        return jdbcClient.sql("SELECT * FROM import_template WHERE header_hash = :headerHash ORDER BY created_at DESC")
                .param("headerHash", headerHash)
                .query(ImportTemplateRepository::mapRow)
                .optional();
    }

    public ImportTemplate insert(ImportTemplate template) {
        jdbcClient.sql("""
                        INSERT INTO import_template (id, name, header_hash, mapping_json, created_at)
                        VALUES (:id, :name, :headerHash, :mappingJson, :createdAt)
                        """)
                .param("id", template.id())
                .param("name", template.name())
                .param("headerHash", template.headerHash())
                .param("mappingJson", template.mappingJson())
                .param("createdAt", template.createdAt().toString())
                .update();
        return template;
    }

    public boolean deleteById(String id) {
        int rows = jdbcClient.sql("DELETE FROM import_template WHERE id = :id").param("id", id).update();
        return rows > 0;
    }

    private static ImportTemplate mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ImportTemplate(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("header_hash"),
                rs.getString("mapping_json"),
                Instant.parse(rs.getString("created_at")));
    }
}
