package se.klubb.groupplanner.explain;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.klubb.groupplanner.explain.ExplanationDtos.SuggestionView;
import se.klubb.groupplanner.explain.ExplanationService.RunContext;
import se.klubb.groupplanner.explain.UnmetWishResolver.UnmetWish;
import se.klubb.groupplanner.fields.PriorityOrder;
import se.klubb.groupplanner.fields.PriorityOrder.Priority;
import se.klubb.groupplanner.solver.domain.Group;
import se.klubb.groupplanner.solver.domain.PlayerAssignment;

/**
 * v0.6.0 E5 "family D" — {@code PRIORITY_ORDER}: the one PLAN-LEVEL member of {@link
 * ImprovementSuggestionService}'s suggestion families (A/B/C are all per-player/per-group). Where A/B/C
 * ask "what SMALL DATA CHANGE would unlock a placement", this family asks a different, honest question:
 * "how many PLACED players' unmet {@link PriorityOrder}-bucket wishes would stop costing points under a
 * DIFFERENT priority order" — never claiming what the solver would actually place them in (that is only
 * known by re-solving; see {@link #CAUTION_SV}, which every emitted suggestion carries verbatim as its
 * {@code impactSv}).
 *
 * <h2>Algorithm</h2>
 *
 * <ol>
 *   <li>Skip the WHOLE family when the plan's current weights don't exactly match one of the 24 {@link
 *       PriorityOrder.Priority} permutations ({@code customWeightsActive} — the rank list isn't in
 *       charge of the plan's scoring right now, so reordering it would not change anything) — reuses
 *       {@link PrioritySensitivityCalculator#currentWeightsOf}/{@link
 *       PrioritySensitivityCalculator#matchCurrentPermutation} verbatim, never reimplemented.
 *   <li>Collect every PLACED, non-pinned player (ascending solver-id order — CLAUDE.md determinism
 *       rule) with at least one unmet wish that (a) has a {@link PriorityOrder#bucketOf} bucket (a
 *       COACH wish has none — outside the four-priority system entirely) and (b) has at least one
 *       satisfying candidate group ({@link UnmetWishResolver}, DATA only) — one REPRESENTATIVE wish per
 *       player (the first one {@link UnmetWishResolver#resolve}'s own deterministic TIME/FRIEND/AVOID/
 *       PREVGROUP order produces), so "one player, one wish" throughout the rest of this algorithm. This
 *       is {@code totalAffectedPlayers}, computed BEFORE any probing.
 *   <li>Probe budget: {@link #maxPriorityProbes} {@link MoveProbe#evaluate} calls TOTAL for the whole
 *       family (not per player) — each qualifying player's wish costs exactly {@code
 *       wish.candidateGroups().size()} calls (typically 1-3). Players are probed in the SAME ascending
 *       order as collected; the moment the NEXT player's cost would exceed the remaining budget, probing
 *       stops entirely (a player is either fully probed or not probed at all — never partially) — {@code
 *       analyzedPlayers} is how many were actually probed.
 *   <li>Per probed player, the "best" (least-bad) candidate among the wish's own satisfying groups is
 *       picked the same way {@link CausalNarrator}'s own least-bad-candidate ordering does (no new hard
 *       breaks first, then full lexicographic score delta descending, then group order ascending) — a
 *       SEPARATE local copy of that comparator, like {@link ImprovementSuggestionService
 *       #isBetterCandidate} already is its own separate copy for family A (see that method's javadoc for
 *       why these are never shared). A best candidate that itself breaks a hard constraint contributes
 *       ZERO flips under every ordering (skipped before calling {@link PrioritySensitivityCalculator
 *       #compute}, whose own hard/medium-zero guard would otherwise throw — see that class's javadoc:
 *       this can only happen for a wish whose OWN outcome is not truly {@code TRADE_OFF}-shaped, e.g. a
 *       player-of-the-family whose only candidate happens to be full).
 *   <li>Per probed player, {@link PrioritySensitivityCalculator#compute} (REUSED, not reimplemented) is
 *       called on the best candidate's own {@code perConstraint} units — exact arithmetic, zero
 *       additional {@code analyze()} calls beyond the probe itself — yielding all 24 {@code Ordering}s.
 *       For every ordering where {@code nonWorse()}, that ordering's flip-count is incremented by one
 *       (one player, one wish, at most one flip-count contribution per ordering).
 *   <li>Two suggestions at most: (a) the ordering with the highest flip-count; ties are broken by
 *       fewest pairwise adjacent-swaps from the CURRENT order ({@link #swapDistance}), and a REMAINING
 *       tie (equal flip-count AND equal swap-distance) keeps whichever entry {@link #pickSuggestions}
 *       reached FIRST while scanning — which is always the lexicographically-earliest of the tied
 *       orderings, because {@link PrioritySensitivityCalculator#compute} always populates all 24
 *       orderings in {@code ALL_ORDERS}' fixed lexicographic order, regardless of which qualifying
 *       player's computation happens to be the first one inserted into {@code flipCounts}; (b) — only
 *       if DIFFERENT from (a) and itself flips at least one player — the best ordering exactly ONE
 *       adjacent swap away from the current order (the smallest possible nudge a council member could
 *       make from the priorities screen).
 * </ol>
 *
 * <p>Every {@code SuggestionView} this class emits carries {@code null} for {@code groupId}/{@code
 * participantProfileId}/{@code coachProfileId}/{@code timeSlotId} — a plan-level aggregate points at no
 * single group/player/coach/time slot, and the four-id contract ("never fabricated") is honored simply
 * by never fabricating a reference that would not be honest.
 */
