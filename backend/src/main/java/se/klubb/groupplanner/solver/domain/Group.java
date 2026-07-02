package se.klubb.groupplanner.solver.domain;

/**
 * Immutable problem fact for one {@code training_group} row (docs/design/04-solver.md §1.1/§1.2).
 * Static attributes only — the {@link GroupSchedule}/{@link CoachSlot}/{@link PlayerAssignment}
 * planning entities reference a {@code Group} but never mutate it.
 *
 * <p>{@code id} is the solver's deterministic {@code long} id for this group, which — unlike every
 * other fact in this package — is set equal to {@code groupOrder} rather than derived from a sorted
 * index over the DB's UUID primary key: {@code groupOrder} is already a stable, plan-unique,
 * deterministic integer assigned by {@code GroupGenerator}, so reusing it avoids an extra indirection
 * and doubles as a human-readable id in justifications/logs (see {@code SolverInputAssembler} for the
 * full id-mapping rationale, a deviation from this design doc's "all ids are long DB ids" assumption
 * forced by the DB's actual TEXT UUIDv7 primary keys).
 */
public record Group(
        long id,
        String name,
        int groupOrder,
        int minSize,
        int targetSize,
        int maxSize,
        int requiredCoachCount,
        int levelMinScaled,
        int levelMaxScaled) {
}
