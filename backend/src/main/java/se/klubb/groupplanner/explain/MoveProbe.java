package se.klubb.groupplanner.explain;

import ai.timefold.solver.core.api.score.analysis.ConstraintAnalysis;
import ai.timefold.solver.core.api.score.analysis.MatchAnalysis;
import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import ai.timefold.solver.core.api.solver.ScoreAnalysisFetchPolicy;
import ai.timefold.solver.core.api.solver.SolutionManager;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import se.klubb.groupplanner.explain.ExplanationDtos.ConstraintMessageView;
import se.klubb.groupplanner.solver.constraints.ConstraintKeys;
import se.klubb.groupplanner.solver.constraints.Justifications;
import se.klubb.groupplanner.solver.constraints.LevelMath;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;

/**
 * Shared "what-if this player moved to this group" evaluator (docs/design/04-solver.md §12.2),
 * backing BOTH {@link ExplanationService}'s per-alternative probes (§11.3, up to 13 per person: every
 * other group plus unassigned) and {@link WhatIfService}'s explicit move/why-not endpoints (§12) — one
 * implementation, so a "why is Grupp C rejected" answer is IDENTICAL whether it came from the
 * automatic alternatives list or a manual why-not click.
 *
 * <p><b>Deliberate performance deviation from the design's literal §12.1 text</b> ("{@code moved =
 * assembleFromRun(runId); moved.find(p).setGroup(targetGroup)}" — implying a FRESH {@code
 * SolverInputAssembler.assemble} per probe): this class instead mutates the ALREADY-assembled
 * solution's target entity in place, calls {@code analyze}, then restores the original group in a
 * {@code finally} block. Since {@code SolutionManager.analyze} is a pure read of the current object
 * graph (it recomputes the whole score fresh every call, never incrementally), probing this way is
 * behaviorally identical to rebuilding a fresh solution each time — but skips 12+ redundant DB
 * round-trips per person explanation, which is the difference between meeting and missing the M-S3
 * gate's explicit "&lt;1s cold per-player latency on large-120" requirement (measured in {@code
 * ExplanationLatencyTest}). Documented as a deviation, not silently done, per this milestone's rules.
 */
@Component
public class MoveProbe {

    private final SolutionManager<GroupPlanSolution, HardMediumSoftLongScore> solutionManager;

    /** M7 review fix m6 (restore-verify): solutions whose post-restore state has already been
     * verified to re-attain the baseline score — once per solution instance (i.e. once per request,
     * since {@code ExplanationService.loadContext} assembles a fresh solution per request), not per
     * probe, keeping the check cheap. Weak keys so per-request solutions never leak; synchronized
     * wrapper since {@link java.util.WeakHashMap} is not thread-safe (contention is nil — solutions
     * are request-scoped, only bookkeeping is shared). */
    private final java.util.Set<GroupPlanSolution> restoreVerified =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    public MoveProbe(SolutionManager<GroupPlanSolution, HardMediumSoftLongScore> solutionManager) {
        this.solutionManager = solutionManager;
    }

    /** One touched group's size/level-spread before and after a hypothetical move — {@code null} if
     * the group is irrelevant to this side of the move (e.g. moving OUT of the waitlist has no
     * "from" group). */
    public record GroupImpact(Group group, int sizeBefore, int sizeAfter, int spreadBefore, int spreadAfter) {
    }

    /**
     * M-E1 (v0.6.0, backend/docs/explain-linearity-spike.md's "design consequence"): one constraint's
     * EXACT contribution to a move's {@link Result#scoreDelta()}, decomposed to a level-free scalar.
     *
     * @param key {@link ConstraintKeys} string, always one of {@link ConstraintKeys#IMPLEMENTED}
     * @param scoreDelta this constraint's own slice of the diff (the RAW {@code
     *     ConstraintAnalysis.score()} from {@code movedAnalysis.diff(baseAnalysis)} for this key —
     *     {@link HardMediumSoftLongScore#ZERO} when {@code unitsKnown} is false, since a disabled
     *     constraint contributes nothing by construction)
     * @param units the level-free scalar {@code Δscore_component / weight_component} from the spike's
     *     "design consequence" section — {@code weight_component} read from the RAW (non-diffed)
     *     baseline analysis, NEVER from the diff itself (spike (d)); 0 when {@code unitsKnown} is false
     * @param unitsKnown false exactly when this constraint's key is absent from the RAW baseline
     *     analysis (spike (c): a zero-weight/disabled constraint is omitted ENTIRELY from {@code
     *     ScoreAnalysis}, never present-with-zero-weight) — "no data", not "units known but zero"
     */
    public record ConstraintDelta(String key, HardMediumSoftLongScore scoreDelta, long units, boolean unitsKnown) {
    }

