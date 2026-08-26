package se.klubb.groupplanner.explain;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import se.klubb.groupplanner.fields.PriorityOrder;
import se.klubb.groupplanner.fields.PriorityOrder.Priority;
import se.klubb.groupplanner.solver.constraints.ConstraintKeys;
import se.klubb.groupplanner.solver.domain.GroupPlanSolution;

/**
 * M-E3 "vad skulle krävas?" (simple mode): for a {@code TRADE_OFF} unmet wish's best candidate,
 * predicts — for every one of the 24 permutations of {@link PriorityOrder.Priority} — whether
 * reordering the plan's four priorities would make the wish's move stop costing points, using ONLY
 * the M-E1 probe's already-computed {@link MoveProbe.ConstraintDelta} units and the spike's proven
 * "design consequence" (backend/docs/explain-linearity-spike.md): {@code Δscore(w') = Σ units_k·w'_k}
 * for ANY weight vector {@code w'}, exact, with zero additional {@code analyze()} calls.
 *
 * <p><b>Why this is soft-only</b>: a {@code TRADE_OFF} candidate is, by {@code CausalNarrator}'s own
 * outcome definition, hard-feasible ({@code wouldBreakHard() == false}) and not an improvement — since
 * {@link HardMediumSoftLongScore#compareTo} is lexicographic (hard, then medium, then soft), a
 * hard-feasible non-improving candidate's {@code scoreDelta} MUST have hard component exactly 0 (any
 * positive hard delta would already be an improvement, and no negative hard delta can exist without a
 * hard-level match, which {@code wouldBreakHard} would have flagged). Medium is separately unreachable:
 * the only MEDIUM-level constraint is {@code unassignedPlayer}, whose match count cannot change for a
 * probe that moves a player between two non-null groups (a {@code TRADE_OFF} candidate is always a
 * concrete group, never the waitlist — see {@code UnmetWishResolver}). Both are asserted defensively
 * below — a violation is a programming error upstream, never an honest sensitivity to report.
 *
 * <p><b>Why only the six bucket keys vary</b>: all six {@link PriorityOrder}-bucket constraint keys
 * ({@code sameGroupSoft}/{@code differentGroupSoft}/{@code previousGroupContinuity}/{@code
 * timePreferenceSoft}/{@code levelBalance}/{@code groupOrderByLevel}) are SOFT-level PENALIZE
 * constraints under every one of the 24 permutations ({@link PriorityOrderService#updateForPlan}
 * always restores {@code SOFT}/{@code enabled=true} on them) — so a permutation's weight vector {@code
 * w'} only ever changes these six keys' magnitude, never their level, and never touches any OTHER
 * constraint's weight at all. Every non-bucket key's contribution to {@code Δscore} is therefore
 * IDENTICAL across all 24 permutations — exactly the probe's own {@code ConstraintDelta.scoreDelta()},
 * already computed under the plan's current weights, reused verbatim rather than recomputed.
 */
final class PrioritySensitivityCalculator {

    /** The six {@link ConstraintKeys} a {@link PriorityOrder.Priority} ranking actually controls (see
     * {@link PriorityOrder#weightsFor}) — every other constraint key's weight is invariant across the
     * 24 permutations this class evaluates. */
    static final Set<String> BUCKET_KEYS = Set.of(
            ConstraintKeys.SAME_GROUP_SOFT, ConstraintKeys.DIFFERENT_GROUP_SOFT, ConstraintKeys.PREVIOUS_GROUP_CONTINUITY,
            ConstraintKeys.TIME_PREFERENCE_SOFT, ConstraintKeys.LEVEL_BALANCE, ConstraintKeys.GROUP_ORDER_BY_LEVEL);

    private static final String CUSTOM_WEIGHTS_REASON = "Planen använder egna vikter – prioritetsordningen styr inte just nu.";

    private static final String UNITS_UNKNOWN_REASON =
            "En regel som påverkar flytten är avstängd i planen, så det går inte att räkna ut vad en omprioritering skulle göra.";

