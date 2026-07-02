package se.klubb.groupplanner.solver.domain;

/**
 * Problem fact configuring the {@code lateTimeTopGroups}/{@code lateTimeBottomGroups} SOFT
 * constraints (docs/design/04-solver.md §1.2, §10.22a/b) — M6b scope. {@code
 * SolverInputAssembler} always supplies {@code enabled=false} this milestone since no M6a
 * constraint consumes this fact; the type exists now purely so {@link GroupPlanSolution}'s field
 * shape matches the design doc from day one.
 */
public record LateTimePolicy(
        boolean enabled, int lateFromMinuteOfDay, int topGroupOrderMax, int bottomGroupOrderMin) {

    public static final LateTimePolicy DISABLED = new LateTimePolicy(false, 0, 0, 0);
}
