package se.klubb.groupplanner.explain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.klubb.groupplanner.explain.PrioritySensitivityCalculator.Computation;
import se.klubb.groupplanner.fields.PriorityOrder;
import se.klubb.groupplanner.solver.constraints.ConstraintKeys;

/**
 * M-E3 pure-function tests for {@link PrioritySensitivityCalculator} — stubbed {@link
 * MoveProbe.ConstraintDelta} units/weights, no Spring/DB/solver dependency (this class has none, by
 * design). {@code DEFAULT_WEIGHTS} below is the plan-default ladder ({@code
 * PriorityOrder.defaultOrder()}: TRAIN_TOGETHER, PREVIOUS_GROUP, PREFERRED_TIME, LEVEL) so every test
 * starts from a plan whose weights exactly match a permutation (never {@code customWeightsActive}
 * unless a test specifically wants that).
 */
class PrioritySensitivityCalculatorTest {

    private static final Map<String, HardMediumSoftLongScore> DEFAULT_WEIGHTS = defaultOrderWeights();

    private static Map<String, HardMediumSoftLongScore> defaultOrderWeights() {
        Map<String, HardMediumSoftLongScore> map = new LinkedHashMap<>();
        map.put(ConstraintKeys.SAME_GROUP_SOFT, HardMediumSoftLongScore.ofSoft(2400));
        map.put(ConstraintKeys.DIFFERENT_GROUP_SOFT, HardMediumSoftLongScore.ofSoft(1800));
        map.put(ConstraintKeys.PREVIOUS_GROUP_CONTINUITY, HardMediumSoftLongScore.ofSoft(1500));
        map.put(ConstraintKeys.TIME_PREFERENCE_SOFT, HardMediumSoftLongScore.ofSoft(950));
        map.put(ConstraintKeys.LEVEL_BALANCE, HardMediumSoftLongScore.ofSoft(85));
        map.put(ConstraintKeys.GROUP_ORDER_BY_LEVEL, HardMediumSoftLongScore.ofSoft(42));
        return map;
    }

    private static MoveProbe.ConstraintDelta delta(String key, long softDelta, long units) {
        return new MoveProbe.ConstraintDelta(key, HardMediumSoftLongScore.ofSoft(softDelta), units, true);
    }

    private static MoveProbe.ConstraintDelta unknown(String key) {
        return new MoveProbe.ConstraintDelta(key, HardMediumSoftLongScore.ZERO, 0L, false);
    }

    /** Every one of the six bucket keys present with zero units (untouched by this move) — the
     * baseline every scenario below starts from and then overrides individual entries in. */
    private static List<MoveProbe.ConstraintDelta> allBucketKeysUntouched() {
        return List.of(
                delta(ConstraintKeys.SAME_GROUP_SOFT, 0, 0), delta(ConstraintKeys.DIFFERENT_GROUP_SOFT, 0, 0),
                delta(ConstraintKeys.PREVIOUS_GROUP_CONTINUITY, 0, 0), delta(ConstraintKeys.TIME_PREFERENCE_SOFT, 0, 0),
                delta(ConstraintKeys.LEVEL_BALANCE, 0, 0), delta(ConstraintKeys.GROUP_ORDER_BY_LEVEL, 0, 0));
    }

    private static List<MoveProbe.ConstraintDelta> withOverrides(List<MoveProbe.ConstraintDelta> overrides) {
        Map<String, MoveProbe.ConstraintDelta> byKey = new LinkedHashMap<>();
        for (MoveProbe.ConstraintDelta d : allBucketKeysUntouched()) {
            byKey.put(d.key(), d);
        }
        for (MoveProbe.ConstraintDelta d : overrides) {
            byKey.put(d.key(), d);
        }
        return List.copyOf(byKey.values());
    }

    // ─────────────────────────────────────────────────────────────────────── FLIPS_BY_REORDER at rank 1