    static final String CAUTION_SV =
            "Det gäller just den här flytten. Vad optimeringen faktiskt väljer avgörs först när du kör om den.";

    /** All 24 permutations of {@link Priority#values()}, in a fixed lexicographic order over the
     * enum's own declaration index (TRAIN_TOGETHER=0, PREVIOUS_GROUP=1, PREFERRED_TIME=2, LEVEL=3) —
     * deterministic (CLAUDE.md rule), and index 0 is exactly {@link PriorityOrder#defaultOrder()}. */
    private static final List<List<Priority>> ALL_ORDERS = allPermutationsLexicographic();

    /** One of the 24 permutations' prediction — {@code predictedSoftDelta} is the exact {@code
     * Σ units_k·w'_k} SOFT total under {@code order} (see class javadoc for why hard/medium never
     * enter here), {@code nonWorse = predictedSoftDelta >= 0}. */
    record Ordering(List<Priority> order, boolean nonWorse, long predictedSoftDelta) {
    }

    /** The full computation — {@code available=false} carries only {@code unavailableReasonSv}
     * (every other field {@code null}/empty, mirroring {@link ExplanationDtos.PrioritySensitivityView}'s
     * own null-safety contract, which {@link #toView} converts this into 1:1). */
    record Computation(
            boolean available,
            String unavailableReasonSv,
            String verdict,
            List<Priority> suggestedOrder,
            String summarySv,
            String cautionSv,
            String blockerLabelSv,
            List<Ordering> orderings) {

        static Computation unavailable(String reasonSv) {
            return new Computation(false, reasonSv, null, null, null, null, null, List.of());
        }
    }

    private PrioritySensitivityCalculator() {
    }

    /** Wiring convenience: the RAW, signed weight (magnitude + sign, as {@link
     * ai.timefold.solver.core.api.score.analysis.ConstraintAnalysis#weight()} itself reports it — see
     * the linearity spike's cross-cutting finding) for every constraint key currently known/enabled on
     * {@code solution}'s {@code ConstraintWeightOverrides} — mirrors {@link
     * ExplanationService#appliedWeightsFor}'s own lookup, kept signed here (that method takes {@code
     * Math.abs} for display; this class needs the sign to match a permutation's own weights exactly). */
    static Map<String, HardMediumSoftLongScore> currentWeightsOf(GroupPlanSolution solution) {
        Map<String, HardMediumSoftLongScore> map = new LinkedHashMap<>();
        var overrides = solution.getConstraintWeightOverrides();
        for (String key : ConstraintKeys.IMPLEMENTED) {
            if (overrides.getKnownConstraintNames().contains(key)) {
                map.put(key, overrides.getConstraintWeight(key));
            }
        }
        return map;
    }

