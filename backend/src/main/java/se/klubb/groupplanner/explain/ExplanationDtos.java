package se.klubb.groupplanner.explain;

import java.util.List;

/**
 * Response DTOs for the M7 explain/what-if REST surface (docs/design/04-solver.md §14.2 — every
 * shape below matches that section's example JSON verbatim, field for field). Grouped in one file
 * (mirroring {@code se.klubb.groupplanner.solver.constraints.Justifications}'s own "lower ceremony"
 * choice) since only {@code ExplanationService}/{@code WhatIfService} and their controllers reference
 * these.
 *
 * <p>Every response record starts with the SAME four staleness-envelope fields (design §11.6):
 * {@code runId, basedOnRevision, currentRevision, stale} — "Staleness envelope on ALL responses"
 * per this milestone's brief.
 */
public final class ExplanationDtos {

    private ExplanationDtos() {
    }

    // ─────────────────────────────────────────────────────────────────── shared

    public record ScoreDeltaView(long hard, long medium, long soft) {
    }

    public record ConstraintMessageView(String key, String messageSv) {
    }

    public record FactorView(String messageSv) {
    }

    public record AppliedWeightView(String key, String label, String level, long weight) {
    }

    // ─────────────────────────────────────────────────────────────────── person level

    /** {@code unassignedFriendParticipantProfileId} is set only for the "waitlisted-friend edge"
     * (docs/design/05-solver-verification.md minor finding, amendment (c)): the wished person is
     * themselves unassigned, so Timefold produces no match at all to report (a broken/satisfied
     * {@code sameGroupHard/Soft} match requires BOTH sides to have a non-null group — {@code
     * forEach(PlayerAssignment)}'s own null-filtering semantics) — the id lets the frontend link
     * straight to that person's own waitlist explanation ("Lisa är oplacerad (kölista)").
     *
     * <p>{@code coachBindingSv} is v0.3.0 WI-5's second-order annotation (user feedback: "beror det
     * på att en annan spelare påverkas av en tränare?"): set only for a broken {@code MUST_SAME}/
     * {@code WANT_SAME} pair wish whose OTHER participant is themselves placed (in a different
     * group) and tied to THAT group via their own MUST/WANT coach wish — i.e. the finished Swedish
     * reason the wish couldn't be honored ("Lisa är knuten till Grupp 3 via tränare Anna (måste ha
     * tränare)"), null in every other case (the common one). */
    public record BrokenWishView(
            String key, String withPerson, long weightApplied, String messageSv,
            String unassignedFriendParticipantProfileId, String coachBindingSv) {
    }

    /** {@code origin} entries: {@code FRIEND_WISH|FRIEND_VIA_COACH|COACH_WISH|PREVIOUS_GROUP|
     * TOP_SCORE} (design §11.3's union-rule labels — a candidate can carry more than one, e.g. both a
     * friend AND the previous group). {@code FRIEND_VIA_COACH} (v0.3.0 WI-5) is added ALONGSIDE
     * {@code FRIEND_WISH} — never instead of it — whenever the friend's own placement in that
     * candidate group is itself explained by a MUST/WANT coach wish tied to that group's CoachSlot.
     * {@code verdict} values: {@code WOULD_BREAK_HARD|BETTER|NEUTRAL|WORSE} — NEUTRAL (an
     * exactly-zero score diff, "påverkar inte totalpoängen") is an M7-review extension of design
     * §11.3's original three-value enum; frontends must treat it as a fourth first-class value. */
    public record AlternativeGroupView(
            String groupId,
            String name,
            List<String> origin,
            String verdict,
            ScoreDeltaView scoreDelta,
            List<ConstraintMessageView> newlyBroken,
            List<ConstraintMessageView> newlyFixed,
            String narrativeSv) {
    }

    /** v0.3.0 WI-5 second-order factor (user feedback: "Förklaringen av varför en spelare blev
     * tilldelad en grupp bör även visa om det beror på att en annan spelare påverkas av en
     * tränare"): placed player X is (partly) in their group because a wished-for playing partner
     * ({@code otherPersonName}, MUST_SAME/WANT_SAME) is themselves ALSO in that group only because
     * of their own {@code coachWishType} (MUST/WANT) coach wish for {@code coachPersonName}, who is
     * assigned to the group via its CoachSlot. Built directly from {@code PersonPairWish}/{@code
     * CoachWish}/{@code CoachSlot} facts (there is no Timefold match type for a two-hop chain like
     * this — see {@code ExplanationService#buildIndirectFactors}); {@code messageSv} is the finished
     * Swedish sentence, rendered server-side like every other factor in this API (mirrors {@link
     * BrokenWishView}'s finished-text-plus-id pattern so the frontend never needs its own MUST/WANT
     * copy). */
    public record IndirectFactorView(
            String otherParticipantProfileId,
            String otherPersonName,
            String coachPersonName,
            String coachWishType,
            String groupName,
            String messageSv) {
    }

