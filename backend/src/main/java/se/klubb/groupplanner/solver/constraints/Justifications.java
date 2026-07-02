package se.klubb.groupplanner.solver.constraints;

import ai.timefold.solver.core.api.score.stream.ConstraintJustification;

/**
 * Typed justification records for every constraint in {@link GroupPlanConstraintProvider}
 * (docs/design/04-solver.md §4). Every record carries entity ids (never object references) so it
 * survives serialization into an explanation JSON payload (M7) — matching the design doc's own
 * note: "every justification record carries entity ids... so records survive serialization".
 *
 * <p>Kept package-private and grouped in one file (rather than 13 separate public top-level types)
 * for lower ceremony; only {@link GroupPlanConstraintProvider} and its tests reference them.
 */
final class Justifications {

    private Justifications() {
    }

    /** 10.1 trainingBlockCapacity: two groups double-booked onto the same training block. */
    record BlockDoubleBookedJustification(long blockId, long groupIdA, long groupIdB) implements ConstraintJustification {
    }

    /** 10.3 groupMaxSizeHard: a group exceeds its configured maxSize. */
    record GroupOverMaxJustification(long groupId, int size, int maxSize) implements ConstraintJustification {
    }

    /** 10.9 timeAvailabilityHard: a player placed at a time slot they marked unavailable. */
    record TimeUnavailableJustification(long participantId, long groupId, long timeSlotId, String label)
            implements ConstraintJustification {
    }

    /** 10.11/10.13 sameGroupHard/differentGroupHard: a MUST_SAME/MUST_DIFFERENT wish broken. */
    record PairWishBrokenJustification(long wishFieldId, long aParticipantId, long bParticipantId, String type)
            implements ConstraintJustification {
    }

    /** 10.15 coachNoOverlap: one coach double-booked onto two overlapping group sessions. */
    record CoachDoubleBookedJustification(long coachPersonId, long groupIdA, long groupIdB, String timeLabel)
            implements ConstraintJustification {
    }

    /** 10.16 playerNoOverlap: one person (via two PlayerAssignments) double-booked in-plan. */
    record PlayerDoubleBookedJustification(long personId, long groupIdA, long groupIdB) implements ConstraintJustification {
    }

    /** 10.17 coachCannotTrainAndCoachSameTime: a person coaches and plays at an overlapping time. */
    record TrainAndCoachClashJustification(long personId, long coachGroupId, long playGroupId, String timeLabel)
            implements ConstraintJustification {
    }

    /** 10.18 coachAvailabilityHard: a coach assigned to a group session they are unavailable for. */
    record CoachUnavailableJustification(long coachPersonId, long groupId, long timeSlotId) implements ConstraintJustification {
    }

    /** 10.19 coachRequirementHard: a required coach slot left unfilled. */
    record MissingCoachJustification(long groupId, int slotIndex) implements ConstraintJustification {
    }

    /** coachMaxGroups: a coach assigned to more groups than their configured cap. */
    record CoachOverloadedJustification(long coachPersonId, int groups, int maxGroups) implements ConstraintJustification {
    }

    /** 10.21b/10.21c coachWishRequired/coachWishForbidden: a MUST/CANNOT coach wish violated.
     * {@code fulfilled} disambiguates the two directions: true = the forbidden coach IS present
     * (CANNOT violated), false = the required coach is ABSENT (MUST violated). */
    record CoachWishJustification(long participantId, long coachPersonId, String type, boolean fulfilled)
            implements ConstraintJustification {
    }

    /** 10.24a/b/c savedPlanPersonBlocked/CoachBlocked/CourtBlocked. {@code kind} is PLAYER|COACH|COURT;
     * {@code resourceId} is the clashing personId (PLAYER/COACH) or courtId (COURT). */
    record SavedPlanClashJustification(long groupId, long resourceId, String sourcePlanName, String timeLabel, String kind)
            implements ConstraintJustification {
    }

    /** unassignedPlayer: a player left on the waitlist (medium-level penalty, §2.1). */
    record UnassignedPlayerJustification(long participantId, String displayName, int priority)
            implements ConstraintJustification {
    }
}
