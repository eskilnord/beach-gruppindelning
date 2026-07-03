package se.klubb.groupplanner.solver.run;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import se.klubb.groupplanner.common.time.TimeKey;
import se.klubb.groupplanner.solver.domain.CoachFact;
import se.klubb.groupplanner.solver.domain.CoachSlot;
import se.klubb.groupplanner.solver.domain.CoachWish;
import se.klubb.groupplanner.solver.domain.CoachWishType;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.GroupSchedule;
import se.klubb.groupplanner.solver.domain.LateTimePolicy;
import se.klubb.groupplanner.solver.domain.PersonPairWish;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;
import se.klubb.groupplanner.solver.domain.TrainingBlock;
import se.klubb.groupplanner.solver.domain.WishType;

/**
 * Hardware micro-benchmark for v0.2.0's "suggested optimization time" feature
 * (backend/docs/v020-notes.md): solves a FIXED, hardcoded, deterministic synthetic problem — no DB
 * reads, no persistence, never varies between calls or between plans — at production-like solver
 * settings ({@code NO_ASSERT} + {@code randomSeed 0}, exactly {@code solverConfig.xml}'s own
 * defaults) with a fixed total step budget, and measures wall-clock milliseconds. Since the problem
 * and the step budget never change, the measured time is a pure function of THIS machine's raw
 * Timefold move-evaluation throughput — a fair, repeatable machine-speed proxy.
 *
 * <p><b>Why NO_ASSERT + a fixed step count (not the CI convention of {@code PHASE_ASSERT})</b>: CI
 * golden-score tests use step-count termination FOR DETERMINISTIC SCORING (ADR-007) with
 * {@code PHASE_ASSERT}'s extra correctness checks. This benchmark wants raw *speed*, matching what a
 * real user solve experiences ({@code NO_ASSERT}, exactly like {@code solverConfig.xml} and every
 * production {@code SolveCoordinator} job) — the step count is fixed only so the workload is
 * reproducible, not for scoring determinism (nobody reads this problem's score).
 *
 * <p><b>Wall-clock cap (v0.2.0 review fast-follow 1)</b>: a step-count-only benchmark would block
 * the first {@code suggest-duration} call for 12-25s on a 5-10× slower machine (the actual target
 * hardware class). The total step budget is therefore split into {@link #BENCHMARK_CHUNKS} equal
 * sequential chunks (each its own fresh {@code Solver} + fresh problem instance, so every chunk is
 * an identical, independently-terminating unit of work), and the loop stops early once {@link
 * #BENCHMARK_TIME_CAP_MS} of wall-clock is spent. Each chunk additionally carries a defensive
 * {@code spentLimit} of the REMAINING budget, so even one pathologically slow chunk cannot overshoot
 * the cap by more than Timefold's own termination-check granularity. When the cap fires, the speed
 * factor is derived from the steps ACTUALLY completed, proportionally:
 *
 * <pre>
 * machineSpeedFactor = (stepsDone / stepsPlanned) × (REFERENCE_MS / elapsedMs)
 * </pre>
 *
 * Derivation: the extrapolated time to complete ALL planned steps at the observed per-step rate is
 * {@code elapsedMs × stepsPlanned / stepsDone}; the factor is {@code REFERENCE_MS} divided by that,
 * which rearranges to the product above. (Note the ratio's direction — {@code
 * stepsDone/stepsPlanned}, a value ≤ 1: an incomplete run must REDUCE the reported speed, never
 * inflate it.) When every chunk completes, {@code stepsDone == stepsPlanned} and the formula
 * degenerates to the plain uncapped {@code REFERENCE_MS / elapsedMs}. A partial final chunk (cut
 * mid-chunk by its own {@code spentLimit}) contributes its wall time to {@code elapsedMs} but
 * nothing to {@code stepsDone} — slightly UNDERSTATING speed, i.e. erring toward a LONGER suggested
 * duration, the safe direction (correctness over speed, per the feature's own brief). The factor is
 * floored at {@link #MIN_SPEED_FACTOR}: any machine ≥100× slower than reference saturates the
 * suggestion formula's 600s clamp long before precision down there could matter, and the floor keeps
 * the degenerate zero-chunks-completed case (factor would otherwise be 0 → division by zero
 * downstream) well-defined.
 *
 * <p><b>Idle-machine assumption</b>: the measurement — and therefore every suggestion derived from
 * it for the rest of the session, since the result is cached — assumes an otherwise-idle machine. A
 * benchmark that runs while something else saturates the CPU reads as "slow hardware" and inflates
 * every subsequent suggestion. {@code SuggestDurationService}'s per-plan 409 guard stops the one
 * in-app contention source a user can create against the SAME plan, but deliberately NOT cross-plan
 * contention or out-of-process load — an accepted trade-off for this single-user desktop tool.
 *
 * <p><b>Caching</b>: the benchmark is run at most ONCE per application session (an in-memory,
 * double-checked-locking cache) — re-benchmarking on every {@code suggest-duration} call would be
 * wasteful and would make {@code suggestedSeconds} jitter across calls for the exact same plan,
 * which the task's own determinism note explicitly guards against ("suggestedSeconds stable via
 * cache").
 */
