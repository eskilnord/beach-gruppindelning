package se.klubb.groupplanner.fields;

/**
 * The user-settable constraint/field weight bounds (backend/docs/m6a-notes.md "Review fix 5").
 *
 * <p>{@link #MAX_WEIGHT} = 10 000 is the exact "max UI weight" assumption the solver's score
 * overflow-headroom analysis rests on (docs/design/04-solver.md §3.4, asserted by
 * {@code se.klubb.groupplanner.solver.ScoreHeadroomTest}): the analytic worst case there is
 * computed WITH this ceiling, giving {@code HardMediumSoftLongScore} a &gt;1e8 headroom factor.
 * Accepting a larger weight would silently invalidate that analysis, so both write paths
 * ({@code ConstraintWeightService} for constraint overrides, {@code FieldDefinitionValidator} for
 * field weights) reject anything above it with a 400.
 */
public final class WeightLimits {

    public static final int MIN_WEIGHT = 1;
    public static final int MAX_WEIGHT = 10_000;

    private WeightLimits() {
    }
}
