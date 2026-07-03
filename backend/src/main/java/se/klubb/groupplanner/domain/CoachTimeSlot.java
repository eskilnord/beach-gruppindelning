package se.klubb.groupplanner.domain;

/**
 * A {@link CoachProfile}'s tri-state availability at one {@link TimeSlot} (spec §7.3
 * availableTimeSlotIds/unavailableTimeSlotIds/preferredTimeSlotIds, normalized into one row per
 * (coach, slot) pair). A time slot with no row for a given coach is neutral/unlisted ("Okänd") and
 * is TREATED AS AVAILABLE for HARD purposes by capacity analysis and the solver — only an explicit
 * {@code UNAVAILABLE} row blocks a coach from a slot (docs/design/04-solver.md). {@code AVAILABLE}
 * is an explicit confirmation, {@code PREFERRED} additionally feeds the coach-preference soft
 * constraint (M6b). Since WI-B, the solver's SOFT layer is no longer blind to the neutral/unlisted
 * case either: {@code CoachFact.availableTimeSlotIds} (the union of {@code AVAILABLE}/{@code
 * PREFERRED} rows) lets the {@code coachUnknownTimeSlot} constraint give a small preference to
 * coaches who filled in their availability over those who left a slot blank.
 */
public record CoachTimeSlot(String id, String coachProfileId, String timeSlotId, String kind) {

    public static final String AVAILABLE = "AVAILABLE";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String PREFERRED = "PREFERRED";

    public static boolean isValidKind(String kind) {
        return AVAILABLE.equals(kind) || UNAVAILABLE.equals(kind) || PREFERRED.equals(kind);
    }
}