final class PriorityOrderSuggestionBuilder {

    private static final Logger log = LoggerFactory.getLogger(PriorityOrderSuggestionBuilder.class);

    /** Hard cap on {@link MoveProbe#evaluate} calls this family will make for one {@link #build}
     *  invocation, TOTAL (not per player) — keeps the plan-level scan bounded regardless of how many
     *  players have unmet bucket wishes. Package-visible and deliberately NON-final so {@code
     *  PriorityOrderSuggestionTest} can lower it to exercise the "granskades inte" (not-reviewed)
     *  honesty clause without needing a huge fixture — any test that reassigns this MUST restore it
     *  (60) in a {@code finally} block, since it is shared process-wide state. */
    static int maxPriorityProbes = 60;

    /** The mandatory caution every {@code PRIORITY_ORDER} suggestion's {@code impactSv} carries
     *  verbatim (task brief) — this family only ever predicts what WOULD stop costing points under a
     *  different order, never what the solver would actually place a player into; only a re-solve
     *  answers that. */
    static final String CAUTION_SV = "Vad optimeringen faktiskt väljer avgörs först när du kör om den.";

    private static final String TITLE_SV = "Fler skulle kunna få sina önskemål uppfyllda med en annan prioritetsordning.";

    /** {@code probeCount} is exposed purely for {@code PriorityOrderSuggestionTest}'s probe-budget
     *  assertions ({@code ImprovementSuggestionService} itself only ever consumes {@link
     *  #suggestions()}). */
    record Result(int totalAffectedPlayers, int analyzedPlayers, int probeCount, List<SuggestionView> suggestions) {
        static final Result EMPTY = new Result(0, 0, 0, List.of());
    }

    private record AnalyzedWish(PlayerAssignment player, UnmetWish wish) {
    }

    private PriorityOrderSuggestionBuilder() {
    }

