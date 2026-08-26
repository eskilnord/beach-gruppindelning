package se.klubb.groupplanner.fields;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import se.klubb.groupplanner.solver.constraints.ConstraintKeys;

/**
 * v0.6.0 milestone B6: the new default priority order's weight ladder — test-only today; the
 * priority-order endpoint (B7) and the explain module consume it next, to turn a user-chosen
 * ranking of four trade-off families into concrete {@code constraint_definition}/{@code
 * constraint_weight_config} weights (six {@link ConstraintKeys} entries).
 *
 * <h2>The r&#8776;1.6 rationale</h2>
 *
 * <p>Each of {@link #UNIT_LADDER}/{@link #LEVEL_LADDER}/{@link #AVOID_LADDER} is a geometric-ish
 * ladder (ratio between adjacent ranks &#8776; 1.6) rather than a linear one: a linear ladder makes
 * rank 2 barely distinguishable from rank 3 in practice (whichever constraints happen to fire more
 * often at solve time can still flip the effective ordering), while a much steeper ladder (ratio
 * &#8805; 3) makes every rank but the first practically inert. r&#8776;1.6 keeps every rank
 * meaningfully dominant over the next while still letting a rank-4 constraint occasionally win a
 * small, cheap trade-off against a rank-1 constraint that would cost it dearly.
 *
 * <p>Concretely, at {@link #defaultOrder()} (TRAIN_TOGETHER, PREVIOUS_GROUP, PREFERRED_TIME, LEVEL):
 * {@code sameGroupSoft} = 2400, and one "band" of level imbalance costs {@code LevelMath
 * .SPREAD_UNIT_SCALED}-sized spread units &#215; {@code levelBalance}'s weight (85) &#8776; 7 spread
 * units &#215; 85 = 595 per band (see {@code fields.WeightCalibrationTest}'s worked arithmetic).
 * Since 2400 / 595 &#8776; 4.03, satisfying ONE friend wish is worth dragging a player up to
 * <b>&#8776;4 level bands</b> away from where pure level-balance would have placed them — a
 * self-limiting trade-off (a 5th band would cost more than the wish is worth) rather than an
 * unbounded one. This is a deliberate, accepted, and documented trade-off (not a bug): under the OLD
 * pre-v0.6.0 defaults (kravspec §17.2's worked example, level balance 100 &gt; friend wishes 60-80)
 * level balance dominated every friend wish unconditionally; v0.6.0 inverts that priority by design
 * (see {@code backend/docs/priority-order-notes.md}).
 *
 * <p>Reversing the order (LEVEL ranked 1st, TRAIN_TOGETHER ranked 4th): {@code levelBalance} = 340,
 * so level dominates a single wish per band under reversal (340&#215;7=2380 &gt; 600, {@code
 * sameGroupSoft} at rank 4) — see {@code WeightCalibrationTest}'s reversal-sanity case. This is the
 * SAME per-band dominance relationship the default order gives friend wishes over level balance,
 * just with the two families' ranks swapped; it is not a claim that every OTHER aspect of pre-v0.6.0
 * scoring behavior (unrelated constraints, ladder shape, etc.) is reproduced unconditionally.
 *
 * <p>{@link #AVOID_LADDER} (used for {@code differentGroupSoft}) is deliberately 0.75&#215; {@link
 * #UNIT_LADDER} at every rank: avoiding an unwanted pairing is real but, per product judgment, a
 * notch less important than satisfying a positive "want to play with" wish at the same priority
 * rank — {@code differentGroupSoft} therefore rides the SAME {@link Priority#TRAIN_TOGETHER} bucket
 * as {@code sameGroupSoft} but at 0.75&#215; its ladder value, not its own independent rank.
 */
public final class PriorityOrder {

    /** The four trade-off families a user ranks, highest priority first by convention of the list
     * passed to {@link #weightsFor(List)} — the enum's own declaration order is otherwise
     * meaningless (it does NOT imply a ranking; only {@link #defaultOrder()}'s LIST does). */
    public enum Priority {
        TRAIN_TOGETHER,
        PREVIOUS_GROUP,
        PREFERRED_TIME,
        LEVEL
    }

