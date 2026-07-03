package se.klubb.groupplanner.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import se.klubb.groupplanner.domain.OptimizationRun;

/**
 * {@code optimization_run} CRUD via {@link JdbcClient} (ADR-004, V5__solver_runs.sql). Written by
 * {@code se.klubb.groupplanner.solver.run.OptimizationRunService}.
 */
@Repository
public class OptimizationRunRepository {

    private final JdbcClient jdbcClient;

    public OptimizationRunRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public OptimizationRun insert(OptimizationRun run) {
        jdbcClient.sql("""
                        INSERT INTO optimization_run
                            (id, activity_plan_id, input_snapshot_json, constraint_weights_json, score, status,
                             started_at, finished_at, duration_ms, result_summary_json, plan_revision)
                        VALUES
                            (:id, :activityPlanId, :inputSnapshotJson, :constraintWeightsJson, :score, :status,
                             :startedAt, :finishedAt, :durationMs, :resultSummaryJson, :planRevision)
                        """)
                .param("id", run.id())
                .param("activityPlanId", run.activityPlanId())
                .param("inputSnapshotJson", run.inputSnapshotJson())
                .param("constraintWeightsJson", run.constraintWeightsJson())
                .param("score", run.score())
                .param("status", run.status())
                .param("startedAt", run.startedAt())
                .param("finishedAt", run.finishedAt())
                .param("durationMs", run.durationMs())
                .param("resultSummaryJson", run.resultSummaryJson())
                .param("planRevision", run.planRevision())
                .update();
        return run;
    }

    public OptimizationRun update(OptimizationRun run) {
        jdbcClient.sql("""
                        UPDATE optimization_run
                        SET input_snapshot_json = :inputSnapshotJson, constraint_weights_json = :constraintWeightsJson,
                            score = :score, status = :status, started_at = :startedAt, finished_at = :finishedAt,
                            duration_ms = :durationMs, result_summary_json = :resultSummaryJson,
                            plan_revision = :planRevision
                        WHERE id = :id
                        """)
                .param("id", run.id())
                .param("inputSnapshotJson", run.inputSnapshotJson())
                .param("constraintWeightsJson", run.constraintWeightsJson())
                .param("score", run.score())
                .param("status", run.status())
                .param("startedAt", run.startedAt())
                .param("finishedAt", run.finishedAt())
                .param("durationMs", run.durationMs())
                .param("resultSummaryJson", run.resultSummaryJson())
                .param("planRevision", run.planRevision())
                .update();
        return run;
    }

    public Optional<OptimizationRun> findById(String id) {
        return jdbcClient.sql("SELECT * FROM optimization_run WHERE id = :id")
                .param("id", id)
                .query(OptimizationRunRepository::mapRow)
                .optional();
    }

    /** Most recent run for a plan (by started_at desc, id desc as a stable tiebreak). */
    public Optional<OptimizationRun> findLatestByActivityPlanId(String activityPlanId) {
        return jdbcClient.sql("""
                        SELECT * FROM optimization_run WHERE activity_plan_id = :activityPlanId
                        ORDER BY started_at DESC, id DESC LIMIT 1
                        """)
                .param("activityPlanId", activityPlanId)
                .query(OptimizationRunRepository::mapRow)
                .optional();
    }

    /** WI-C unchanged-result detection ({@code se.klubb.groupplanner.solver.run.OptimizationRunService
     * #hasFinishedRun}): whether this plan has any run that reached {@link OptimizationRun
     * #STATUS_FINISHED} (excludes CANCELLED/FAILED and any currently-SOLVING row). */
    public boolean existsFinishedByActivityPlanId(String activityPlanId) {
        Integer count = jdbcClient.sql(
                        "SELECT COUNT(*) FROM optimization_run WHERE activity_plan_id = :activityPlanId AND status = :status")
                .param("activityPlanId", activityPlanId)
                .param("status", OptimizationRun.STATUS_FINISHED)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public List<OptimizationRun> findByActivityPlanId(String activityPlanId) {
        return jdbcClient.sql("SELECT * FROM optimization_run WHERE activity_plan_id = :activityPlanId "
                        + "ORDER BY started_at DESC, id DESC")
                .param("activityPlanId", activityPlanId)
                .query(OptimizationRunRepository::mapRow)
                .list();
    }

    /**
     * Startup recovery sweep (backend/docs/m6a-notes.md "Review fix 4"): a run left in a
     * non-terminal state ({@code SOLVING}, or the currently-never-written-but-defined {@code
     * QUEUED}) after a JVM restart can only mean the process died mid-solve — the in-memory
     * {@code SolverManager} job is gone, so the row would otherwise report "solving" forever.
     * Marks such rows {@code FAILED} with an explanatory {@code result_summary_json}. Returns the
     * number of rows swept.
     */
    public int markInterruptedRunsFailed(String finishedAt, String resultSummaryJson) {
        return jdbcClient.sql("""
                        UPDATE optimization_run
                        SET status = 'FAILED', finished_at = :finishedAt, result_summary_json = :resultSummaryJson
                        WHERE status IN ('SOLVING', 'QUEUED')
                        """)
                .param("finishedAt", finishedAt)
                .param("resultSummaryJson", resultSummaryJson)
                .update();
    }

    private static OptimizationRun mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new OptimizationRun(
                rs.getString("id"),
                rs.getString("activity_plan_id"),
                rs.getString("input_snapshot_json"),
                rs.getString("constraint_weights_json"),
                rs.getString("score"),
                rs.getString("status"),
                rs.getString("started_at"),
                rs.getString("finished_at"),
                NullableColumns.nullableInt(rs, "duration_ms"),
                rs.getString("result_summary_json"),
                rs.getInt("plan_revision"));
    }
}