    static Result build(RunContext ctx, MoveProbe moveProbe) {
        Map<String, HardMediumSoftLongScore> currentWeights = PrioritySensitivityCalculator.currentWeightsOf(ctx.solution());
        Optional<List<Priority>> currentOrderOpt = PrioritySensitivityCalculator.matchCurrentPermutation(currentWeights);
        if (currentOrderOpt.isEmpty()) {
            return Result.EMPTY; // customWeightsActive - the rank list isn't in charge right now.
        }
        List<Priority> currentOrder = currentOrderOpt.get();

        List<AnalyzedWish> qualifying = collectQualifyingPlayers(ctx);
        if (qualifying.isEmpty()) {
            return Result.EMPTY; // 0 unmet bucket wishes - nothing this family could ever suggest.
        }

        Map<List<Priority>, Long> flipCounts = new LinkedHashMap<>();
        int analyzedPlayers = 0;
        int probeCount = 0;
        for (AnalyzedWish aw : qualifying) {
            int cost = aw.wish().candidateGroups().size();
            if (probeCount + cost > maxPriorityProbes) {
                break; // cap reached - a player is either fully probed or not probed at all.
            }
            Group bestGroup = null;
            MoveProbe.Result bestResult = null;
            for (Group g : aw.wish().candidateGroups()) {
                MoveProbe.Result r = moveProbe.evaluate(ctx.solution(), ctx.baseAnalysis(), aw.player(), g, ctx.index());
                probeCount++;
                if (bestResult == null || isBetterCandidate(r, g, bestResult, bestGroup)) {
                    bestGroup = g;
                    bestResult = r;
                }
            }
            analyzedPlayers++;
            // Only a genuine TRADE_OFF-shaped best candidate (hard-feasible AND strictly worse than the
            // CURRENT placement under CURRENT weights) can honestly "stop costing points" under a
            // DIFFERENT order - a wish whose best candidate is already non-worse right now (SOLVER_MISS/
            // EQUAL-shaped) is already free, so it must never contribute a flip (that would suggest
            // reordering to fix something that already isn't broken - proven by a real fixture: two
            // placed friends in different groups whose wish is fixable with zero cost today). Mirrors
            // CausalNarrator's own TRADE_OFF gate (hard-feasible, not EQUAL, not an improvement).
            boolean isTradeOff = bestResult != null && !bestResult.wouldBreakHard()
                    && bestResult.scoreDelta().compareTo(HardMediumSoftLongScore.ZERO) < 0;
            // E5 review fix (MINOR, wishGain self-check): mirrors CausalNarrator's own TRADE_OFF
            // self-check invariant (see that class's javadoc on INCONCLUSIVE) - the best candidate's
            // newlyFixedScored must actually contain a match for THIS wish's own pair/key before its
            // flip can honestly count anywhere. A candidate that happens to fix a DIFFERENT pair
            // sharing the same constraint key must never inflate this player's contribution. Unlike
            // CausalNarrator (a WARN, since that failure blocks one specific narration the user is
            // actively reading), this family already tolerates all sorts of honest zero-flip
            // outcomes across a whole plan scan, so a DEBUG log is enough - never WARN spam.
            if (isTradeOff && !wishOwnFixIsPresent(bestResult, aw.wish(), aw.player().getId())) {
                log.debug(
                        "PriorityOrderSuggestionBuilder self-check: best candidate {} for participant {} wish {} does "
                                + "not fix the wish's own pair - skipping this player's flip contribution.",
                        bestGroup.name(), aw.player().getId(), aw.wish().key());
                isTradeOff = false;
            }
            if (isTradeOff) {
                PrioritySensitivityCalculator.Computation computation = PrioritySensitivityCalculator.compute(
                        bestResult.perConstraint(), currentWeights, aw.wish().key(), bestGroup.name());
                if (computation.available()) {
                    for (PrioritySensitivityCalculator.Ordering o : computation.orderings()) {
                        flipCounts.merge(o.order(), o.nonWorse() ? 1L : 0L, Long::sum);
                    }
                }
                // computation.available()==false (a bucket key disabled in this plan) - this player's
                // wish contributes zero flips to every ordering, same as a hard-blocked best candidate;
                // never a crash, never a fabricated flip.
            }
            // !isTradeOff (every candidate hard-blocked, or the best candidate is already non-worse
            // right now): no priority reorder claim is honest here either way - zero flips, same
            // honest non-contribution, still counted in analyzedPlayers (it WAS reviewed).
        }

        int omitted = qualifying.size() - analyzedPlayers;
        List<SuggestionView> suggestions = pickSuggestions(currentOrder, flipCounts, analyzedPlayers, omitted);
        return new Result(qualifying.size(), analyzedPlayers, probeCount, suggestions);
    }