    /** Rank-indexed (index 0 = rank 1, highest priority) ladder for the "unit" family:
     * {@code sameGroupSoft} (TRAIN_TOGETHER), {@code previousGroupContinuity} (PREVIOUS_GROUP),
     * {@code timePreferenceSoft} (PREFERRED_TIME). Ratio between adjacent ranks &#8776; 1.6 (see
     * class javadoc). */
    static final int[] UNIT_LADDER = {2400, 1500, 950, 600};

    /** Rank-indexed ladder for {@code levelBalance} (the LEVEL bucket's spread half) — &#8776;
     * UNIT_LADDER / 7, since one band-move of level spread costs about 7 {@code
     * LevelMath.SPREAD_UNIT_SCALED}-sized spread units of matchWeight (see class javadoc's worked
     * example and {@code WeightCalibrationTest}). */
    static final int[] LEVEL_LADDER = {340, 215, 135, 85};

    /** Rank-indexed ladder for {@code groupOrderByLevel} (the LEVEL bucket's ordering half) — since
     * v0.6.0 milestone B6, {@code groupOrderByLevel}'s matchWeight is in the SAME {@code
     * LevelMath.SPREAD_UNIT_SCALED} unit {@code levelBalance} uses (before B6 the two disagreed by
     * ~10x despite sharing one ladder rank). {@link #ORDER_LADDER} is {@link #LEVEL_LADDER} halved
     * (integer floor division, not rounded to a multiple of 5 — contrast {@link #AVOID_LADDER}),
     * restoring the ORIGINAL design's &#8776;0.5&#215; relative strength of ordering vs. spread per
     * unit (design §4: {@code groupOrderByLevel} SOFT 5/point vs. {@code levelBalance} SOFT 2/point
     * &#8776; the same 0.5&#8211;2.5&#215; ballpark once units are shared): at rank 4 (default order)
     * a 70-point inversion costs 7 spread units &#215; 42 = 294 &#8776; 8 &#215; 42 = 336, about
     * 0.56&#215; one levelBalance band-cost (7 &#215; 85 = 595) — matching pre-v0.6.0 relative
     * semantics between the two constraints, not just a coincidence of the halving. */
    static final int[] ORDER_LADDER = {170, 107, 67, 42};

    /** Rank-indexed ladder for {@code differentGroupSoft} — 0.75&#215; {@link #UNIT_LADDER} at every
     * rank, ROUNDED TO THE NEAREST 5 (a plain 0.75&#215; would give {2400,1125,712.5,450} — 712.5 is
     * not an integer, so every rank is rounded to the nearest whole multiple of 5 for a clean UI
     * number; {@code WeightCalibrationTest} pins each rank within &#177;5 of the exact 0.75&#215;
     * value). See class javadoc: rides the TRAIN_TOGETHER bucket, not an independent rank. */
    static final int[] AVOID_LADDER = {1800, 1125, 715, 450};

    private static final Map<Priority, Set<String>> CONSTRAINT_KEYS_OF = buildConstraintKeysOf();

    private static final Map<Priority, String> LABEL_SV = buildLabelsSv();

    private static final Map<String, Priority> BUCKET_OF_KEY = buildBucketOfKey();

    private PriorityOrder() {
    }

    /** The shipped v0.6.0 default order: TRAIN_TOGETHER, PREVIOUS_GROUP, PREFERRED_TIME, LEVEL. */
    public static List<Priority> defaultOrder() {
        return List.of(Priority.TRAIN_TOGETHER, Priority.PREVIOUS_GROUP, Priority.PREFERRED_TIME, Priority.LEVEL);
    }

