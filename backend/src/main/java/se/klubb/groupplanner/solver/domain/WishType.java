package se.klubb.groupplanner.solver.domain;

/**
 * Kind of a {@link PersonPairWish} between two participants (docs/design/04-solver.md §1.2), sourced
 * from a {@code personRelation} field's configured {@code constraintType} + {@code hardOrSoft}:
 * {@code SAME_GROUP}+{@code HARD} -&gt; {@link #MUST_SAME}, {@code SAME_GROUP}+{@code SOFT} -&gt;
 * {@link #WANT_SAME}, {@code DIFFERENT_GROUP}+{@code HARD} -&gt; {@link #MUST_DIFFERENT}, {@code
 * DIFFERENT_GROUP}+{@code SOFT} -&gt; {@link #WANT_DIFFERENT}.
 */
public enum WishType {
    MUST_SAME,
    WANT_SAME,
    MUST_DIFFERENT,
    WANT_DIFFERENT
}
