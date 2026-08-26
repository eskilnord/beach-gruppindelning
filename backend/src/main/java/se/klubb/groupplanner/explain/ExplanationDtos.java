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

    /** {@code timeLabelSv} (M-E2) is the group's scheduled time, Swedish-labelled ("torsdag 19.30")
     * via the same {@code SolutionIndex.timeSlotLabel} lookup every other time-bearing sentence in
     * this API already uses — {@code null} only when the group has no assigned training block. */
    public record SelectedGroupView(
            String groupId, String name, int size, Integer targetSize, Integer maxSize, String levelMeanSv,
            Integer levelSpread, String timeLabelSv) {
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

    /**
     * M-E3 "vad skulle krävas?": for a {@code TRADE_OFF} unmet wish, whether — and how — reordering
     * the plan's four priorities ({@link se.klubb.groupplanner.fields.PriorityOrder.Priority}) would
     * make the wish's own move stop costing points, computed via {@link PrioritySensitivityCalculator}
     * from the SAME probe data {@code CausalNarrator} already has (zero extra {@code analyze()} calls
     * — pure arithmetic over the M-E1 linearity spike's {@code Δscore(w') = Σ units_k·w'_k} result).
     *
     * <p>Null-safety contract ({@code NoClaimWithoutProbeTest} pins this): {@code available=false} ⇒
     * every other field is {@code null}. {@code available=true} ⇒ {@code unavailableReasonSv} is
     * {@code null} and {@code verdict}/{@code summarySv} are non-null. {@code verdict ==
     * "FLIPS_BY_REORDER"} ⇒ {@code suggestedOrder} is non-null and NOT equal to the plan's current
     * order, and {@code cautionSv} is non-null (MANDATORY — every "this would help" claim is scoped to
     * THIS move, never a claim about what the solver would actually choose on a full re-solve).
     * {@code verdict} is one of {@code FLIPS_BY_REORDER|NO_ORDER_HELPS|ALREADY_TOP}, {@code null} when
     * {@code available=false}. {@code blockerLabelSv} is non-null only for {@code NO_ORDER_HELPS}/
     * {@code ALREADY_TOP} (names the dominant reason no reorder is enough). {@code suggestedOrder} is
     * the {@link se.klubb.groupplanner.fields.PriorityOrder.Priority} names in rank-1-first order —
     * the exact shape {@code PUT /api/plans/{planId}/priority-order} accepts, so the frontend can offer
     * "apply this order" directly.
     *
     * <p>{@code available=false} is honest for every OTHER outcome too (LOCKED/NO_CANDIDATE/
     * BLOCKED_HARD/EQUAL/SOLVER_MISS/INCONCLUSIVE — none of these name a "would a reorder help"
     * question that is even meaningful), each with its own {@code unavailableReasonSv}, and for
     * {@code TRADE_OFF} itself when the plan uses custom (non-ladder) weights or a bucket constraint
     * the move touches is disabled in this plan (units unknown — see {@link
     * PrioritySensitivityCalculator}).
     */
    public record PrioritySensitivityView(
            boolean available,
            String unavailableReasonSv,
            String verdict,
            List<String> suggestedOrder,
            String summarySv,
            String cautionSv,
            String blockerLabelSv) {
    }

    /** Advanced-mode closed-form per-constraint break-even ({@link WeightBreakEven}, M-E3 "vad skulle
     * krävas?" for someone editing raw weights instead of the four-priority order): the weight {@code
     * key} would need to cross (holding every other constraint's CURRENT weight fixed) for the same
     * probe's move to stop costing points. {@code direction} is {@code AT_MOST} (the constraint's cost
     * grows with its weight — lower it) or {@code AT_LEAST} (the constraint's gain grows with its
     * weight — raise it). {@code threshold} is the clamped ({@code WeightLimits} 1..10 000) integer
     * weight achieving break-even, {@code null} exactly when {@code impossibleReasonSv} is set (no
     * weight in the allowed range would do it). {@code messageSv}/{@code impossibleReasonSv} are
     * finished Swedish sentences, server-rendered like every other message field in this API. Rows
     * with {@code units_k == 0} (the move never touched this constraint) are omitted entirely. */
    public record WeightBreakEvenView(
            String key,
            String labelSv,
            long currentWeight,
            String direction,
            Integer threshold,
            String messageSv,
            String impossibleReasonSv) {
    }

    /** One of the 24 permutations of the four {@link se.klubb.groupplanner.fields.PriorityOrder
     * .Priority} values, and what it would predict for this wish's move (M-E3 advanced-mode {@code
     * /wish-analysis}) — {@code orderKeys} in rank-1-first order (same shape as {@code
     * PrioritySensitivityView.suggestedOrder}), {@code predictedSoftDelta} the exact {@code
     * Σ units_k·w'_k} SOFT-level total under that permutation (never HARD/MEDIUM — a {@code TRADE_OFF}
     * candidate is hard-feasible by construction and medium is unreachable in a group-to-group probe,
     * see {@link PrioritySensitivityCalculator}), {@code nonWorse} = {@code predictedSoftDelta >= 0}. */
    public record OrderingView(List<String> orderKeys, boolean nonWorse, long predictedSoftDelta) {
    }

    /** M-E3 lazy "wish analysis" drawer ({@code GET .../wish-analysis?wish={wishId}}, simple mode's
     * {@link PrioritySensitivityView} plus advanced mode's full {@link WeightBreakEvenView} rows and
     * every one of the 24 {@link OrderingView} permutations) — same staleness envelope as every other
     * explanation response. {@code breakEven}/{@code orderings} are empty (never fabricated) for a wish
     * whose outcome isn't {@code TRADE_OFF}; {@code unavailableReasonSv} then carries that outcome's own
     * honest reason (the same text {@link UnmetWishView#prioritySensitivity()} would carry).
     *
     * <p><b>Mixed state (FIX 6, M-E3 review)</b>: for a {@code TRADE_OFF} wish whose {@code
     * PrioritySensitivityCalculator} sensitivity is itself {@code available=false} (custom weights, or a
     * bucket constraint the move touches is disabled), {@code unavailableReasonSv} is non-null and
     * {@code orderings} is empty — but {@code breakEven} is computed independently ({@link
     * WeightBreakEven} has no dependency on the 24-permutation sensitivity at all) and MAY still be
     * non-empty. In that case {@code unavailableReasonSv} scopes ONLY the {@code orderings}/priority-
     * reorder question ("would reordering the four priorities help") — {@code breakEven}'s per-constraint
     * weight thresholds stand on their own and remain a fully valid, independently truthful answer to
     * the advanced-mode "what raw weight would it take" question. Frontends must not suppress
     * {@code breakEven} just because {@code unavailableReasonSv} is set.
     *
     * <p>{@code cautionSv} (FIX 2, M-E3 review — MANDATORY on this payload, mirroring {@link
     * PrioritySensitivityView#cautionSv()}'s own mandatory-on-FLIPS_BY_REORDER contract): set to {@link
     * PrioritySensitivityCalculator#CAUTION_SV} whenever this response contains any concrete "this would
     * help" claim at all — i.e. whenever {@code breakEven} or {@code orderings} is non-empty — {@code
     * null} only when BOTH are empty (nothing here to scope a caution onto). Every claim in this payload
     * is, like {@link PrioritySensitivityView}'s, scoped to THIS specific move, never a claim about what
     * a full re-solve would actually choose. */
    public record WishAnalysisResponse(
            String runId,
            int basedOnRevision,
            int currentRevision,
            boolean stale,
            String wishId,
            List<WeightBreakEvenView> breakEven,
            List<OrderingView> orderings,
            String unavailableReasonSv,
            String cautionSv) {
    }

    /** One competing reason a {@link UnmetWishView}'s best candidate would cost (M-E2 {@code
     * CausalNarrator}'s TRADE_OFF aggregation): {@code key}/{@code label} from {@link
     * ConstraintMetadata}, {@code messageSv} a reused, already-rendered {@link
     * se.klubb.groupplanner.explain.JustificationMessages} sentence (never invented text),
     * {@code scoreImpact} the summed {@code |primary score component|} this key would cost (always
     * &gt;= 0), {@code sharePercent} = {@code floor(100 * scoreImpact / totalNegative)} across every
     * competing key for that same candidate. */
    public record ConstraintReasonView(String key, String label, String messageSv, long scoreImpact, int sharePercent) {
    }

    /**
     * M-E2 "därför-meningen": one wish {@code target} did NOT get, with a truthful, probe-derived
     * causal narrative for why (docs/design/04-solver.md §17.4's "aldrig gissningar" rule — {@code
     * CausalNarrator} enforces this in code, not just by convention).
     *
     * <p>{@code wishId} is {@code TIME|FRIEND:{id}|AVOID:{id}|PREVGROUP|COACH:{id}} ({@code {id}} the
     * SOLVER-internal long id of the other participant/coach person, an opaque grouping key — never
     * exposed as a DB id elsewhere in this API). {@code bucket} is {@link
     * se.klubb.groupplanner.fields.PriorityOrder.Priority#name()} when {@code key} belongs to one of
     * the four priority-order families, else {@code null}. {@code outcome} is one of {@code
     * LOCKED|NO_CANDIDATE|BLOCKED_HARD|TRADE_OFF|EQUAL|SOLVER_MISS|INCONCLUSIVE} — the last a safety
     * net for the (never-truthfully-claimable) case where the TRADE_OFF self-check invariant fails,
     * logged as a WARN and surfaced with an honest "couldn't determine" {@code primaryReasonSv} rather
     * than a fabricated causal claim (M-E2 review fix: {@code primaryReasonSv} is non-null for EVERY
     * outcome, including {@code INCONCLUSIVE}, and — M-E2 review fix, staleness — is PREFIXED with a
     * stale-run notice whenever the response's {@code stale} flag is true, for every outcome, not just
     * {@code SOLVER_MISS}). {@code hedgeSv} (M-E2 review fix, scope honesty) is non-null on {@code
     * LOCKED}/{@code NO_CANDIDATE}/{@code TRADE_OFF}/{@code EQUAL} and {@code null} on {@code
     * BLOCKED_HARD}/{@code SOLVER_MISS}/{@code INCONCLUSIVE}. {@code bestCandidateGroupId}/{@code
     * bestCandidateDelta} are non-null ONLY for {@code TRADE_OFF}/{@code EQUAL}/{@code SOLVER_MISS}
     * (the three outcomes that name a specific "best candidate" at all) — {@code competingReasons} is
     * non-empty ONLY for {@code TRADE_OFF}.
     */
    public record UnmetWishView(
            String wishId,
            String key,
            String bucket,
            String wishSv,
            String outcome,
            String primaryReasonSv,
            String hedgeSv,
            List<String> candidateGroupIds,
            String bestCandidateGroupId,
            ScoreDeltaView bestCandidateDelta,
            List<ConstraintReasonView> competingReasons,
            PrioritySensitivityView prioritySensitivity) {
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
            WaitlistView waitlist,
            String placementSummarySv,
            String lockedNoticeSv,
            List<UnmetWishView> unmetWishes) {
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

    // ─────────────────────────────────────────────────────────────────── improvement suggestions

    /**
     * One "small data change would unlock a big improvement" suggestion ({@link
     * ImprovementSuggestionService}, WI-D — user feedback v0.4 #2). {@code kind} is one of {@code
     * PLAYER_TIME | GROUP_MAX | COACH_TIME | COACH_MAX | GROUP_MAX_WISH | PLAYER_TIME_WISH}.
     * {@code titleSv}/{@code detailSv}/{@code impactSv} are finished Swedish sentences, rendered
     * server-side like every other message field in this API (same "no client-side copy" pattern as
     * {@link BrokenWishView}/{@link IndirectFactorView}) — {@code detailSv} is {@code null} whenever
     * the title+impact already say everything (most suggestions). The four reference ids are
     * whichever of {groupId, participantProfileId, coachProfileId, timeSlotId} are relevant to that
     * {@code kind}; irrelevant ones are {@code null}, never fabricated.
     */
    public record SuggestionView(
            String kind,
            String titleSv,
            String detailSv,
            String impactSv,
            String groupId,
            String participantProfileId,
            String coachProfileId,
            String timeSlotId) {
    }

    public record ImprovementSuggestionsResponse(
            String runId,
            int basedOnRevision,
            int currentRevision,
            boolean stale,
            List<SuggestionView> suggestions,
            int omittedCount) {
    }
}