    /** Internal (non-DTO) sibling of {@link ConstraintMessageView} carrying the individual match's own
     * score alongside its key/message — {@link ExplanationDtos.ConstraintMessageView} itself stays
     * untouched (E1 brief: "public ConstraintMessageView DTO UNTOUCHED") since it is already part of
     * the external API contract; this variant exists only for E2's {@code CausalNarrator}, which needs
     * to aggregate a candidate's broken/fixed matches BY SCORE (e.g. "which competing reason costs the
     * most") without any additional {@code analyze()} call.
     *
     * <p>{@code participantIds} (M-E2 review fix, per-PAIR granularity) is the set of participant
     * solver-ids the underlying {@link ConstraintJustification} itself names — non-empty ONLY for
     * justification types this class knows how to decompose ({@link
     * Justifications.PairWishBrokenJustification}/{@link Justifications.PairWishSoftJustification}'s
     * two sides, or the single participant named by a {@link Justifications.CoachWishJustification}/
     * {@link Justifications.TimePreferenceMissedJustification}/{@link
     * Justifications.ContinuityJustification}/{@link Justifications.TimeUnavailableJustification}/
     * {@link Justifications.UnassignedPlayerJustification}) — empty for every other match type (e.g. a
     * group-capacity/level-spread match has no participant to name). Lets {@code CausalNarrator}
     * attribute a match to the SPECIFIC pair/person a wish concerns, not merely its constraint key —
     * two DIFFERENT {@code sameGroupSoft} pairs must never be conflated into one wish's cost/gain. */
    public record ScoredMatch(String key, String messageSv, HardMediumSoftLongScore matchScore, List<Long> participantIds) {
    }

    /**
     * @param wouldBreakHard M-E2 review fix (BLOCKER, "hard-feasibility from matches, not net delta"):
     *     true iff {@code newlyBroken} contains ANY hard-level match — NOT (as originally coded)
     *     {@code scoreDelta.hardScore() < 0}. The net-delta version let a move that repairs one hard
     *     violation while creating a DIFFERENT one net to zero and read as "feasible" (proven: a
     *     SOLVER_MISS recommending a time-infeasible manual move, narrated "ingen regel hindrar den").
     *     A move that breaks ANY hard constraint is infeasible for a manual "move anyway" regardless of
     *     what it simultaneously repairs elsewhere.
     */
    public record Result(
            HardMediumSoftLongScore scoreDelta,
            boolean wouldBreakHard,
            List<ConstraintMessageView> newlyBroken,
            List<ConstraintMessageView> newlyFixed,
            List<ScoredMatch> newlyBrokenScored,
            List<ScoredMatch> newlyFixedScored,
            List<ConstraintDelta> perConstraint,
            GroupImpact fromImpact,
            GroupImpact toImpact) {

        /** True iff {@code scoreDelta} is a net improvement under the FULL lexicographic ordering
         * (hard, then medium, then soft) that {@link HardMediumSoftLongScore#compareTo} already
         * implements — a STRICTLY positive hard delta is always an improvement regardless of what
         * medium/soft do (e.g. a move that repairs a hard violation elsewhere while costing soft
         * points is still an improvement). Independent of {@link #wouldBreakHard}, which is about NEW
         * hard breaks (match-based); this method never re-derives feasibility, only orders candidates
         * that already passed it. */
        public boolean isImprovement() {
            return scoreDelta.compareTo(HardMediumSoftLongScore.ZERO) > 0;
        }
    }

