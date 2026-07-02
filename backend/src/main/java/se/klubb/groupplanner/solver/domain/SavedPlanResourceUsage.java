package se.klubb.groupplanner.solver.domain;

import se.klubb.groupplanner.common.time.TimeKey;

/**
 * Problem fact modelling a resource (person or court) already committed by another, previously
 * saved/locked activity plan in the same season (docs/design/04-solver.md §1.2/§6.2), consumed by
 * the {@code savedPlanPersonBlocked}/{@code savedPlanCoachBlocked}/{@code savedPlanCourtBlocked}
 * constraints (§10.24a/b/c).
 *
 * <p><b>M6a scope note:</b> this is a placeholder type only. {@code saved_plan}/{@code
 * saved_plan_resource_usage} DB tables don't exist yet (M8 per docs/plan.md); {@code
 * SolverInputAssembler} always supplies an empty list this milestone, so the three constraints above
 * compile, are justified, and are unit-tested against synthetic facts, but never fire against real
 * data until M6b/M8 wires {@code collectSavedPlanUsages} (§6.2) against the real tables.
 */
public record SavedPlanResourceUsage(
        UsageType type, long personId, long courtId, TimeKey timeKey, String sourcePlanName, String sourceDetail) {
}