    /** sameGroupSoft breaks (-2400 at rank1), timePreferenceSoft fixes (+950 at rank3, "units" = -1
     * since Δscore/weight_signed = 950/-950). Predicted at current default order: -2400+950=-1450
     * (negative, matches TRADE_OFF). Promoting PREFERRED_TIME to rank3 (no-op, same as current) and
     * rank2 both stay negative; rank1 flips (-1500+2400=+900) - hand-verified against the identical
     * fixture in CausalNarrativeTruthfulnessTest.tradeOffOutcomeNamesTheDominantCompetingReasonWithoutRawNumbers. */
    @Test
    void flipsByReorderAtRankOneWhenOnlyTheFullPromotionIsEnough() {
        List<MoveProbe.ConstraintDelta> perConstraint = withOverrides(List.of(
                delta(ConstraintKeys.SAME_GROUP_SOFT, -2400, 1), delta(ConstraintKeys.TIME_PREFERENCE_SOFT, 950, -1)));

        Computation c = PrioritySensitivityCalculator.compute(
                perConstraint, DEFAULT_WEIGHTS, ConstraintKeys.TIME_PREFERENCE_SOFT, "Grupp B");

        assertThat(c.available()).isTrue();
        assertThat(c.verdict()).isEqualTo("FLIPS_BY_REORDER");
        assertThat(c.suggestedOrder()).containsExactly(
                PriorityOrder.Priority.PREFERRED_TIME, PriorityOrder.Priority.TRAIN_TOGETHER,
                PriorityOrder.Priority.PREVIOUS_GROUP, PriorityOrder.Priority.LEVEL);
        assertThat(c.cautionSv()).isEqualTo(PrioritySensitivityCalculator.CAUTION_SV);
        assertThat(c.summarySv()).isEqualTo(
                "Om Önskad träningstid prioriteras högre (över Träna tillsammans och Tidigare grupp) skulle flytten till "
                        + "Grupp B inte längre kosta poäng.");
        assertThat(c.orderings()).hasSize(24);
    }

    // ─────────────────────────────────────────────────────────────────────── minimal-promotion stability

    /** levelBalance (units=-5, gains as its weight rises) vs. timePreferenceSoft (units=+1, costs as
     * its weight rises) - promoting LEVEL to rank3 alone (demoting PREFERRED_TIME 3->4, promoting
     * LEVEL 4->3) already flips the total (-525 -> +75); rank3 must therefore be picked, never
     * overshooting to rank2/rank1 (both of which would ALSO flip, but are not minimal). */
    @Test
    void minimalPromotionPicksTheLeastAggressiveRankThatFlips() {
        List<MoveProbe.ConstraintDelta> perConstraint = withOverrides(List.of(
                delta(ConstraintKeys.LEVEL_BALANCE, 425, -5), delta(ConstraintKeys.TIME_PREFERENCE_SOFT, -950, 1)));

        Computation c = PrioritySensitivityCalculator.compute(perConstraint, DEFAULT_WEIGHTS, ConstraintKeys.LEVEL_BALANCE, "Grupp Z");

        assertThat(c.verdict()).isEqualTo("FLIPS_BY_REORDER");
        assertThat(c.suggestedOrder()).containsExactly(
                PriorityOrder.Priority.TRAIN_TOGETHER, PriorityOrder.Priority.PREVIOUS_GROUP,
                PriorityOrder.Priority.LEVEL, PriorityOrder.Priority.PREFERRED_TIME);
    }

    // ─────────────────────────────────────────────────────────────────────── FIX 1: promotion-sentence sufficiency gate