    /**
     * Evaluates moving {@code target} to {@code candidateGroupOrNull} (null = waitlist) against
     * {@code solution}/{@code baseAnalysis} (already {@code FETCH_ALL}-analyzed at the CURRENT
     * assignment). Mutates {@code target} for the duration of the call only — restored before
     * returning, so {@code solution} is safe to reuse for the next probe.
     */
    public Result evaluate(
            GroupPlanSolution solution,
            ScoreAnalysis<HardMediumSoftLongScore> baseAnalysis,
            PlayerAssignment target,
            Group candidateGroupOrNull,
            SolutionIndex idx) {
        Group originalGroup = target.getGroup();
        GroupStats fromBefore = originalGroup == null ? null : statsOf(solution, originalGroup);
        GroupStats toBefore = candidateGroupOrNull == null ? null : statsOf(solution, candidateGroupOrNull);

        target.setGroup(candidateGroupOrNull);
        try {
            ScoreAnalysis<HardMediumSoftLongScore> movedAnalysis =
                    solutionManager.analyze(solution, ScoreAnalysisFetchPolicy.FETCH_ALL);
            ScoreAnalysis<HardMediumSoftLongScore> diff = movedAnalysis.diff(baseAnalysis);

            GroupStats fromAfter = originalGroup == null ? null : statsOf(solution, originalGroup);
            GroupStats toAfter = candidateGroupOrNull == null ? null : statsOf(solution, candidateGroupOrNull);

            List<ConstraintMessageView> newlyBroken = new ArrayList<>();
            List<ConstraintMessageView> newlyFixed = new ArrayList<>();
            List<ScoredMatch> newlyBrokenScored = new ArrayList<>();
            List<ScoredMatch> newlyFixedScored = new ArrayList<>();
            boolean wouldBreakHard = false;
            for (ConstraintAnalysis<HardMediumSoftLongScore> ca : diff.constraintAnalyses()) {
                for (MatchAnalysis<HardMediumSoftLongScore> match : ca.matches()) {
                    HardMediumSoftLongScore matchScore = match.score();
                    List<Long> participantIds = participantIdsOf(match.justification());
                    if (isNegative(matchScore)) {
                        String messageSv = JustificationMessages.toSwedish(match.justification(), idx);
                        newlyBroken.add(new ConstraintMessageView(ca.constraintName(), messageSv));
                        newlyBrokenScored.add(new ScoredMatch(ca.constraintName(), messageSv, matchScore, participantIds));
                        // M-E2 review fix (BLOCKER): hard-feasibility comes from the MATCH set, never
                        // the net scoreDelta — see the Result#wouldBreakHard javadoc.
                        if (matchScore.hardScore() != 0) {
                            wouldBreakHard = true;
                        }
                    } else if (isPositive(matchScore)) {
                        String messageSv = JustificationMessages.toSwedishAsFixed(match.justification(), idx);
                        newlyFixed.add(new ConstraintMessageView(ca.constraintName(), messageSv));
                        newlyFixedScored.add(new ScoredMatch(ca.constraintName(), messageSv, matchScore, participantIds));
                    }
                }
            }

            List<ConstraintDelta> perConstraint = derivePerConstraintDeltas(diff, baseAnalysis);

            GroupImpact fromImpact = fromBefore == null
                    ? null
                    : new GroupImpact(originalGroup, fromBefore.size(), fromAfter.size(), fromBefore.spread(), fromAfter.spread());
            GroupImpact toImpact = toBefore == null
                    ? null
                    : new GroupImpact(
                            candidateGroupOrNull, toBefore.size(), toAfter.size(), toBefore.spread(), toAfter.spread());

            return new Result(
                    diff.score(), wouldBreakHard, newlyBroken, newlyFixed, newlyBrokenScored, newlyFixedScored,
                    perConstraint, fromImpact, toImpact);
        } finally {
            target.setGroup(originalGroup);
            verifyRestoredOncePerSolution(solution, baseAnalysis);
        }
    }

