package se.klubb.groupplanner.explain;

import ai.timefold.solver.core.api.score.buildin.hardmediumsoftlong.HardMediumSoftLongScore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import se.klubb.groupplanner.explain.ExplanationDtos.WeightBreakEvenView;
import se.klubb.groupplanner.fields.WeightLimits;

/**
 * M-E3 "vad skulle krävas?" (advanced mode): for a {@code TRADE_OFF} unmet wish's best candidate,
 * closed-form per-constraint break-even — the weight a SINGLE constraint key would need to cross,
 * holding every OTHER constraint's current weight fixed, for the move to stop costing points. Same
 * linearity-spike foundation as {@link PrioritySensitivityCalculator} ({@code Δscore(w') =
 * Σ units_k·w'_k}), zero additional {@code analyze()} calls, integer arithmetic only.
 *
 * <p><b>Derivation.</b> For key {@code k} with {@code unitsKnown} and {@code units_k != 0}, let
 * {@code C = Σ_{j != k} units_j·w_j} — the SOFT-level total contributed by every OTHER constraint at
 * its CURRENT weight (this is just {@code totalSoft - scoreDelta_k.softScore()}, since each {@code
 * ConstraintDelta.scoreDelta()} already IS {@code units_j · w_j} at the current weight). Varying only
 * {@code k}'s weight MAGNITUDE {@code W} (1..{@link WeightLimits#MAX_WEIGHT}), keeping its
 * penalize/reward sign fixed, the total becomes {@code C + magnitudeUnits_k · W} where {@code
 * magnitudeUnits_k = units_k} for a REWARD constraint and {@code -units_k} for a PENALIZE one (a
 * REWARD constraint's applied weight is {@code +W}; a PENALIZE constraint's is {@code -W} — see the
 * linearity spike's cross-cutting sign finding). Solving {@code C + magnitudeUnits_k·W >= 0}:
 *
 * <ul>
 *   <li>{@code magnitudeUnits_k < 0} (raising {@code k}'s weight makes the total WORSE): the
 *       inequality flips on division, giving an UPPER bound — {@code AT_MOST}, {@code W_max =
 *       floorDiv(C, -magnitudeUnits_k)}.
 *   <li>{@code magnitudeUnits_k > 0} (raising {@code k}'s weight makes the total BETTER): a LOWER
 *       bound — {@code AT_LEAST}, {@code W_min = ceilDiv(-C, magnitudeUnits_k)}.
 * </ul>
 *
 * <p>Both are then clamped to {@code [MIN_WEIGHT, MAX_WEIGHT]}; a bound that clamps away entirely
 * (the AT_MOST ceiling is below 1, or the AT_LEAST floor is above 10 000) is reported as {@code
 * impossibleReasonSv} instead of a threshold — never a silently-wrong number outside the allowed
 * range. Rows with {@code units_k == 0} (this move never touched the constraint at all) are omitted.
 */
final class WeightBreakEven {

    private WeightBreakEven() {
    }

