package se.klubb.groupplanner.domain;

/**
 * A training group within one {@link ActivityPlan} (spec §7.4) — "group" is a SQL keyword, hence
 * the table name {@code training_group}. Created/updated by {@code
 * se.klubb.groupplanner.solver.assemble.GroupGenerator} (M6a, docs/design/04-solver.md §7);
 * {@code groupOrder} is the solver's own deterministic long id for this group (1 = highest level) —
 * see {@code SolverInputAssembler}'s id-mapping note.
 */
public record TrainingGroup(
        String id,
        String activityPlanId,
        String name,
        Integer groupOrder,
        String category,
        Integer minSize,
        Integer targetSize,
        Integer maxSize,
        int requiredCoachCount,
        Double levelMin,
        Double levelMax,
        String assignedTrainingBlockId,
        boolean locked) {
}
