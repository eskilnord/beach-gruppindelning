package se.klubb.groupplanner.solver.domain;

/**
 * Problem fact for one directed "these two participants should/must (not) share a group" wish
 * (docs/design/04-solver.md §1.2), sourced from a {@code personRelation} custom field value (the
 * seeded standard fields {@code mustPlayWith}/{@code playWith}/{@code avoidPlayWith}/{@code
 * mustNotPlayWith}, or any council-created custom field of the same shape).
 *
 * <p>{@code fieldDefinitionId} is the solver's long id for the owning field (see {@code
 * SolverInputAssembler}) — carried through into justifications so an explanation can name which
 * field produced the match, not just "a personRelation wish".
 */
public record PersonPairWish(
        long fieldDefinitionId, WishType type, long aParticipantProfileId, long bParticipantProfileId) {
}