    /** M7 review fix m6: cheap guard against the mutate-and-restore strategy's one real hazard — a
     * probe that fails to restore the solution would silently poison every SUBSEQUENT probe's diff
     * for the rest of the request. After the FIRST restore for a given solution instance, re-analyze
     * once ({@code FETCH_SHALLOW} — score only, no match data, a fraction of a full probe's cost) and
     * assert the baseline score is re-attained; any mismatch is a programming error worth failing
     * loudly on rather than serving corrupted explanations. */
    private void verifyRestoredOncePerSolution(
            GroupPlanSolution solution, ScoreAnalysis<HardMediumSoftLongScore> baseAnalysis) {
        if (!restoreVerified.add(solution)) {
            return; // already verified once for this request's solution instance.
        }
        HardMediumSoftLongScore restoredScore =
                solutionManager.analyze(solution, ScoreAnalysisFetchPolicy.FETCH_SHALLOW).score();
        if (!restoredScore.equals(baseAnalysis.score())) {
            throw new IllegalStateException(
                    "MoveProbe restore-verify failed: baseline score " + baseAnalysis.score()
                            + " not re-attained after probe restore (got " + restoredScore
                            + ") - the working solution has been corrupted mid-request");
        }
    }

    /** Package-visible (not private) so {@link ExplanationService} can reuse the exact same size/
     * spread/mean computation for its "selected group" display view, without duplicating the logic.
     * {@code meanScaled} is 0 for an empty group (no members to average — same convention {@link
     * LevelMath#sadPoints} uses for spread). */
    record GroupStats(int size, int spread, long meanScaled) {
    }

    static GroupStats statsOf(GroupPlanSolution solution, Group group) {
        List<Integer> levels = new ArrayList<>();
        for (PlayerAssignment pa : solution.getPlayerAssignments()) {
            if (pa.getGroup() == group) {
                levels.add(pa.getLevelScaled());
            }
        }
        int[] arr = levels.stream().mapToInt(Integer::intValue).toArray();
        long mean = arr.length == 0 ? 0L : LevelMath.floorMean(LevelMath.sum(arr), arr.length);
        return new GroupStats(arr.length, LevelMath.sadPoints(arr), mean);
    }

    /**
     * M-E1: {@code units_k = Δscore_k / weight_k} for every {@link ConstraintKeys#IMPLEMENTED}
     * constraint, key-ascending (CLAUDE.md determinism rule) — the exact derivation
     * {@code WeightSensitivityLinearityTest#deriveUnits} proved sound, reused per-probe rather than
     * per-test. The weight is read from {@code weightSource} (a RAW, non-diffed {@code ScoreAnalysis}
     * computed under the CURRENT weight vector — {@code MoveProbe} never touches weights, only {@code
     * target.setGroup(...)}, so {@code baseAnalysis} and {@code movedAnalysis} always share one weight
     * vector) — never from {@code diff} itself, whose own {@code ConstraintAnalysis.weight()} is
     * always zero for same-weight operands (spike (d)).
     */
    private static List<ConstraintDelta> derivePerConstraintDeltas(
            ScoreAnalysis<HardMediumSoftLongScore> diff, ScoreAnalysis<HardMediumSoftLongScore> weightSource) {
        List<ConstraintDelta> result = new ArrayList<>();
        for (String key : new TreeSet<>(ConstraintKeys.IMPLEMENTED)) {
            ConstraintAnalysis<HardMediumSoftLongScore> baseCa = findConstraintAnalysis(weightSource, key);
            if (baseCa == null) {
                // Spike (c): a zero-weight/disabled constraint is omitted ENTIRELY from ScoreAnalysis
                // — "no data", not "units known but zero" (spike's own design-consequence wording).
                result.add(new ConstraintDelta(key, HardMediumSoftLongScore.ZERO, 0L, false));
                continue;
            }
            HardMediumSoftLongScore weight = baseCa.weight();
            int level = soleNonZeroLevel(weight);
            if (level < 0) {
                // Defensive only: every GroupPlanConstraintProvider constraint declares its weight at
                // exactly one level by construction (WeightSensitivityLinearityTest#deriveUnits'
                // own invariant) - a weight that is entirely zero here would mean baseCa shouldn't
                // have existed in the first place (spike (c)). Treated the same as "no data" rather
                // than risking a divide-by-zero or a fabricated units value.
                result.add(new ConstraintDelta(key, HardMediumSoftLongScore.ZERO, 0L, false));
                continue;
            }
            long weightScalar = componentOf(weight, level);
            ConstraintAnalysis<HardMediumSoftLongScore> diffCa = findConstraintAnalysis(diff, key);
            HardMediumSoftLongScore scoreDelta = diffCa == null ? HardMediumSoftLongScore.ZERO : diffCa.score();
            long deltaScalar = componentOf(scoreDelta, level);
            long units = weightScalar == 0 ? 0L : deltaScalar / weightScalar;
            result.add(new ConstraintDelta(key, scoreDelta, units, true));
        }
        return result;
    }