    static List<WeightBreakEvenView> compute(
            List<MoveProbe.ConstraintDelta> perConstraint, Map<String, HardMediumSoftLongScore> currentWeights) {
        long totalSoft = 0L;
        for (MoveProbe.ConstraintDelta d : perConstraint) {
            totalSoft += d.scoreDelta().softScore();
        }

        List<WeightBreakEvenView> rows = new ArrayList<>();
        for (MoveProbe.ConstraintDelta d : perConstraint) {
            if (!d.unitsKnown() || d.units() == 0L) {
                continue;
            }
            // FIX 4 (M-E3 review, MINOR): precondition — this class's whole closed-form derivation (see
            // class javadoc's "Derivation" section) is SOFT-level only ({@code totalSoft}/{@code C} are
            // sums of softScore()). A delta whose nonzero component is HARD or MEDIUM cannot be folded
            // into that same linear soft-only model; skip it rather than silently misreporting a
            // hard/medium-level break-even as though it were a soft-weight threshold.
            if (d.scoreDelta().hardScore() != 0L || d.scoreDelta().mediumScore() != 0L) {
                continue;
            }
            String key = d.key();
            ConstraintMetadata.Meta meta = ConstraintMetadata.of(key);
            long c = totalSoft - d.scoreDelta().softScore();
            long magnitudeUnits = meta.direction() == ConstraintMetadata.Direction.REWARD ? d.units() : -d.units();
            if (magnitudeUnits == 0L) {
                continue; // Defensive: cannot happen (units_k != 0 and sign is +-1), kept for clarity.
            }

            // FIX 4 (M-E3 review, MINOR): self-verifying sign assertion — the SIGN of the raw, signed
            // per-match weight this move's own data implies ({@code scoreDelta.softScore() / units},
            // exact per MoveProbe's own units derivation — see MoveProbe#derivePerConstraintDeltas'
            // FIX-9 zero-remainder guarantee) must agree with the direction this key is REGISTERED under
            // in ConstraintMetadata (negative for PENALIZE, positive for REWARD). A mismatch means the
            // registry has drifted out of sync with the actual constraint code — silently trusting
            // meta.direction() in that case would flip AT_MOST/AT_LEAST backwards without any signal;
            // fail loudly instead.
            long signedWeight = d.scoreDelta().softScore() / d.units();
            if (signedWeight != 0L) {
                boolean impliesPenalize = signedWeight < 0L;
                boolean registeredPenalize = meta.direction() == ConstraintMetadata.Direction.PENALIZE;
                if (impliesPenalize != registeredPenalize) {
                    throw new IllegalStateException(
                            "WeightBreakEven sign mismatch for constraint '" + key + "': scoreDelta/units implies a "
                                    + (impliesPenalize ? "PENALIZE" : "REWARD") + "-signed weight but ConstraintMetadata "
                                    + "registers " + meta.direction() + " - registry drift would silently flip "
                                    + "AT_MOST/AT_LEAST for this constraint.");
                }
            }

            long currentWeight = Math.abs(componentOf(currentWeights.get(key), key));

            if (magnitudeUnits < 0L) {
                long rawMax = floorDiv(c, -magnitudeUnits);
                if (rawMax < WeightLimits.MIN_WEIGHT) {
                    rows.add(new WeightBreakEvenView(
                            key, meta.label(), currentWeight, "AT_MOST", null, null,
                            "Inte ens den lägsta tillåtna vikten (%s) räcker.".formatted(formatWeight(WeightLimits.MIN_WEIGHT))));
                } else {
                    long threshold = Math.min(rawMax, WeightLimits.MAX_WEIGHT);
                    rows.add(new WeightBreakEvenView(
                            key, meta.label(), currentWeight, "AT_MOST", (int) threshold,
                            "%s får som mest vara %d för att flytten inte ska kosta poäng.".formatted(meta.label(), threshold),
                            null));
                }
            } else {
                long rawMin = ceilDiv(-c, magnitudeUnits);
                if (rawMin > WeightLimits.MAX_WEIGHT) {
                    rows.add(new WeightBreakEvenView(
                            key, meta.label(), currentWeight, "AT_LEAST", null, null,
                            "Inte ens den högsta tillåtna vikten (%s) räcker.".formatted(formatWeight(WeightLimits.MAX_WEIGHT))));
                } else {
                    long threshold = Math.max(rawMin, WeightLimits.MIN_WEIGHT);
                    rows.add(new WeightBreakEvenView(
                            key, meta.label(), currentWeight, "AT_LEAST", (int) threshold,
                            "%s behöver minst vara %d för att flytten inte ska kosta poäng.".formatted(meta.label(), threshold),
                            null));
                }
            }
        }
        return rows;
    }

    /** Swedish thousands grouping (plain space, e.g. {@code 10 000}) for {@link WeightLimits#MIN_WEIGHT}/
     * {@link WeightLimits#MAX_WEIGHT} interpolated into the "impossible" messages above (FIX 4, M-E3
     * review) — deliberately NOT {@code java.text.NumberFormat}'s Swedish locale, whose CLDR grouping
     * separator is a non-breaking space, not the plain space this API's other Swedish text uses. */
    private static String formatWeight(int value) {
        String digits = Integer.toString(value);
        StringBuilder result = new StringBuilder();
        int fromEnd = 0;
        for (int i = digits.length() - 1; i >= 0; i--) {
            result.append(digits.charAt(i));
            fromEnd++;
            if (fromEnd % 3 == 0 && i != 0) {
                result.append(' ');
            }
        }
        return result.reverse().toString();
    }

    private static long componentOf(HardMediumSoftLongScore weight, String key) {
        if (weight == null) {
            return 0L;
        }
        // Every ConstraintKeys.IMPLEMENTED constraint declares its weight at exactly one level - see
        // MoveProbe#soleNonZeroLevel's own invariant, reused here as a simple sum (only one term can
        // ever be nonzero for a well-formed weight).
        return weight.hardScore() + weight.mediumScore() + weight.softScore();
    }

    private static long floorDiv(long a, long b) {
        return Math.floorDiv(a, b);
    }

    /** {@code ceilDiv} for a POSITIVE divisor {@code b} (always true here — {@code magnitudeUnits_k}
     * in the {@code AT_LEAST} branch is strictly positive by construction). */
    private static long ceilDiv(long a, long b) {
        return -Math.floorDiv(-a, b);
    }
}
