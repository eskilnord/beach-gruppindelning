package se.klubb.groupplanner.solver.run;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import se.klubb.groupplanner.repo.OptimizationRunRepository;

/**
 * Startup recovery for {@code optimization_run} rows orphaned by a crash/restart (backend/docs/
 * m6a-notes.md "Review fix 4"): a row still in a non-terminal status ({@code SOLVING}/{@code
 * QUEUED}) when the application boots can only be a leftover — solver jobs live purely in memory
 * ({@code SolverManager}), so no such run can survive a restart. Without this sweep, the plan's
 * run history would show a forever-"solving" ghost run (and any client trusting the run row over
 * {@code GET solve/status} would wait forever).
 *
 * <p>Runs on {@link ApplicationReadyEvent} — after Flyway/datasource setup, before any user
 * request could plausibly start a new solve.
 */
@Component
public class SolverRunStartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(SolverRunStartupRecovery.class);

    /** Swedish, shown verbatim in the run history UI (M6b). */
    static final String INTERRUPTED_SUMMARY_JSON = "{\"error\":\"avbruten av omstart\"}";

    private final OptimizationRunRepository optimizationRunRepository;

    public SolverRunStartupRecovery(OptimizationRunRepository optimizationRunRepository) {
        this.optimizationRunRepository = optimizationRunRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        int swept = sweep();
        if (swept > 0) {
            log.warn("Marked {} optimization_run row(s) stuck in SOLVING/QUEUED as FAILED (interrupted by restart)", swept);
        }
    }

    /** Extracted for direct testability (the shared test Spring context fires the ready event once,
     * before test fixtures exist). Returns the number of rows swept. */
    public int sweep() {
        return optimizationRunRepository.markInterruptedRunsFailed(Instant.now().toString(), INTERRUPTED_SUMMARY_JSON);
    }
}
