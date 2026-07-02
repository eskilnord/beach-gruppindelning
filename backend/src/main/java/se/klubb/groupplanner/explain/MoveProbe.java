package se.klubb.groupplanner.explain;

import ai.timefold.solver.core.api.score.analysis.ConstraintAnalysis;
import ai.timefold.solver.core.api.score.analysis.MatchAnalysis;
import ai.timefold.solver.core.api.score.analysis.ScoreAnalysis;
import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import ai.timefold.solver.core.api.solver.ScoreAnalysisFetchPolicy;
import ai.timefold.solver.core.api.solver.SolutionManager;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import se.klubb.groupplanner.explain.ExplanationDtos.ConstraintMessageView;
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

    public record Result(
            HardMediumSoftLongScore scoreDelta,
            boolean wouldBreakHard,
            List<ConstraintMessageView> newlyBroken,
            List<ConstraintMessageView> newlyFixed,
            GroupImpact fromImpact,
            GroupImpact toImpact) {

        /** {@code hard==0} and either an improvement (medium/soft strictly better) or a lateral move;
         * used by callers to derive the WOULD_BREAK_HARD|WORSE|BETTER verdict (design §11.3). */
        public boolean isImprovement() {
            if (scoreDelta.hardScore() < 0) {
                return false;
            }
            if (scoreDelta.mediumScore() != 0) {
                return scoreDelta.mediumScore() > 0;
            }
            return scoreDelta.softScore() > 0;
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
            for (ConstraintAnalysis<HardMediumSoftLongScore> ca : diff.constraintAnalyses()) {
                for (MatchAnalysis<HardMediumSoftLongScore> match : ca.matches()) {
                    HardMediumSoftLongScore matchScore = match.score();
                    if (isNegative(matchScore)) {
                        newlyBroken.add(new ConstraintMessageView(
                                ca.constraintName(), JustificationMessages.toSwedish(match.justification(), idx)));
                    } else if (isPositive(matchScore)) {
                        newlyFixed.add(new ConstraintMessageView(
                                ca.constraintName(), JustificationMessages.toSwedishAsFixed(match.justification(), idx)));
                    }
                }
            }

            GroupImpact fromImpact = fromBefore == null
                    ? null
                    : new GroupImpact(originalGroup, fromBefore.size(), fromAfter.size(), fromBefore.spread(), fromAfter.spread());
            GroupImpact toImpact = toBefore == null
                    ? null
                    : new GroupImpact(
                            candidateGroupOrNull, toBefore.size(), toAfter.size(), toBefore.spread(), toAfter.spread());

            return new Result(diff.score(), diff.score().hardScore() < 0, newlyBroken, newlyFixed, fromImpact, toImpact);
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