    private static List<SuggestionView> pickSuggestions(
            List<Priority> currentOrder, Map<List<Priority>, Long> flipCounts, int analyzedPlayers, int omitted) {
        if (flipCounts.isEmpty()) {
            return List.of();
        }

        List<Priority> bestOrder = null;
        long bestFlips = -1;
        int bestSwapDist = Integer.MAX_VALUE;
        for (Map.Entry<List<Priority>, Long> e : flipCounts.entrySet()) {
            long flips = e.getValue();
            int swapDist = swapDistance(currentOrder, e.getKey());
            if (flips > bestFlips || (flips == bestFlips && swapDist < bestSwapDist)) {
                bestOrder = e.getKey();
                bestFlips = flips;
                bestSwapDist = swapDist;
            }
        }
        if (bestOrder == null || bestFlips < 1) {
            return List.of(); // no ordering flips anyone - nothing honest to suggest.
        }

        List<SuggestionView> out = new ArrayList<>();
        out.add(toSuggestion(bestOrder, bestFlips, analyzedPlayers, omitted));

        List<Priority> bestAdjacent = null;
        long bestAdjacentFlips = -1;
        for (List<Priority> candidate : singleAdjacentSwaps(currentOrder)) {
            long flips = flipCounts.getOrDefault(candidate, 0L);
            if (flips > bestAdjacentFlips) {
                bestAdjacent = candidate;
                bestAdjacentFlips = flips;
            }
        }
        if (bestAdjacent != null && bestAdjacentFlips >= 1 && !bestAdjacent.equals(bestOrder)) {
            out.add(toSuggestion(bestAdjacent, bestAdjacentFlips, analyzedPlayers, omitted));
        }
        return List.copyOf(out);
    }

    private static List<AnalyzedWish> collectQualifyingPlayers(RunContext ctx) {
        List<PlayerAssignment> placed = ctx.solution().getPlayerAssignments().stream()
                .filter(pa -> pa.getGroup() != null && !pa.isPinned())
                .sorted(Comparator.comparingLong(PlayerAssignment::getId))
                .toList();
        List<AnalyzedWish> out = new ArrayList<>();
        for (PlayerAssignment player : placed) {
            for (UnmetWish w : UnmetWishResolver.resolve(ctx, player, player.getGroup())) {
                // E5 review fix (BLOCKER, target-side attribution): a directed FRIEND/AVOID wish's
                // b-side (wishOwnedByTarget()==false - see UnmetWish's own javadoc) must never count
                // as a qualifying "spelare med ouppfyllda önskemål" here - a single one-directional
                // wish would otherwise inflate n by counting BOTH the owner and the mere target as
                // two separate affected players for what is honestly one person's unmet wish.
                if (PriorityOrder.bucketOf(w.key()).isPresent() && !w.candidateGroups().isEmpty() && w.wishOwnedByTarget()) {
                    out.add(new AnalyzedWish(player, w));
                    break; // one representative wish per player - resolver's own deterministic order.
                }
            }
        }
        return out;
    }

    /** Local copy of the "least-bad candidate" ordering (no new hard breaks first, then full
     *  lexicographic score delta descending, then group order ascending) — deliberately NOT {@link
     *  ImprovementSuggestionService#isBetterCandidate} (that one is |hard|-ascending, wrong here for
     *  the exact same reason {@link CausalNarrator}'s own javadoc explains) and NOT a call into {@link
     *  CausalNarrator} itself (its comparator is {@code private}) — a third, purpose-built copy,
     *  matching this codebase's established precedent of never sharing this comparator across families. */
    private static boolean isBetterCandidate(MoveProbe.Result r, Group g, MoveProbe.Result best, Group bestGroup) {
        if (r.wouldBreakHard() != best.wouldBreakHard()) {
            return !r.wouldBreakHard();
        }
        int scoreCmp = r.scoreDelta().compareTo(best.scoreDelta());
        if (scoreCmp != 0) {
            return scoreCmp > 0;
        }
        return g.groupOrder() < bestGroup.groupOrder();
    }