    /**
     * Pure computation — no Spring/DB/solver dependency, stubbable with plain data (see {@code
     * PrioritySensitivityCalculatorTest}).
     *
     * @param perConstraint the TRADE_OFF candidate's {@link MoveProbe.Result#perConstraint()} (every
     *     {@link ConstraintKeys#IMPLEMENTED} key, key-ascending, per {@link MoveProbe}'s own contract)
     * @param currentWeights the plan's CURRENT RAW signed weight per known/enabled constraint key (see
     *     {@link #currentWeightsOf}) — used only to detect which of the 24 permutations (if any) the
     *     plan's six bucket keys currently match
     * @param wishKey the unmet wish's own {@link ConstraintKeys} constant ({@code
     *     UnmetWishResolver.UnmetWish#key()}) — used to find the wish's OWN {@link PriorityOrder
     *     .Priority} bucket, if any (a COACH wish has none: coach wishes are outside the four-priority
     *     system entirely)
     * @param candidateGroupNameSv the TRADE_OFF candidate's group name, for the FLIPS_BY_REORDER
     *     summary sentence
     */
    static Computation compute(
            List<MoveProbe.ConstraintDelta> perConstraint,
            Map<String, HardMediumSoftLongScore> currentWeights,
            String wishKey,
            String candidateGroupNameSv) {
        Map<String, MoveProbe.ConstraintDelta> byKey = new LinkedHashMap<>();
        for (MoveProbe.ConstraintDelta d : perConstraint) {
            byKey.put(d.key(), d);
        }

        // Defensive guard (see class javadoc): a TRADE_OFF candidate's aggregate hard/medium delta
        // must already be exactly zero. A violation means this method was invoked for a probe that
        // isn't actually a valid TRADE_OFF candidate - a programming error upstream, never an honest
        // sensitivity to compute.
        long aggHard = 0L;
        long aggMedium = 0L;
        for (MoveProbe.ConstraintDelta d : perConstraint) {
            aggHard += d.scoreDelta().hardScore();
            aggMedium += d.scoreDelta().mediumScore();
        }
        if (aggHard != 0L || aggMedium != 0L) {
            throw new IllegalStateException(
                    "PrioritySensitivityCalculator.compute invoked for a probe with nonzero hard/medium delta (hard="
                            + aggHard + ", medium=" + aggMedium + ") - impossible for a TRADE_OFF candidate, which is "
                            + "hard-feasible and medium-invariant by construction (see class javadoc).");
        }

        // Short-circuit: a bucket key disabled in the CURRENT plan has unitsKnown=false (spike (c) -
        // its ScoreAnalysis entry is entirely absent, no data). Every one of the 24 permutations would
        // give it a nonzero weight (PriorityOrderService.updateForPlan always re-enables all six bucket
        // keys as SOFT) - but this probe never measured any matches under that weight, so predicting
        // its contribution would not be provable from data.
        for (String bucketKey : BUCKET_KEYS) {
            MoveProbe.ConstraintDelta d = byKey.get(bucketKey);
            if (d == null || !d.unitsKnown()) {
                return Computation.unavailable(UNITS_UNKNOWN_REASON);
            }
        }

        Optional<List<Priority>> currentOrderOpt = matchCurrentPermutation(currentWeights);
        if (currentOrderOpt.isEmpty()) {
            return Computation.unavailable(CUSTOM_WEIGHTS_REASON);
        }
        List<Priority> currentOrder = currentOrderOpt.get();

        // Every non-bucket key's contribution is invariant across all 24 permutations - exactly the
        // probe's own scoreDelta (already units_k x currentWeight_k at the correct level).
        long fixedSoft = 0L;
        for (MoveProbe.ConstraintDelta d : perConstraint) {
            if (!BUCKET_KEYS.contains(d.key())) {
                fixedSoft += d.scoreDelta().softScore();
            }
        }

        List<Ordering> orderings = new ArrayList<>(ALL_ORDERS.size());
        Map<List<Priority>, Long> softByOrder = new LinkedHashMap<>();
        for (List<Priority> order : ALL_ORDERS) {
            Map<String, Integer> ladder = PriorityOrder.weightsFor(order);
            long soft = fixedSoft;
            for (String bucketKey : BUCKET_KEYS) {
                long units = byKey.get(bucketKey).units();
                long weight = -ladder.get(bucketKey); // every bucket key is a PENALIZE constraint.
                soft += units * weight;
            }
            orderings.add(new Ordering(order, soft >= 0, soft));
            softByOrder.put(order, soft);
        }

        boolean anyFlips = orderings.stream().anyMatch(Ordering::nonWorse);
        Optional<Priority> wishBucket = PriorityOrder.bucketOf(wishKey);

        if (!anyFlips) {
            String blockerKey = dominantBlockerKey(perConstraint, softByOrder);
            boolean blockerIsBucket = blockerKey != null && BUCKET_KEYS.contains(blockerKey);
            // FIX 5 (M-E3 review): the "lägsta prioritet" sentence names the PRIORITY family the user
            // would rank (e.g. "Träna tillsammans"), never the underlying constraint's own label (e.g.
            // "Samma grupp (mjuk)") - PriorityOrder.labelSv(bucketOf(key)) for a bucket blocker,
            // ConstraintMetadata's constraint label only for the (non-bucket) "outside the four
            // priorities" phrasing.
            String blockerLabel = blockerKey == null
                    ? "de samlade kostnaderna"
                    : blockerIsBucket
                            ? PriorityOrder.labelSv(PriorityOrder.bucketOf(blockerKey).orElseThrow())
                            : ConstraintMetadata.of(blockerKey).label();
            boolean alreadyTop = wishBucket.isPresent() && currentOrder.get(0) == wishBucket.get();
            String verdict = alreadyTop ? "ALREADY_TOP" : "NO_ORDER_HELPS";
            String summary = blockerIsBucket
                    ? "Inte ens lägsta prioritet på %s räcker – kostnaden på andra punkter är för stor.".formatted(blockerLabel)
                    : ("Ingen ordning av de fyra prioriteringarna räcker här. Det som väger tyngst emot är %s, som inte styrs "
                            + "av prioritetsordningen.").formatted(blockerLabel);
            if (alreadyTop) {
                summary = "%s har redan högsta prioritet. ".formatted(PriorityOrder.labelSv(wishBucket.get())) + summary;
            }
            return new Computation(true, null, verdict, null, summary, null, blockerLabel, orderings);
        }

        List<Priority> suggested = pickSuggestedOrder(currentOrder, wishBucket, orderings);
        String summary = summaryForFlip(currentOrder, suggested, wishBucket, candidateGroupNameSv);
        return new Computation(true, null, "FLIPS_BY_REORDER", suggested, summary, CAUTION_SV, null, orderings);
    }