    /** Reviewer's exact counterexample (M-E3 review, BLOCKER): PREVIOUS_GROUP wish, units=-1;
     * sameGroupSoft has TWO broken matches, units=+2. At the default order, the ONLY promotion of
     * PREVIOUS_GROUP available (to rank 1: {@code [PG,TT,PT,LVL]}) still predicts {@code -600} (does
     * NOT flip: {@code -2*950(TT@rank2) + 2400(PG@rank1) = -1900+2400}... — see the worked arithmetic
     * below). The first permutation that DOES flip in lexicographic order is {@code [PG,PT,TT,LVL]}
     * (rank3 puts TRAIN_TOGETHER low enough), which ALSO reorders PREFERRED_TIME above TRAIN_TOGETHER —
     * a second change the old buggy sentence ("promote PREVIOUS_GROUP over TRAIN_TOGETHER") never
     * mentioned, and which the pure promotion alone provably does not achieve. This pins that the
     * sentence must fall back to the neutral, honest full-order phrasing instead of falsely claiming
     * sufficiency for a promotion that still costs 600 points. */
    @Test
    void flipRequiringMoreThanTheNamedPromotionUsesTheNeutralSentenceNotTheInsufficientPromotionClaim() {
        List<MoveProbe.ConstraintDelta> perConstraint = withOverrides(List.of(
                delta(ConstraintKeys.SAME_GROUP_SOFT, -4800, 2), delta(ConstraintKeys.PREVIOUS_GROUP_CONTINUITY, 1500, -1)));

        Computation c = PrioritySensitivityCalculator.compute(
                perConstraint, DEFAULT_WEIGHTS, ConstraintKeys.PREVIOUS_GROUP_CONTINUITY, "Grupp X");

        assertThat(c.available()).isTrue();
        assertThat(c.verdict()).isEqualTo("FLIPS_BY_REORDER");
        assertThat(c.suggestedOrder()).containsExactly(
                PriorityOrder.Priority.PREVIOUS_GROUP, PriorityOrder.Priority.PREFERRED_TIME,
                PriorityOrder.Priority.TRAIN_TOGETHER, PriorityOrder.Priority.LEVEL);
        // The neutral, honest full-order sentence - NEVER the promotion-only phrasing naming just
        // "Tidigare grupp över Träna tillsammans" (that pure promotion alone does not flip - see below).
        assertThat(c.summarySv()).isEqualTo(
                "Med prioritetsordningen Tidigare grupp, Önskad träningstid, Träna tillsammans och Träningsnivå "
                        + "skulle flytten till Grupp X inte längre kosta poäng.");

        // Pin the insufficiency itself: the promotion-only order the old buggy sentence would have
        // implied ("PREVIOUS_GROUP promoted above TRAIN_TOGETHER, nothing else changed") still costs
        // 600 points - it does NOT appear among the flipping orderings.
        List<PriorityOrder.Priority> promotionOnlyOrder = List.of(
                PriorityOrder.Priority.PREVIOUS_GROUP, PriorityOrder.Priority.TRAIN_TOGETHER,
                PriorityOrder.Priority.PREFERRED_TIME, PriorityOrder.Priority.LEVEL);
        PrioritySensitivityCalculator.Ordering promotionOnly = c.orderings().stream()
                .filter(o -> o.order().equals(promotionOnlyOrder))
                .findFirst()
                .orElseThrow();
        assertThat(promotionOnly.nonWorse()).as("the promotion-only order must NOT flip").isFalse();
        assertThat(promotionOnly.predictedSoftDelta()).isEqualTo(-600);
    }

    // ─────────────────────────────────────────────────────────────────────── FIX 5: bucket-floor blocker label

