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
    THOROUGH(Duration.ofSeconds(120), Duration.ofSeconds(30));

    private final Duration spentLimit;
    private final Duration unimprovedSpentLimit;

    SolveProfile(Duration spentLimit, Duration unimprovedSpentLimit) {
        this.spentLimit = spentLimit;
        this.unimprovedSpentLimit = unimprovedSpentLimit;
    }

    public TerminationConfig terminationConfig() {
        return new TerminationConfig().withSpentLimit(spentLimit).withUnimprovedSpentLimit(unimprovedSpentLimit);
    }

    public long limitMs() {
        return spentLimit.toMillis();
    }

    public static SolveProfile fromString(String value) {
        if (value == null || value.isBlank()) {
            return NORMAL;
        }
        try {
            return SolveProfile.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new se.klubb.groupplanner.api.error.BadRequestException(
                    "Unknown solve profile '" + value + "' - expected FAST|NORMAL|THOROUGH");
        }
    }
}
