package se.klubb.groupplanner.solver.run;

import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import java.time.Duration;

/**
 * Interactive wall-clock termination presets (docs/design/04-solver.md §9.3, spec §16.6): Snabb
 * 10s, Normal 60s, Grundlig 120s. Wall-clock termination is ONLY for interactive user solves
 * (ADR-007) — CI/golden-score tests always use step-count termination instead.
 *
 * <p>Every profile carries an {@code unimprovedSpentLimit} — the design doc's §9.3 table only
 * specified one for NORMAL (PT20S), but the converged-solution pathology documented in
 * backend/docs/m6a-notes.md ("Review fix 1 — RCA") makes an unimproved cutoff a required safety net
 * on every profile: a plan that is already at its optimum (e.g. re-solving right after a successful
 * solve, or a heavily pinned plan) otherwise burns the FULL wall-clock budget churning hundreds of
 * thousands of rejected move selections per local-search step. Measured on the pathological
 * pinned-to-waitlist fixture with NO_ASSERT: FAST terminates at ~5.0 s via the unimproved limit
 * instead of the full 10 s.
 */
public enum SolveProfile {
    FAST(Duration.ofSeconds(10), Duration.ofSeconds(5)),
    NORMAL(Duration.ofSeconds(60), Duration.ofSeconds(20)),
    THOROUGH(Duration.ofSeconds(120), Duration.ofSeconds(30)),
    /**
     * v0.2.0 (SUGGESTED OPTIMIZATION TIME): a user-chosen {@code durationSeconds} (validated {@link
     * #CUSTOM_MIN_SECONDS}..{@link #CUSTOM_MAX_SECONDS} at the call site, see {@code
     * SolveController#solve}/{@code SolveCoordinator#startSolve}) instead of one of the three fixed
     * presets above — the whole point of the companion {@code suggest-duration} endpoint is to
     * propose a number instead of forcing a pick from {FAST,NORMAL,THOROUGH}. The {@link Duration}
     * constructor args here are unused placeholders ({@link Duration#ZERO}): {@link
     * #terminationConfig()}/{@link #limitMs()} deliberately throw for this constant — every caller
     * must go through {@link #customTerminationConfig(int)}/{@link #customLimitMs(int)} instead, so
     * a duration can never be silently forgotten.
     */
    CUSTOM(Duration.ZERO, Duration.ZERO);

    /** Validation bounds for {@code CUSTOM}'s {@code durationSeconds} (v0.2.0 task spec: "validated
     * 10..900; 400 outside"). */
    public static final int CUSTOM_MIN_SECONDS = 10;
    public static final int CUSTOM_MAX_SECONDS = 900;

    /** Safety-net cap on {@code CUSTOM}'s proportional {@code unimprovedSpentLimit} — every profile
     * needs one (m6a-notes.md "Review fix 1" RCA: an already-optimal/heavily-pinned plan otherwise
     * burns the full wall-clock budget on rejected move selections); for a preset profile this is a
     * fixed fraction of the preset (see the three constants above), but {@code CUSTOM}'s duration is
     * unbounded up to 900s, so a flat fraction (e.g. half) would let a 900s solve churn for 450s on a
     * converged plan. Capped at 120s — the same order of magnitude as THOROUGH's own 30s cap scaled
     * up, still well short of ever dominating the total budget. */
    private static final int CUSTOM_UNIMPROVED_CAP_SECONDS = 120;

    private final Duration spentLimit;
    private final Duration unimprovedSpentLimit;

    SolveProfile(Duration spentLimit, Duration unimprovedSpentLimit) {
        this.spentLimit = spentLimit;
        this.unimprovedSpentLimit = unimprovedSpentLimit;
    }

    public TerminationConfig terminationConfig() {
        if (this == CUSTOM) {
            throw new IllegalStateException("CUSTOM has no fixed termination - use customTerminationConfig(int)");
        }
        return new TerminationConfig().withSpentLimit(spentLimit).withUnimprovedSpentLimit(unimprovedSpentLimit);
    }

    public long limitMs() {
        if (this == CUSTOM) {
            throw new IllegalStateException("CUSTOM has no fixed limit - use customLimitMs(int)");
        }
        return spentLimit.toMillis();
    }

    /** {@code CUSTOM}'s per-request termination: {@code durationSeconds} as the hard wall-clock
     * cap, {@code min(durationSeconds/2, 120)}s as the unimproved-cutoff safety net (documented on
     * {@link #CUSTOM_UNIMPROVED_CAP_SECONDS}). Caller must have already validated the range via
     * {@link #requireValidCustomDuration(Integer)}. */
    public static TerminationConfig customTerminationConfig(int durationSeconds) {
        Duration spent = Duration.ofSeconds(durationSeconds);
        Duration unimproved = Duration.ofSeconds(Math.min(durationSeconds / 2, CUSTOM_UNIMPROVED_CAP_SECONDS));
        return new TerminationConfig().withSpentLimit(spent).withUnimprovedSpentLimit(unimproved);
    }

    public static long customLimitMs(int durationSeconds) {
        return durationSeconds * 1000L;
    }

    /** 400s unless {@code durationSeconds} is present and within {@link #CUSTOM_MIN_SECONDS}..
     * {@link #CUSTOM_MAX_SECONDS} inclusive — the single source of truth for the CUSTOM profile's
     * validation, called both by {@code SolveController} (fail fast on the HTTP request) and
     * defensively re-checked by {@code SolveCoordinator} (matching this codebase's established
     * defensive-recheck convention, e.g. {@code SolverInputAssembler}'s reserved-MEDIUM guardrail). */
    public static void requireValidCustomDuration(Integer durationSeconds) {
        if (durationSeconds == null || durationSeconds < CUSTOM_MIN_SECONDS || durationSeconds > CUSTOM_MAX_SECONDS) {
            throw new se.klubb.groupplanner.api.error.BadRequestException(
                    "CUSTOM profile requires durationSeconds between " + CUSTOM_MIN_SECONDS + " and " + CUSTOM_MAX_SECONDS
                            + " (got " + durationSeconds + ")");
        }
    }

    public static SolveProfile fromString(String value) {
        if (value == null || value.isBlank()) {
            return NORMAL;
        }
        try {
            return SolveProfile.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new se.klubb.groupplanner.api.error.BadRequestException(
                    "Unknown solve profile '" + value + "' - expected FAST|NORMAL|THOROUGH|CUSTOM");
        }
    }
}