@Service
public class SolveBenchmarkService {

    /**
     * The total step budget is {@code BENCHMARK_CHUNKS × CHUNK_STEP_COUNT_LIMIT} = 140,000 steps of
     * the fixed synthetic problem (~30 players / 4 groups / 4 blocks / 3 coaches), split into ten
     * equal chunks so the wall-clock cap gets ~10% work granularity on a slow machine. The 140k
     * total was tuned empirically against THIS implementation's own dev machine (Apple Silicon Mac,
     * Temurin JDK 21.0.11) so a fast machine lands in the ~1.5-2.5s window the task asks for — see
     * backend/docs/v020-notes.md "Feature 2 benchmark calibration" for the measurement table.
     * Per-chunk overhead (fresh problem build + Construction Heuristic, both around a millisecond at
     * this fixture size) is negligible against a ~240ms chunk.
     */
    static final int BENCHMARK_CHUNKS = 10;

    static final int CHUNK_STEP_COUNT_LIMIT = 14_000;

    /** Wall-clock cap on the WHOLE benchmark (review fast-follow 1): a machine slow enough to need
     * more than this gets a proportional steps-actually-completed factor instead of blocking the
     * first {@code suggest-duration} call for however long 140k steps take there. 30s bounds the
     * worst-case first-call latency while still letting a ~12× slower machine finish the full
     * measurement uncapped. */
    static final long BENCHMARK_TIME_CAP_MS = 30_000L;

    /** Factor floor (class javadoc): ≥100× slower than reference already saturates the suggestion
     * formula's clamp, and the floor keeps the zero-chunks-completed degenerate case well-defined. */
    static final double MIN_SPEED_FACTOR = 0.01;

    /**
     * Wall-clock milliseconds the reference dev machine (Apple Silicon Mac, Temurin JDK 21.0.11 —
     * the machine this feature was implemented and verified on) measures for the FULL uncapped
     * 140,000-step benchmark. Calibrated ONCE and hardcoded per the task's own instruction
     * ("REFERENCE_MS is what THIS dev machine measures... hardcode with a comment"): the original
     * single-solve implementation measured 2612/2241/2411/2361 ms over four cold fresh-JVM runs
     * (mean ≈ 2406, reference 2400); the chunked rewrite (review fast-follow 1) re-measured
     * 2269/2042/2131/2318 ms over four cold fresh-JVM runs (mean ≈ 2190 — slightly faster, since
     * chunks 2-10 run JIT-warm where the single-solve version paid warmup across its whole run), so
     * the reference was RE-calibrated to the round 2200 to match the workload actually shipping.
     * {@code machineSpeedFactor = REFERENCE_MS / measuredMs}: a machine measuring FASTER than this
     * (fewer ms) gets a factor &gt; 1.0 ("faster than reference"), and {@code
     * SuggestDurationService} divides its raw estimate by the factor, so faster hardware gets a
     * SHORTER suggested duration.
     */
    static final long REFERENCE_MS = 2_200L;