    /** {@code NO_ORDER_HELPS}/{@code ALREADY_TOP}'s "Inte ens lägsta prioritet på X räcker" sentence
     * must name the PRIORITY family (e.g. "Träna tillsammans") the user would actually rank, never the
     * underlying constraint's own label (e.g. "Samma grupp (mjuk)") - both {@code blockerLabelSv} and
     * the sentence text are pinned here for a dominant blocker that IS one of the six bucket keys. */
    @Test
    void noOrderHelpsNamesThePriorityFamilyLabelWhenTheDominantBlockerIsABucketKey() {
        List<MoveProbe.ConstraintDelta> perConstraint = withOverrides(List.of(
                delta(ConstraintKeys.SAME_GROUP_SOFT, -2400, 1), delta(ConstraintKeys.DIFFERENT_GROUP_SOFT, -1800, 1)));

        Computation c = PrioritySensitivityCalculator.compute(
                perConstraint, DEFAULT_WEIGHTS, ConstraintKeys.TIME_PREFERENCE_SOFT, "Grupp B");

        assertThat(c.verdict()).isEqualTo("NO_ORDER_HELPS");
        assertThat(c.blockerLabelSv()).isEqualTo("Träna tillsammans");
        assertThat(c.summarySv()).isEqualTo("Inte ens lägsta prioritet på Träna tillsammans räcker – kostnaden på andra punkter är för stor.");
    }

    // ─────────────────────────────────────────────────────────────────────── NO_ORDER_HELPS