    private static ConstraintAnalysis<HardMediumSoftLongScore> findConstraintAnalysis(
            ScoreAnalysis<HardMediumSoftLongScore> analysis, String key) {
        for (ConstraintAnalysis<HardMediumSoftLongScore> ca : analysis.constraintAnalyses()) {
            if (key.equals(ca.constraintName())) {
                return ca;
            }
        }
        return null;
    }

    /** 0=hard, 1=medium, 2=soft; -1 if the score is entirely zero at all three levels (see spike's
     * {@code WeightSensitivityLinearityTest#soleNonZeroLevel}, reused here rather than duplicated by
     * accident of independent invention). */
    private static int soleNonZeroLevel(HardMediumSoftLongScore s) {
        int found = -1;
        for (int level = 0; level < 3; level++) {
            if (componentOf(s, level) != 0L) {
                if (found >= 0) {
                    return -1; // more than one nonzero level: not a well-formed single-level weight.
                }
                found = level;
            }
        }
        return found;
    }

    private static long componentOf(HardMediumSoftLongScore s, int level) {
        return switch (level) {
            case 0 -> s.hardScore();
            case 1 -> s.mediumScore();
            case 2 -> s.softScore();
            default -> throw new IllegalArgumentException("level must be 0/1/2, got " + level);
        };
    }

    /** M-E2 review fix (per-PAIR granularity, backing {@link ScoredMatch#participantIds()}): the
     * participant solver-id(s) this specific justification names, so {@code CausalNarrator} can tell
     * "this pair's own wish" apart from "some OTHER pair with the same constraint key" — see {@link
     * ScoredMatch}'s own javadoc. Empty (never null) for every justification type not listed here. */
    private static List<Long> participantIdsOf(ai.timefold.solver.core.api.score.stream.ConstraintJustification j) {
        return switch (j) {
            case Justifications.PairWishBrokenJustification x -> List.of(x.aParticipantId(), x.bParticipantId());
            case Justifications.PairWishSoftJustification x -> List.of(x.aParticipantId(), x.bParticipantId());
            case Justifications.CoachWishJustification x -> List.of(x.participantId());
            case Justifications.TimePreferenceMissedJustification x -> List.of(x.participantId());
            case Justifications.ContinuityJustification x -> List.of(x.participantId());
            case Justifications.TimeUnavailableJustification x -> List.of(x.participantId());
            case Justifications.UnassignedPlayerJustification x -> List.of(x.participantId());
            default -> List.of();
        };
    }

    private static boolean isNegative(HardMediumSoftLongScore s) {
        if (s.hardScore() != 0) {
            return s.hardScore() < 0;
        }
        if (s.mediumScore() != 0) {
            return s.mediumScore() < 0;
        }
        return s.softScore() < 0;
    }

    private static boolean isPositive(HardMediumSoftLongScore s) {
        if (s.hardScore() != 0) {
            return s.hardScore() > 0;
        }
        if (s.mediumScore() != 0) {
            return s.mediumScore() > 0;
        }
        return s.softScore() > 0;
    }
}
