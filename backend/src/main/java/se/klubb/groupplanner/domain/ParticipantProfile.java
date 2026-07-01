package se.klubb.groupplanner.domain;

/**
 * A {@link Person}'s participation record within one {@link ActivityPlan} (spec §7.2). At most one
 * row exists per (personId, activityPlanId) pair.
 *
 * <p>{@code importedComment}/{@code internalNote} are sensitive free text (CLAUDE.md confidentiality
 * rules): information only, and callers building solver input, {@code *_json} snapshots, or default
 * exports must never read these two fields for that purpose.
 */
public record ParticipantProfile(
        String id,
        String personId,
        String activityPlanId,
        Double rankingPoints,
        String rankingSource,
        String previousGroupName,
        Double previousGroupLevel,
        Double estimatedLevel,
        Double levelConfidence,
        Double manualLevelScore,
        String importedComment,
        String internalNote,
        boolean manualReviewFlag,
        boolean waitlisted) {
}
