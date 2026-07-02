package se.klubb.groupplanner.solver.domain;

/**
 * Problem fact for one "this participant wants/must (not) have this coach" wish
 * (docs/design/04-solver.md §1.2), sourced from a {@code coachRelation} custom field value (the
 * seeded standard fields {@code wantsCoach}/{@code mustHaveCoach}/{@code cannotHaveCoach}, or any
 * council-created custom field of the same shape).
 *
 * <p>{@code coachPersonId} is the solver's long id for the wished-for coach's underlying
 * {@code person} row (matching {@link CoachFact#personId()}) — NOT the coach profile id — because a
 * {@code coachRelation} field value stores a {@code coach_profile} id, which the assembler resolves
 * to that profile's person before building this fact.
 */
public record CoachWish(long fieldDefinitionId, CoachWishType type, long participantProfileId, long coachPersonId) {
}
