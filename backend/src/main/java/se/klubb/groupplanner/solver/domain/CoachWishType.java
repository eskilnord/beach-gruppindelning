package se.klubb.groupplanner.solver.domain;

/**
 * Kind of a {@link CoachWish} a participant/group expresses about a specific coach
 * (docs/design/04-solver.md §1.2), sourced from a {@code coachRelation} field's configured {@code
 * constraintType} + {@code hardOrSoft}: {@code COACH_PREFERENCE}+{@code SOFT} -&gt; {@link #WANT}
 * (M6b), {@code COACH_PREFERENCE}+{@code HARD} -&gt; {@link #MUST}, {@code COACH_FORBIDDEN}+{@code
 * HARD} -&gt; {@link #CANNOT}.
 */
public enum CoachWishType {
    WANT,
    MUST,
    CANNOT
}