    private static final int GROUP_COUNT = 4;
    private static final int BLOCK_COUNT = 4;
    private static final int COACH_COUNT = 3;
    private static final int PLAYER_COUNT = 30;

    private volatile BenchmarkResult cached;

    /** One-time hardware measurement, cached for the application session. */
    public record BenchmarkResult(long benchmarkMs, double machineSpeedFactor) {
    }

    public BenchmarkResult benchmark() {
        BenchmarkResult result = cached;
        if (result == null) {
            synchronized (this) {
                result = cached;
                if (result == null) {
                    result = runBenchmark(BENCHMARK_TIME_CAP_MS);
                    cached = result;
                }
            }
        }
        return result;
    }

    /** Package-visible (not private) so the wall-clock-cap path is directly testable with a tiny
     * budget ({@code SolveBenchmarkServiceTest}) — production code only ever reaches this through
     * {@link #benchmark()}'s cache with the real {@link #BENCHMARK_TIME_CAP_MS}. */
    BenchmarkResult runBenchmark(long timeCapMs) {
        long startNanos = System.nanoTime();
        int completedChunks = 0;
        for (int chunk = 0; chunk < BENCHMARK_CHUNKS; chunk++) {
            long remainingBudgetMs = timeCapMs - elapsedMsSince(startNanos);
            if (remainingBudgetMs <= 0) {
                break;
            }
            TerminationConfig termination = new TerminationConfig()
                    .withStepCountLimit(CHUNK_STEP_COUNT_LIMIT)
                    // Defensive per-chunk wall-clock cap (review fast-follow 1): bounds even a
                    // single pathologically slow chunk to the remaining overall budget.
                    .withSpentLimit(Duration.ofMillis(remainingBudgetMs));
            // Deliberately does NOT override environmentMode (stays solverConfig.xml's own
            // NO_ASSERT) or randomSeed (stays 0) - "production-like speed" per the task spec, unlike
            // test code's TestSolverFactory which forces PHASE_ASSERT for correctness-checking.
            SolverConfig config = SolverConfig.createFromXmlResource("solverConfig.xml").withTerminationConfig(termination);
            SolverFactory<GroupPlanSolution> factory = SolverFactory.create(config);

            long chunkStartNanos = System.nanoTime();
            factory.buildSolver().solve(buildSyntheticProblem());
            long chunkElapsedMs = elapsedMsSince(chunkStartNanos);

            if (chunkElapsedMs >= remainingBudgetMs) {
                // The chunk's spentLimit fired (or it consumed the entire remaining budget): its
                // internal step count is unknown, so it contributes wall time but no steps - see
                // the class javadoc for why that errs toward understating speed (safe direction).
                break;
            }
            completedChunks++;
        }
        long elapsedMs = Math.max(1, elapsedMsSince(startNanos));
        long stepsDone = (long) completedChunks * CHUNK_STEP_COUNT_LIMIT;
        long stepsPlanned = (long) BENCHMARK_CHUNKS * CHUNK_STEP_COUNT_LIMIT;
        return new BenchmarkResult(elapsedMs, computeSpeedFactor(stepsDone, stepsPlanned, elapsedMs));
    }

    /**
     * The proportional speed factor (full derivation in the class javadoc): {@code
     * (stepsDone/stepsPlanned) × (REFERENCE_MS/elapsedMs)}, floored at {@link #MIN_SPEED_FACTOR}.
     * Static and package-visible so the arithmetic (full completion, partial completion, zero
     * completion) is unit-testable without running any real solver.
     */
    static double computeSpeedFactor(long stepsDone, long stepsPlanned, long elapsedMs) {
        double proportion = stepsDone / (double) stepsPlanned;
        double factor = proportion * (REFERENCE_MS / (double) elapsedMs);
        return Math.max(MIN_SPEED_FACTOR, factor);
    }

