package se.klubb.groupplanner.domain;

/**
 * One materialized (person, time, court) usage row belonging to a {@link SavedPlan} (spec §14.3,
 * docs/design/04-solver.md §6.2) — the persisted counterpart of {@code
 * se.klubb.groupplanner.solver.domain.SavedPlanResourceUsage} (same name, different package, same
 * convention already established for {@code PlayerAssignment}: the DB row vs. the solver fact built
 * from it). {@code role} is {@link #ROLE_PLAYER} or {@link #ROLE_COACH}; {@code personId} is the
 * shared {@code person} row (so the SAME person coaching in one plan and playing in another is
 * detectable via a single id, per spec §13.2's "Anna" example). Exactly one of {@code dayOfWeek}/
 * {@code date} is set, mirroring {@link TimeSlot}.
 */
public record SavedPlanResourceUsage(
        String id,
        String savedPlanId,
        String personId,
        String role,
        String dayOfWeek,
        String date,
        String startTime,
        String endTime,
        String courtId) {

    public static final String ROLE_PLAYER = "PLAYER";
    public static final String ROLE_COACH = "COACH";
}
