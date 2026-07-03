package se.klubb.groupplanner.solver.domain;

import java.util.Arrays;

/**
 * Immutable problem fact for one {@code coach_profile} row (docs/design/04-solver.md §1.2).
 *
 * <p>{@code unavailableTimeSlotIds} is the ONLY signal the HARD layer consumes — matching the M5
 * semantic decision (backend/docs/m5-notes.md "review fix 2"): neutral/unlisted and explicit
 * {@code AVAILABLE} both still mean "not blocked"; only an explicit {@code UNAVAILABLE} row blocks
 * a coach from a slot (see {@link #availableAt(long)}). The SOFT layer is more nuanced since
 * WI-B: {@code availableTimeSlotIds} (the union of explicit {@code AVAILABLE}/{@code PREFERRED}
 * rows) distinguishes a coach who actually filled in their availability at a slot from one who
 * left it blank — the latter still isn't hard-blocked, but the {@code coachUnknownTimeSlot} SOFT
 * constraint nudges the solver away from it (see {@link #hasKnownAvailabilityAt(long)}).
 *
 * <p>All three arrays are sorted ascending (binary-search lookups).
 *
 * <p>{@code maxGroups} is a single, plan-wide cap — a deliberate simplification of {@code
 * coach_profile}'s separate {@code max_groups_per_day}/{@code max_groups_per_week} columns (see
 * {@code SolverInputAssembler} for the exact fold-down rule and backend/docs/m6a-notes.md for the
 * rationale): every committed fixture/real plan schedules all its blocks on a single day, so a
 * day/week split adds no discriminating power yet and the design's own {@code coachMaxGroups}
 * constraint sketch already assumes one scalar cap.
 */
public record CoachFact(
        long coachProfileId,
        long personId,
        String displayName,
        int coachLevelScaled,
        int canCoachMinScaled,
        int canCoachMaxScaled,
        long[] unavailableTimeSlotIds,
        int maxGroups,
        long[] preferredTimeSlotIds,
        long[] availableTimeSlotIds) {

    public boolean availableAt(long timeSlotId) {
        return Arrays.binarySearch(unavailableTimeSlotIds, timeSlotId) < 0;
    }

    /** M6b addition: backs the {@code coachPreferredTimeSlot} SOFT constraint — the M5 tri-state's
     * {@code PREFERRED} kind, mirroring {@link PlayerAssignment#prefers(long)}. */
    public boolean prefersTimeSlot(long timeSlotId) {
        return Arrays.binarySearch(preferredTimeSlotIds, timeSlotId) >= 0;
    }

    /** WI-B addition: true when this coach has an explicit {@code AVAILABLE} or {@code PREFERRED}
     * row at {@code timeSlotId} — i.e. the coach actually filled in an opinion about this slot,
     * as opposed to leaving it "Okänd" (unlisted). Backs the {@code coachUnknownTimeSlot} SOFT
     * constraint, which only fires when this is false AND {@link #availableAt(long)} is true (an
     * explicit {@code UNAVAILABLE} slot is already a HARD violation via {@code
     * coachAvailabilityHard} and must not be double-punished here). */
    public boolean hasKnownAvailabilityAt(long timeSlotId) {
        return Arrays.binarySearch(availableTimeSlotIds, timeSlotId) >= 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CoachFact other)) {
            return false;
        }
        return coachProfileId == other.coachProfileId
                && personId == other.personId
                && coachLevelScaled == other.coachLevelScaled
                && canCoachMinScaled == other.canCoachMinScaled
                && canCoachMaxScaled == other.canCoachMaxScaled
                && maxGroups == other.maxGroups
                && java.util.Objects.equals(displayName, other.displayName)
                && Arrays.equals(unavailableTimeSlotIds, other.unavailableTimeSlotIds)
                && Arrays.equals(preferredTimeSlotIds, other.preferredTimeSlotIds)
                && Arrays.equals(availableTimeSlotIds, other.availableTimeSlotIds);
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(
                coachProfileId, personId, displayName, coachLevelScaled, canCoachMinScaled, canCoachMaxScaled, maxGroups);
        result = 31 * result + Arrays.hashCode(unavailableTimeSlotIds);
        result = 31 * result + Arrays.hashCode(preferredTimeSlotIds);
        result = 31 * result + Arrays.hashCode(availableTimeSlotIds);
        return result;
    }
}
