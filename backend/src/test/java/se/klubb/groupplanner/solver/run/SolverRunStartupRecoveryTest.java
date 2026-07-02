package se.klubb.groupplanner.solver.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.klubb.groupplanner.domain.ActivityPlan;
import se.klubb.groupplanner.domain.OptimizationRun;
import se.klubb.groupplanner.domain.SeasonPlan;
import se.klubb.groupplanner.repo.ActivityPlanRepository;
import se.klubb.groupplanner.repo.OptimizationRunRepository;
import se.klubb.groupplanner.repo.SeasonPlanRepository;
import se.klubb.groupplanner.util.Uuid7;

/**
 * Startup recovery for crash-orphaned {@code optimization_run} rows (review fix 4): rows stuck in
 * {@code SOLVING} (or {@code QUEUED}) are swept to {@code FAILED} with an "avbruten av omstart"
 * note; terminal rows are untouched. The sweep method is invoked directly rather than via a context
 * restart — the shared test Spring context fires {@code ApplicationReadyEvent} exactly once, before
 * any test fixture exists (which is also why the component exposes {@code sweep()} separately).
 */
@SpringBootTest
class SolverRunStartupRecoveryTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void appDataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> dataDir.toString());
    }

    @Autowired
    private SolverRunStartupRecovery recovery;
    @Autowired
    private OptimizationRunRepository optimizationRunRepository;
    @Autowired
    private SeasonPlanRepository seasonPlanRepository;
    @Autowired
    private ActivityPlanRepository activityPlanRepository;

    private String createPlan() {
        Instant now = Instant.now();
        SeasonPlan season = seasonPlanRepository.insert(new SeasonPlan(Uuid7.generate(), "VT26", null, null, "active", now, now));
        ActivityPlan plan = activityPlanRepository.insert(
                new ActivityPlan(Uuid7.generate(), season.id(), "Herr", "beach", "draft", null, null, null, null, now, now));
        return plan.id();
    }

    @Test
    void sweepMarksStuckSolvingRunsFailedAndLeavesTerminalRunsAlone() {
        String planId = createPlan();
        String now = Instant.now().toString();
        OptimizationRun stuckSolving = optimizationRunRepository.insert(new OptimizationRun(
                Uuid7.generate(), planId, "{}", "{}", null, OptimizationRun.STATUS_SOLVING, now, null, null, null, 0));
        OptimizationRun stuckQueued = optimizationRunRepository.insert(new OptimizationRun(
                Uuid7.generate(), planId, "{}", "{}", null, OptimizationRun.STATUS_QUEUED, now, null, null, null, 0));
        OptimizationRun finished = optimizationRunRepository.insert(new OptimizationRun(
                Uuid7.generate(), planId, "{}", "{}", "0hard/0medium/0soft", OptimizationRun.STATUS_FINISHED,
                now, now, 1234, "{\"hard\":0}", 0));

        int swept = recovery.sweep();

        assertThat(swept).isGreaterThanOrEqualTo(2); // >= : other tests in the shared DB may have their own strays.

        OptimizationRun sweptSolving = optimizationRunRepository.findById(stuckSolving.id()).orElseThrow();
        assertThat(sweptSolving.status()).isEqualTo(OptimizationRun.STATUS_FAILED);
        assertThat(sweptSolving.finishedAt()).isNotBlank();
        assertThat(sweptSolving.resultSummaryJson()).contains("avbruten av omstart");

        OptimizationRun sweptQueued = optimizationRunRepository.findById(stuckQueued.id()).orElseThrow();
        assertThat(sweptQueued.status()).isEqualTo(OptimizationRun.STATUS_FAILED);

        OptimizationRun untouched = optimizationRunRepository.findById(finished.id()).orElseThrow();
        assertThat(untouched.status()).isEqualTo(OptimizationRun.STATUS_FINISHED);
        assertThat(untouched.resultSummaryJson()).isEqualTo("{\"hard\":0}");
    }
}