    private static long elapsedMsSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * Hardcoded, deterministic ~30 players / 4 groups / 4 blocks / 3 coaches problem with a handful
     * of friend/coach wishes and time preferences so every constraint family gets SOME exercise
     * (representative of a real small-plan solve) — built entirely in memory, no repository/DB
     * access whatsoever, so this benchmark never depends on plan data or database state. Rebuilt
     * FRESH per chunk (construction is trivially cheap at this size) so every chunk starts from the
     * identical uninitialized state — reusing one instance would make later chunks re-solve an
     * already-converged solution, which hits the converged-churn pathology (m6a-notes.md "Review
     * fix 1" RCA) instead of measuring representative throughput.
     */
    private static GroupPlanSolution buildSyntheticProblem() {
        List<Group> groups = new ArrayList<>();
        for (int g = 1; g <= GROUP_COUNT; g++) {
            int levelMax = 100_000 - (g - 1) * 20_000;
            int levelMin = levelMax - 20_000;
            groups.add(new Group(g, "Bänchmark " + g, g, 5, 8, 10, 1, levelMin, levelMax));
        }

        int[] startMinutes = {990, 1080, 1170, 1260}; // 16:30, 18:00, 19:30, 21:00
        List<TrainingBlock> blocks = new ArrayList<>();
        for (int b = 1; b <= BLOCK_COUNT; b++) {
            TimeKey key = new TimeKey(TimeKey.NO_DATE, 4, startMinutes[b - 1], startMinutes[b - 1] + 90);
            blocks.add(new TrainingBlock(b, 1L, "Bana 1", key, "Block " + b, b));
        }

        List<CoachFact> coaches = new ArrayList<>();
        for (int c = 1; c <= COACH_COUNT; c++) {
            coaches.add(new CoachFact(c, 100L + c, "Coach " + c, 60_000, 0, 100_000, new long[0], 4, new long[0], new long[0]));
        }

        List<PlayerAssignment> players = new ArrayList<>();
        for (int p = 1; p <= PLAYER_COUNT; p++) {
            int levelScaled = 20_000 + (p * 2_700) % 80_000;
            int priority = (p % 5) + 1;
            long[] preferred = (p % 5 == 0) ? new long[] {(p % BLOCK_COUNT) + 1} : new long[0];
            players.add(new PlayerAssignment(
                    (long) p, 200L + p, "Spelare " + p, levelScaled, priority, null, new long[0], preferred, null, false));
        }

        List<GroupSchedule> schedules = new ArrayList<>();
        for (Group g : groups) {
            schedules.add(new GroupSchedule((long) g.id(), g, null, false));
        }

        List<CoachSlot> coachSlots = new ArrayList<>();
        for (Group g : groups) {
            coachSlots.add(new CoachSlot(CoachSlot.syntheticId(g.id(), 0), g, 0, null, false));
        }

        List<PersonPairWish> wishes = List.of(
                new PersonPairWish(1L, WishType.WANT_SAME, 1L, 2L),
                new PersonPairWish(1L, WishType.WANT_SAME, 3L, 4L),
                new PersonPairWish(1L, WishType.MUST_SAME, 5L, 6L),
                new PersonPairWish(1L, WishType.WANT_DIFFERENT, 7L, 8L),
                new PersonPairWish(1L, WishType.WANT_DIFFERENT, 9L, 10L));

        List<CoachWish> coachWishes = List.of(
                new CoachWish(2L, CoachWishType.WANT, 11L, 101L),
                new CoachWish(2L, CoachWishType.WANT, 12L, 102L));

        return new GroupPlanSolution(
                "benchmark-fixed-problem",
                players,
                schedules,
                coachSlots,
                groups,
                blocks,
                coaches,
                wishes,
                coachWishes,
                List.of(),
                LateTimePolicy.DISABLED,
                ConstraintWeightOverrides.none());
    }
}