    /** E5 review fix (MINOR, wishGain self-check): local mirror of {@link
     *  CausalNarrator}'s own {@code sumAbsPrimaryForPair} > 0 invariant (that method is {@code
     *  private}, so — matching this file's established precedent of never sharing comparators across
     *  families — this is a separate, purpose-built boolean copy) — true only when {@code
     *  bestResult.newlyFixedScored()} contains a match for the wish's own SPECIFIC pair (both {@code
     *  targetId} and the wish's {@code otherParticipantSolverId}), or — for a wish with no "other
     *  participant" (TIME/PREVGROUP) — a match naming {@code targetId} itself. A candidate that
     *  merely fixes a DIFFERENT pair/person sharing the same constraint key must never validate this
     *  wish's own gain. */
    private static boolean wishOwnFixIsPresent(MoveProbe.Result bestResult, UnmetWish wish, long targetId) {
        for (MoveProbe.ScoredMatch m : bestResult.newlyFixedScored()) {
            if (!wish.key().equals(m.key())) {
                continue;
            }
            if (wish.otherParticipantSolverId() != null) {
                if (m.participantIds().contains(targetId) && m.participantIds().contains(wish.otherParticipantSolverId())) {
                    return true;
                }
            } else if (m.participantIds().isEmpty() || m.participantIds().contains(targetId)) {
                return true;
            }
        }
        return false;
    }

    private static SuggestionView toSuggestion(List<Priority> order, long flips, int analyzedPlayers, int omitted) {
        List<String> labels = order.stream().map(PriorityOrder::labelSv).toList();
        // E5 review fixes: (MAJOR, joint-claim honesty) "var för sig" makes explicit that each
        // player's move is independently non-worse under the new order - this NEVER claims the k
        // players could all move AT ONCE without the plan's total score changing (moving several
        // players simultaneously is not what any single-move probe in this family ever measured).
        // (MINOR, singular grammar) the participle agrees with analyzedPlayers ("n"), not flips -
        // "1 av 1 granskad spelare" reads correctly, never "1 av 1 granskade spelare".
        String participleSv = analyzedPlayers == 1 ? "granskad" : "granskade";
        String detail = ("%d av %d %s spelare med ouppfyllda önskemål skulle var för sig kunna flyttas som önskat utan "
                + "att planen blir sämre, om ordningen ändras till %s.")
                .formatted(flips, analyzedPlayers, participleSv, joinSv(labels));
        if (omitted > 0) {
            // MINOR review fix (dead duplicate branch): "%d" of 1 already formats to "1" - the old
            // omitted==1 special case produced byte-identical output to the general formula.
            detail += " (%d spelare till har ouppfyllda önskemål men granskades inte.)".formatted(omitted);
        }
        List<String> orderKeys = order.stream().map(Enum::name).toList();
        return new SuggestionView("PRIORITY_ORDER", TITLE_SV, detail, CAUTION_SV, null, null, null, null, orderKeys);
    }

    /** Standard Swedish list join, no comma before the final "och" (task brief's own worked example:
     *  "A, B, C och D") — a separate small copy of the exact same convention {@link
     *  PrioritySensitivityCalculator}'s own (private) {@code joinSv} uses, kept local since that one
     *  isn't accessible from here. */
    private static String joinSv(List<String> items) {
        if (items.isEmpty()) {
            return "";
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        if (items.size() == 2) {
            return items.get(0) + " och " + items.get(1);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " och " + items.get(items.size() - 1);
    }

    // ─────────────────────────────────────────────────────────────────────── permutation-distance helpers

    /** Minimum number of ADJACENT transpositions (bubble-sort swaps) needed to turn {@code from} into
     *  {@code to} — the standard inversion count of {@code to}'s elements re-labeled by their rank in
     *  {@code from}. Symmetric (same result turning {@code to} into {@code from}). */
    private static int swapDistance(List<Priority> from, List<Priority> to) {
        int n = from.size();
        int[] ranks = new int[n];
        for (int i = 0; i < n; i++) {
            ranks[i] = from.indexOf(to.get(i));
        }
        int inversions = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (ranks[i] > ranks[j]) {
                    inversions++;
                }
            }
        }
        return inversions;
    }

    /** The (at most 3, for 4 priorities) orderings exactly ONE adjacent swap away from {@code order} —
     *  every {@code (i, i+1)} position pair swapped once. */
    private static List<List<Priority>> singleAdjacentSwaps(List<Priority> order) {
        List<List<Priority>> out = new ArrayList<>();
        for (int i = 0; i < order.size() - 1; i++) {
            List<Priority> swapped = new ArrayList<>(order);
            Priority tmp = swapped.get(i);
            swapped.set(i, swapped.get(i + 1));
            swapped.set(i + 1, tmp);
            out.add(List.copyOf(swapped));
        }
        return out;
    }
}
