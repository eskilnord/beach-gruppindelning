package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.ImportRun;

/**
 * {@code import_run} CRUD via {@link JdbcClient} (ADR-004).
 */
@Repository
public class ImportRunRepository {

    private final JdbcClient jdbcClient;

    public ImportRunRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<ImportRun> findByActivityPlanId(String activityPlanId) {
        return jdbcClient.sql("SELECT * FROM import_run WHERE activity_plan_id = :activityPlanId ORDER BY created_at, id")
                .param("activityPlanId", activityPlanId)
                .query(ImportRunRepository::mapRow)
                .list();
    }

    public Optional<ImportRun> findById(String id) {
        return jdbcClient.sql("SELECT * FROM import_run WHERE id = :id")
                .param("id", id)
                .query(ImportRunRepository::mapRow)
                .optional();
    }

    public ImportRun insert(ImportRun run) {
        jdbcClient.sql("""
                        INSERT INTO import_run
                            (id, activity_plan_id, file_name, sheet_name, import_template_id,
                             total_rows, imported_rows, skipped_rows, warnings_json, decisions_json, created_at)
                        VALUES
                            (:id, :activityPlanId, :fileName, :sheetName, :importTemplateId,
                             :totalRows, :importedRows, :skippedRows, :warningsJson, :decisionsJson, :createdAt)
                        """)
                .param("id", run.id())
                .param("activityPlanId", run.activityPlanId())
                .param("fileName", run.fileName())
                .param("sheetName", run.sheetName())
                .param("importTemplateId", run.importTemplateId())
                .param("totalRows", run.totalRows())
                .param("importedRows", run.importedRows())
                .param("skippedRows", run.skippedRows())
                .param("warningsJson", run.warningsJson())
                .param("decisionsJson", run.decisionsJson())
                .param("createdAt", run.createdAt().toString())
                .update();
        return run;
    }

    private static ImportRun mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ImportRun(
                rs.getString("id"),
                rs.getString("activity_plan_id"),
                rs.getString("file_name"),
                rs.getString("sheet_name"),
                rs.getString("import_template_id"),
                rs.getInt("total_rows"),
                rs.getInt("imported_rows"),
                rs.getInt("skipped_rows"),
                rs.getString("warnings_json"),
                rs.getString("decisions_json"),
                Instant.parse(rs.getString("created_at")));
    }
}