    static ExplanationDtos.PrioritySensitivityView toView(Computation c) {
        if (!c.available()) {
            return new ExplanationDtos.PrioritySensitivityView(false, c.unavailableReasonSv(), null, null, null, null, null);
        }
        List<String> suggestedNames = c.suggestedOrder() == null ? null : c.suggestedOrder().stream().map(Enum::name).toList();
        return new ExplanationDtos.PrioritySensitivityView(
                true, null, c.verdict(), suggestedNames, c.summarySv(), c.cautionSv(), c.blockerLabelSv());
    }

    static List<ExplanationDtos.OrderingView> toOrderingViews(List<Ordering> orderings) {
        return orderings.stream()
                .map(o -> new ExplanationDtos.OrderingView(
                        o.order().stream().map(Enum::name).toList(), o.nonWorse(), o.predictedSoftDelta()))
                .toList();
    }

    // ─────────────────────────────────────────────────────────────────────── minimal-promotion search

    private static List<Priority> pickSuggestedOrder(List<Priority> currentOrder, Optional<Priority> wishBucket, List<Ordering> orderings) {
        if (wishBucket.isPresent()) {
            Priority bucket = wishBucket.get();
            int currentRank = currentOrder.indexOf(bucket) + 1;
            // FIX 1/FIX 7 (M-E3 review): only ranks STRICTLY ABOVE the bucket's current rank are a true
            // promotion - checking rank == currentRank (a no-op) or rank > currentRank (a demotion, see
            // promote()'s own javadoc) would let a non-promotion "flip" masquerade as one below in
            // summaryForFlip's minimal-promotion equality check. Least-aggressive-first (closest to
            // currentRank) so the MINIMAL sufficient promotion is preferred over an overshoot.
            for (int rank = currentRank - 1; rank >= 1; rank--) {
                List<Priority> candidate = promote(currentOrder, bucket, rank);
                Ordering o = orderingFor(orderings, candidate);
                if (o != null && o.nonWorse()) {
                    return candidate;
                }
            }
        }
        for (Ordering o : orderings) {
            if (o.nonWorse()) {
                return o.order();
            }
        }
        throw new IllegalStateException("pickSuggestedOrder called with no flipping permutation among orderings");
    }

