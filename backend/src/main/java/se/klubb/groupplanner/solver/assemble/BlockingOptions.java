package se.klubb.groupplanner.solver.assemble;

/**
 * The §14.4 cross-plan blocking checkboxes (spec §14.4, docs/design/04-solver.md §6.2), carried on a
 * solve request and consumed by {@code SolverInputAssembler#collectSavedPlanUsages}:
 *
 * <ul>
 *   <li>{@code blockPlayers} — locked-plan PLAYER usages become {@code PERSON} facts (blocks
 *       {@code savedPlanPersonBlocked}, §10.24a).
 *   <li>{@code blockCoaches} — locked-plan COACH usages become {@code PERSON} facts too (this is
 *       what makes §10.17's cross-plan case hold: a coach in one plan blocks that same person from
 *       playing in another, spec §13.2's Anna example).
 *   <li>{@code blockCourts} — every usage's court becomes a {@code COURT} fact (blocks {@code
 *       savedPlanCourtBlocked}, §10.24c).
 *   <li>{@code conflictsAsWarnings} — "Visa konflikter men tillåt ändå": usages are still emitted
 *       (so matches/explanations exist), but the three {@code savedPlan*} HARD constraints are
 *       downgraded to {@code ofSoft(1)} for this solve only (never persisted) — see {@code
 *       SolverInputAssembler#buildConstraintWeightOverrides}.
 * </ul>
 */
public record BlockingOptions(boolean blockPlayers, boolean blockCoaches, boolean blockCourts, boolean conflictsAsWarnings) {

    public static final BlockingOptions NONE = new BlockingOptions(false, false, false, false);

    public boolean anyBlockingEnabled() {
        return blockPlayers || blockCoaches || blockCourts;
    }
}
