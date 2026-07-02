package se.klubb.groupplanner.domain;

/**
 * A {@link Person}'s coaching record within one {@link ActivityPlan} (spec §7.3). At most one row
 * exists per (personId, activityPlanId) pair. Full CRUD (availability, level fit, ...) arrives in
 * M5 (Tränarvyn); this record and its minimal repository exist starting M3 only so the import
 * wizard's "isCoach" mapping target (docs/plan.md red-team correction: "coach import: add a coach
 * mapping target ... spec §13.1 allows importing coaches") has somewhere to write imported coaches.
 */
public record CoachProfile(
        String id,
        String personId,
        String activityPlanId,
        Double coachLevel,
        Double canCoachMinLevel,
        Double canCoachMaxLevel,
        Integer maxGroupsPerDay,
        Integer maxGroupsPerWeek,
        boolean canAlsoTrainAsParticipant,
        String notes) {
}