    /** {@code bucket} moved to rank {@code rank} (1-indexed), every other priority keeping its
     * relative order. A no-op when {@code bucket} is already at {@code rank}, and — despite the name —
     * a DEMOTION when {@code rank} is numerically greater than {@code bucket}'s current rank (i.e. a
     * lower priority); callers that need a true promotion (e.g. {@link #pickSuggestedOrder}) are
     * responsible for only ever passing a {@code rank} strictly above the bucket's current one. */
    private static List<Priority> promote(List<Priority> currentOrder, Priority bucket, int rank) {
        List<Priority> rest = new ArrayList<>(currentOrder);
        rest.remove(bucket);
        rest.add(rank - 1, bucket);
        return List.copyOf(rest);
    }

    private static Ordering orderingFor(List<Ordering> orderings, List<Priority> order) {
        for (Ordering o : orderings) {
            if (o.order().equals(order)) {
                return o;
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────── NO_ORDER_HELPS blocker

    /** The single most-negative contributor under the BEST-achievable permutation (max predicted soft
     * delta among all 24) — "even under the most favorable reorder, this is what still costs the
     * most". {@code null} only defensively (should be unreachable: {@code !anyFlips} means every
     * permutation's total is negative, so at least one contributor must be). */
    private static String dominantBlockerKey(List<MoveProbe.ConstraintDelta> perConstraint, Map<List<Priority>, Long> softByOrder) {
        List<Priority> bestOrder = null;
        long best = Long.MIN_VALUE;
        for (Map.Entry<List<Priority>, Long> e : softByOrder.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                bestOrder = e.getKey();
            }
        }
        Map<String, Integer> ladder = PriorityOrder.weightsFor(bestOrder);
        String worstKey = null;
        long worstValue = 0L;
        for (MoveProbe.ConstraintDelta d : perConstraint) {
            if (!d.unitsKnown() || d.units() == 0L) {
                continue;
            }
            long contribution = BUCKET_KEYS.contains(d.key()) ? d.units() * (-ladder.get(d.key())) : d.scoreDelta().softScore();
            if (contribution < worstValue) {
                worstValue = contribution;
                worstKey = d.key();
            }
        }
        return worstKey;
    }

    // ─────────────────────────────────────────────────────────────────────── FLIPS_BY_REORDER summary

    private static String summaryForFlip(
            List<Priority> currentOrder, List<Priority> suggested, Optional<Priority> wishBucket, String candidateGroupNameSv) {
        // FIX 1 (M-E3 review, BLOCKER): the promotion sentence ("Om X prioriteras högre (över ...)")
        // claims that promoting the wish's bucket ALONE - past exactly the named priorities, everything
        // else unchanged - is sufficient. That is only actually true when `suggested` IS that minimal
        // promotion; pickSuggestedOrder's fallback (the lexicographically-first flipping permutation)
        // can return an order where the bucket moved up AND other priorities were reordered among
        // themselves, in which case the pure promotion described by the sentence would NOT flip (proven
        // counterexample: PrioritySensitivityCalculatorTest#... "still costs 600 points"). Gate on exact
        // equality with the minimal promotion so the sentence is never emitted unless it is provably
        // sufficient by itself; otherwise fall through to the honest neutral full-order sentence below.
        if (wishBucket.isPresent()) {
            Priority bucket = wishBucket.get();
            int oldBucketIdx = currentOrder.indexOf(bucket);
            int newBucketIdx = suggested.indexOf(bucket);
            if (oldBucketIdx > newBucketIdx && suggested.equals(promote(currentOrder, bucket, newBucketIdx + 1))) {
                List<String> passed = passedLabels(currentOrder, suggested, bucket);
                return "Om %s prioriteras högre (över %s) skulle flytten till %s inte längre kosta poäng."
                        .formatted(PriorityOrder.labelSv(bucket), joinSv(passed), candidateGroupNameSv);
            }
        }
        // No wish-owned bucket (e.g. a COACH wish), or the flip requires more than promoting this wish's
        // bucket alone (other priorities' relative order also had to change) - name the whole suggested
        // order honestly rather than a promotion claim that would not, by itself, actually flip the move.
        List<String> labels = suggested.stream().map(PriorityOrder::labelSv).toList();
        return "Med prioritetsordningen %s skulle flytten till %s inte längre kosta poäng."
                .formatted(joinSv(labels), candidateGroupNameSv);
    }

    private static List<String> passedLabels(List<Priority> currentOrder, List<Priority> suggested, Priority bucket) {
        int oldBucketIdx = currentOrder.indexOf(bucket);
        int newBucketIdx = suggested.indexOf(bucket);
        List<String> passed = new ArrayList<>();
        for (Priority p : currentOrder) {
            if (p == bucket) {
                continue;
            }
            if (currentOrder.indexOf(p) < oldBucketIdx && suggested.indexOf(p) > newBucketIdx) {
                passed.add(PriorityOrder.labelSv(p));
            }
        }
        return passed;
    }

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
        // FIX 5 (M-E3 review): standard Swedish list punctuation has NO comma before "och" (no serial/
        // Oxford comma) - "A, B och C", never "A, B, och C".
        return String.join(", ", items.subList(0, items.size() - 1)) + " och " + items.get(items.size() - 1);
    }

    // ─────────────────────────────────────────────────────────────────────── current-order matching

    /** Mirrors {@code PriorityOrderService#findMatchingPermutation}/{@code matchesExactly} but reads
     * the plan's RAW {@code ConstraintWeightOverrides} directly (this milestone's brief: "the plan's
     * current effective weights (from the solution's ConstraintWeightOverrides — the RAW applied
     * weights)") rather than round-tripping through the DB-backed {@code ConstraintWeightService} —
     * deliberately independent so this class stays a pure function of its inputs, stubbable in tests.
     *
     * <p><b>Sign note</b> (do not "fix" this to negate — see the linearity spike's cross-cutting
     * finding): unlike {@code ConstraintAnalysis.weight()} (which reports the SIGNED per-match
     * multiplier Timefold actually scores with, negative for a PENALIZE constraint), {@code
     * ConstraintWeightOverrides.getConstraintWeight(key)} reports the POSITIVE magnitude that was
     * PASSED IN when the override was built ({@code SolverInputAssembler#scoreFor} always calls {@code
     * HardMediumSoftLongScore.ofSoft(weight)} with a plain positive {@code int}, for every constraint
     * regardless of penalize/reward) — the exact same positive convention {@link PriorityOrder
     * #weightsFor} itself returns, so the two compare directly with NO sign adjustment. */
    private static Optional<List<Priority>> matchCurrentPermutation(Map<String, HardMediumSoftLongScore> currentWeights) {
        for (List<Priority> order : ALL_ORDERS) {
            Map<String, Integer> expected = PriorityOrder.weightsFor(order);
            boolean matches = true;
            for (Map.Entry<String, Integer> e : expected.entrySet()) {
                HardMediumSoftLongScore w = currentWeights.get(e.getKey());
                if (w == null || !w.equals(HardMediumSoftLongScore.ofSoft(e.getValue()))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return Optional.of(order);
            }
        }
        return Optional.empty();
    }

    // ─────────────────────────────────────────────────────────────────────── permutation generation

    private static List<List<Priority>> allPermutationsLexicographic() {
        List<Priority> base = List.of(Priority.values());
        int n = base.size();
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        List<List<Priority>> result = new ArrayList<>();
        do {
            List<Priority> order = new ArrayList<>(n);
            for (int i : idx) {
                order.add(base.get(i));
            }
            result.add(List.copyOf(order));
        } while (nextPermutation(idx));
        return List.copyOf(result);
    }

    /** Standard next-lexicographic-permutation-in-place; returns {@code false} once {@code a} is the
     * final (descending) permutation. */
    private static boolean nextPermutation(int[] a) {
        int n = a.length;
        int i = n - 2;
        while (i >= 0 && a[i] >= a[i + 1]) {
            i--;
        }
        if (i < 0) {
            return false;
        }
        int j = n - 1;
        while (a[j] <= a[i]) {
            j--;
        }
        int tmp = a[i];
        a[i] = a[j];
        a[j] = tmp;
        for (int l = i + 1, r = n - 1; l < r; l++, r--) {
            tmp = a[l];
            a[l] = a[r];
            a[r] = tmp;
        }
        return true;
    }
}