    /** A dominant NON-bucket negative (groupSizeTarget, -5000, fixed across every permutation) swamps
     * anything the four priorities could ever buy back - no permutation flips, and the blocker named
     * must be the non-bucket key, not either bucket key. */
    @Test
    void noOrderHelpsNamesTheDominantNonBucketBlocker() {
        List<MoveProbe.ConstraintDelta> perConstraint = withOverrides(List.of(
                delta(ConstraintKeys.SAME_GROUP_SOFT, -2400, 1), delta(ConstraintKeys.TIME_PREFERENCE_SOFT, 950, -1),
                delta(ConstraintKeys.GROUP_SIZE_TARGET, -5000, -5)));

        Computation c = PrioritySensitivityCalculator.compute(
                perConstraint, DEFAULT_WEIGHTS, ConstraintKeys.TIME_PREFERENCE_SOFT, "Grupp B");

        assertThat(c.available()).isTrue();
        assertThat(c.verdict()).isEqualTo("NO_ORDER_HELPS");
        assertThat(c.blockerLabelSv()).isEqualTo("Målstorlek grupp");
        assertThat(c.summarySv()).isEqualTo(
                "Ingen ordning av de fyra prioriteringarna räcker här. Det som väger tyngst emot är Målstorlek grupp, "
                        + "som inte styrs av prioritetsordningen.");
        assertThat(c.suggestedOrder()).isNull();
        assertThat(c.cautionSv()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────── ALREADY_TOP

    /** Same underlying arithmetic as the NO_ORDER_HELPS case above (no permutation flips), but the
     * wish's own key IS sameGroupSoft, whose bucket (TRAIN_TOGETHER) is already rank 1 in the current
     * (default) order - the extra "already highest" clause must be present. */
    @Test
    void alreadyTopWhenTheWishsOwnBucketIsAlreadyRankOneAndNothingFlips() {
        List<MoveProbe.ConstraintDelta> perConstraint = withOverrides(List.of(
                delta(ConstraintKeys.SAME_GROUP_SOFT, -2400, 1), delta(ConstraintKeys.TIME_PREFERENCE_SOFT, 950, -1),
                delta(ConstraintKeys.GROUP_SIZE_TARGET, -5000, -5)));

        Computation c = PrioritySensitivityCalculator.compute(
                perConstraint, DEFAULT_WEIGHTS, ConstraintKeys.SAME_GROUP_SOFT, "Grupp B");

        assertThat(c.verdict()).isEqualTo("ALREADY_TOP");
        assertThat(c.summarySv()).isEqualTo(
                "Träna tillsammans har redan högsta prioritet. Ingen ordning av de fyra prioriteringarna räcker här. "
                        + "Det som väger tyngst emot är Målstorlek grupp, som inte styrs av prioritetsordningen.");
    }

    // ─────────────────────────────────────────────────────────────────────── INCONCLUSIVE (unitsKnown=false)

    /** A bucket key disabled in the current plan (unitsKnown=false) makes the WHOLE computation
     * unavailable - reordering would re-enable it (PriorityOrderService always restores SOFT/enabled),
     * but this probe has no data on what it would have matched. */
    @Test
    void inconclusiveWhenABucketKeyIsDisabledInTheCurrentPlan() {
        List<MoveProbe.ConstraintDelta> perConstraint = withOverrides(List.of(unknown(ConstraintKeys.LEVEL_BALANCE)));

        Computation c = PrioritySensitivityCalculator.compute(
                perConstraint, DEFAULT_WEIGHTS, ConstraintKeys.TIME_PREFERENCE_SOFT, "Grupp B");

        assertThat(c.available()).isFalse();
        assertThat(c.unavailableReasonSv()).isEqualTo(
                "En regel som påverkar flytten är avstängd i planen, så det går inte att räkna ut vad en omprioritering skulle göra.");
        assertThat(c.verdict()).isNull();
        assertThat(c.suggestedOrder()).isNull();
        assertThat(c.summarySv()).isNull();
        assertThat(c.cautionSv()).isNull();
        assertThat(c.blockerLabelSv()).isNull();
        assertThat(c.orderings()).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────── customWeightsActive

    @Test
    void unavailableWhenCurrentWeightsDoNotMatchAnyOfTheTwentyFourPermutations() {
        Map<String, HardMediumSoftLongScore> customWeights = new LinkedHashMap<>(DEFAULT_WEIGHTS);
        customWeights.put(ConstraintKeys.SAME_GROUP_SOFT, HardMediumSoftLongScore.ofSoft(777)); // not on any ladder rank.
        List<MoveProbe.ConstraintDelta> perConstraint = withOverrides(List.of(
                delta(ConstraintKeys.SAME_GROUP_SOFT, -777, 1), delta(ConstraintKeys.TIME_PREFERENCE_SOFT, 950, -1)));

        Computation c = PrioritySensitivityCalculator.compute(
                perConstraint, customWeights, ConstraintKeys.TIME_PREFERENCE_SOFT, "Grupp B");

        assertThat(c.available()).isFalse();
        assertThat(c.unavailableReasonSv()).isEqualTo("Planen använder egna vikter – prioritetsordningen styr inte just nu.");
    }

    // ─────────────────────────────────────────────────────────────────────── hard/medium guard

    /** Defensive guard: TRADE_OFF is hard-feasible and medium-invariant by construction (see class
     * javadoc) - a probe with any nonzero aggregate hard/medium delta must never reach this
     * calculator; it is a programming error upstream, and this asserts the guard actually fires
     * rather than silently computing a false sensitivity. */
    @Test
    void throwsWhenTheAggregateHardDeltaIsNonzeroImpossibleForATradeOffCandidate() {
        List<MoveProbe.ConstraintDelta> perConstraint = withOverrides(List.of(
                new MoveProbe.ConstraintDelta(ConstraintKeys.GROUP_MAX_SIZE_HARD, HardMediumSoftLongScore.ofHard(-1), -1, true)));

        assertThatThrownBy(() -> PrioritySensitivityCalculator.compute(
                perConstraint, DEFAULT_WEIGHTS, ConstraintKeys.TIME_PREFERENCE_SOFT, "Grupp B"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hard=");
    }

    @Test
    void throwsWhenTheAggregateMediumDeltaIsNonzeroImpossibleForATradeOffCandidate() {
        List<MoveProbe.ConstraintDelta> perConstraint = withOverrides(List.of(
                new MoveProbe.ConstraintDelta(ConstraintKeys.UNASSIGNED_PLAYER, HardMediumSoftLongScore.ofMedium(-100), -1, true)));

        assertThatThrownBy(() -> PrioritySensitivityCalculator.compute(
                perConstraint, DEFAULT_WEIGHTS, ConstraintKeys.TIME_PREFERENCE_SOFT, "Grupp B"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("medium=");
    }
}