    public record SelectedGroupView(
            String groupId, String name, int size, Integer targetSize, Integer maxSize, String levelMeanSv, Integer levelSpread) {
    }

    public record WaitlistBlockerView(String groupId, String name, String blockerSv) {
    }

    /** Amendment (a)/(c) of docs/design/05-solver-verification.md: the waitlist branch never derives
     * its verdict from the single-move probe alone (mathematically it would only ever say
     * WOULD_BREAK_HARD/BETTER, per the verifier's fix) — {@code perGroupBlockers} carries the concrete
     * hard blocker or data-derived priority narrative per group instead, and {@code qualityWarningSv}
     * is set only in the "förbättring möjlig" branch (a feasible non-full candidate slipped through,
     * logged as a solver-quality warning, never presented as a priority explanation). */
    public record WaitlistView(String reasonSv, List<WaitlistBlockerView> perGroupBlockers, String qualityWarningSv) {
    }

    public record PersonExplanationResponse(
            String runId,
            int basedOnRevision,
            int currentRevision,
            boolean stale,
            String participantProfileId,
            String name,
            SelectedGroupView selectedGroup,
            List<FactorView> positiveFactors,
            List<FactorView> negativeFactors,
            List<BrokenWishView> brokenWishes,
            List<AppliedWeightView> appliedWeights,
            List<AlternativeGroupView> alternatives,
            List<IndirectFactorView> indirectFactors,
            WaitlistView waitlist) {
    }

    // ─────────────────────────────────────────────────────────────────── group level

    public record GroupCoachView(String coachProfileId, String name) {
    }

    public record GroupBlockView(String trainingBlockId, String label) {
    }

    public record GroupMatchView(String key, String messageSv, ScoreDeltaView scoreImpact) {
    }

    public record GroupMemberBrokenWishView(String participantProfileId, String name, String messageSv) {
    }

    public record GroupExplanationResponse(
            String runId,
            int basedOnRevision,
            int currentRevision,
            boolean stale,
            String groupId,
            String name,
            int size,
            Integer targetSize,
            Integer maxSize,
            String levelMeanSv,
            Integer levelSpread,
            GroupCoachView coach,
            GroupBlockView block,
            List<String> warnings,
            List<GroupMatchView> matches,
            List<GroupMemberBrokenWishView> membersWithBrokenWishes) {
    }

    // ─────────────────────────────────────────────────────────────────── plan level

    public record ConstraintSummaryView(String key, String label, String level, long weightApplied, long scoreTotal, int matchCount) {
    }

    public record HardViolationView(String key, String messageSv) {
    }

    public record WaitlistEntryView(String participantProfileId, String name, int priority, String reasonSv) {
    }

    public record ProblematicGroupView(String groupId, String name, long penaltySum) {
    }

    public record ManualReviewEntryView(String participantProfileId, String name, String reasonSv) {
    }

    public record PlanExplanationResponse(
            String runId,
            int basedOnRevision,
            int currentRevision,
            boolean stale,
            ScoreDeltaView score,
            boolean feasible,
            List<ConstraintSummaryView> constraintSummaries,
            List<HardViolationView> hardViolations,
            List<WaitlistEntryView> waitlist,
            List<ProblematicGroupView> problematicGroups,
            List<ManualReviewEntryView> manualReview) {
    }

    // ─────────────────────────────────────────────────────────────────── what-if

    public record GroupSizeChangeView(String groupId, String name, int from, int to, Integer max) {
    }

    public record LevelSpreadChangeView(String groupId, String name, int from, int to) {
    }

    public record WhatIfMoveResponse(
            String runId,
            int basedOnRevision,
            int currentRevision,
            boolean stale,
            ScoreDeltaView scoreDelta,
            boolean wouldBreakHard,
            List<GroupSizeChangeView> groupSizeChanges,
            List<LevelSpreadChangeView> levelSpreadChanges,
            List<ConstraintMessageView> newlyBroken,
            List<ConstraintMessageView> newlyFixed,
            List<String> suggestedActions) {
    }

    public record WhatIfWhyNotResponse(
            String runId, int basedOnRevision, int currentRevision, boolean stale, AlternativeGroupView alternative) {
    }
}
