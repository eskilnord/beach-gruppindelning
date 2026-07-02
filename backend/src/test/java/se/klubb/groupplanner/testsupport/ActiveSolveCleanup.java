package se.klubb.groupplanner.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import se.klubb.groupplanner.api.error.BadRequestException;
import se.klubb.groupplanner.domain.OptimizationRun;
import se.klubb.groupplanner.solver.run.SolveCoordinator;

/**
 * JUnit extension that guarantees no background solve survives a test method — the systemic fix
 * for the {@code SQLITE_BUSY} ("database is locked") CI flake on slow Windows runners.
 *
 * <p><b>The hazard:</b> a test that starts an async solve ({@code SolveCoordinator#startSolve} /
 * {@code POST .../solve} with a wall-clock profile) can return while the solve is still running.
 * Even after {@code cancelSolve} + awaiting {@code SolverManager} to report {@code NOT_SOLVING},
 * the solve is NOT done touching the database: the final-best-solution consumer
 * ({@code SolveCoordinator#onFinalBestSolution}) fires <em>after</em> the status flip and runs the
 * whole writeback ({@code persistResult}: player/group/coach rows + revision bump) plus
 * {@code finishRun}. On a slow runner that writeback holds the SQLite write lock longer than
 * {@code busy_timeout}, and the NEXT test method's setup inserts fail with {@code SQLITE_BUSY}
 * (observed: {@code INSERT INTO training_block} in {@code SolveControllerIntegrationTest} and
 * {@code StalenessAndApplyMoveTest} on windows-latest).
 *
 * <p><b>The guarantee:</b> after each test, every non-terminal {@code optimization_run} row
 * ({@code SOLVING}/{@code QUEUED}) is cancelled (if the solver is still running) and then awaited
 * to a terminal status ({@code FINISHED}/{@code CANCELLED}/{@code FAILED}). Terminal status is
 * written by {@code finishRun}/{@code failRun}, which run strictly <em>after</em> the writeback —
 * so a terminal row means every write lock from that solve has been released. Polling the run ROW
 * (not the transient solver status) is the same pattern the lifecycle tests in
 * {@code SolveControllerIntegrationTest} use, generalized.
 *
 * <p>Reads the run table directly via {@link JdbcClient} rather than adding a test-only query
 * method to a production repository; cancellation goes through the public
 * {@link SolveCoordinator#cancelSolve} exactly like the REST endpoint. Wire with
 * {@code @ExtendWith(ActiveSolveCleanup.class)} on any {@code @SpringBootTest} class that starts
 * async solves. Synchronous solves (GREEDY via {@code runGreedy}, direct {@code TestSolverFactory}
 * usage) never need this — their run rows are terminal before the test method returns.
 */
public final class ActiveSolveCleanup implements AfterEachCallback {

    private static final Duration AWAIT_TERMINAL = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofMillis(200);

    @Override
    public void afterEach(ExtensionContext context) {
        ApplicationContext spring = SpringExtension.getApplicationContext(context);
        SolveCoordinator solveCoordinator = spring.getBean(SolveCoordinator.class);
        JdbcClient jdbcClient = spring.getBean(JdbcClient.class);

        List<Map<String, Object>> nonTerminal = jdbcClient.sql(
                        "SELECT id, activity_plan_id FROM optimization_run WHERE status IN ('SOLVING', 'QUEUED')")
                .query()
                .listOfRows();
        if (nonTerminal.isEmpty()) {
            return;
        }

        for (Map<String, Object> row : nonTerminal) {
            try {
                solveCoordinator.cancelSolve((String) row.get("activity_plan_id"));
            } catch (BadRequestException solverAlreadyStopped) {
                // SolverManager already reports NOT_SOLVING for this plan, i.e. the run is in its
                // post-solve writeback window - the exact hazard this extension exists for. There
                // is nothing left to cancel; the await below covers the writeback completing.
            }
        }

        for (Map<String, Object> row : nonTerminal) {
            String runId = (String) row.get("id");
            await().atMost(AWAIT_TERMINAL).pollInterval(POLL).untilAsserted(() -> {
                String status = jdbcClient.sql("SELECT status FROM optimization_run WHERE id = :id")
                        .param("id", runId)
                        .query(String.class)
                        .single();
                assertThat(status)
                        .as("leftover solve (run %s) must reach a terminal status before the next test", runId)
                        .isIn(OptimizationRun.STATUS_FINISHED,
                                OptimizationRun.STATUS_CANCELLED,
                                OptimizationRun.STATUS_FAILED);
            });
        }
    }
}