    /**
     * Maps a full ranking of the four {@link Priority} families to concrete weights for the six
     * {@link ConstraintKeys} constants the families expand into.
     *
     * @param order a permutation of ALL FOUR {@link Priority} values, highest priority first
     * @return an immutable map from constraint key to its weight under this order
     * @throws IllegalArgumentException with a Swedish message if {@code order} is not a permutation
     *     of all four {@link Priority} values (wrong size, a duplicate, or a missing value)
     */
    public static Map<String, Integer> weightsFor(List<Priority> order) {
        validatePermutation(order);
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (int rank = 0; rank < order.size(); rank++) {
            Priority priority = order.get(rank);
            switch (priority) {
                case TRAIN_TOGETHER -> {
                    weights.put(ConstraintKeys.SAME_GROUP_SOFT, UNIT_LADDER[rank]);
                    weights.put(ConstraintKeys.DIFFERENT_GROUP_SOFT, AVOID_LADDER[rank]);
                }
                case PREVIOUS_GROUP -> weights.put(ConstraintKeys.PREVIOUS_GROUP_CONTINUITY, UNIT_LADDER[rank]);
                case PREFERRED_TIME -> weights.put(ConstraintKeys.TIME_PREFERENCE_SOFT, UNIT_LADDER[rank]);
                case LEVEL -> {
                    weights.put(ConstraintKeys.LEVEL_BALANCE, LEVEL_LADDER[rank]);
                    weights.put(ConstraintKeys.GROUP_ORDER_BY_LEVEL, ORDER_LADDER[rank]);
                }
            }
        }
        return Map.copyOf(weights);
    }

    /** Which {@link Priority} bucket (if any) a constraint key belongs to — the inverse of {@link
     * #constraintKeysOf(Priority)}, used by the explain module to say "this constraint belongs to
     * your Nth priority". Empty for any key outside the six priority-order buckets. */
    public static Optional<Priority> bucketOf(String constraintKey) {
        return Optional.ofNullable(BUCKET_OF_KEY.get(constraintKey));
    }

    /** The Swedish, user-facing label for a {@link Priority} family. */
    public static String labelSv(Priority priority) {
        return LABEL_SV.get(priority);
    }

    /** The {@link ConstraintKeys} constants a {@link Priority} family expands into (see class
     * javadoc's constraint-key groups). */
    public static Set<String> constraintKeysOf(Priority priority) {
        return CONSTRAINT_KEYS_OF.get(priority);
    }

    private static void validatePermutation(List<Priority> order) {
        if (order == null || order.size() != Priority.values().length || Set.copyOf(order).size() != Priority.values().length) {
            throw new IllegalArgumentException(
                    "Prioritetsordningen måste innehålla alla fyra prioriteter exakt en gång vardera: "
                            + "Träna tillsammans, Tidigare grupp, Önskad träningstid, Träningsnivå");
        }
    }

    private static Map<Priority, Set<String>> buildConstraintKeysOf() {
        Map<Priority, Set<String>> map = new EnumMap<>(Priority.class);
        map.put(Priority.TRAIN_TOGETHER, Set.of(ConstraintKeys.SAME_GROUP_SOFT, ConstraintKeys.DIFFERENT_GROUP_SOFT));
        map.put(Priority.PREVIOUS_GROUP, Set.of(ConstraintKeys.PREVIOUS_GROUP_CONTINUITY));
        map.put(Priority.PREFERRED_TIME, Set.of(ConstraintKeys.TIME_PREFERENCE_SOFT));
        map.put(Priority.LEVEL, Set.of(ConstraintKeys.LEVEL_BALANCE, ConstraintKeys.GROUP_ORDER_BY_LEVEL));
        return Map.copyOf(map);
    }

    private static Map<Priority, String> buildLabelsSv() {
        Map<Priority, String> map = new EnumMap<>(Priority.class);
        map.put(Priority.TRAIN_TOGETHER, "Träna tillsammans");
        map.put(Priority.PREVIOUS_GROUP, "Tidigare grupp");
        map.put(Priority.PREFERRED_TIME, "Önskad träningstid");
        map.put(Priority.LEVEL, "Träningsnivå");
        return Map.copyOf(map);
    }

    private static Map<String, Priority> buildBucketOfKey() {
        Map<String, Priority> map = new LinkedHashMap<>();
        for (Priority priority : Priority.values()) {
            for (String key : CONSTRAINT_KEYS_OF.get(priority)) {
                map.put(key, priority);
            }
        }
        return Map.copyOf(map);
    }
}
