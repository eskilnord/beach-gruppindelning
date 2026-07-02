package se.klubb.groupplanner.solver.constraints;

import java.util.Set;

/**
 * Stable constraint key registry (docs/design/04-solver.md §4): the single source of truth for the
 * string names used both by {@link GroupPlanConstraintProvider#defineConstraints}'s {@code
 * .asConstraint(key)} calls AND by {@code SolverInputAssembler}'s {@code ConstraintWeightOverrides}
 * construction — the same pattern already established by {@code se.klubb.groupplanner.fields
 * .ConstraintTypes}/{@code HardOrSoft}.
 *
 * <p>Where a key already exists as a seeded {@code constraint_definition} row from an earlier
 * milestone (V2), the constant reuses that exact string rather than the shorthand name used in
 * design doc's own §4 table (e.g. {@code groupMaxSizeHard}, not {@code groupMaxSize}) — see
 * backend/docs/m6a-notes.md "Deviations" for the full reconciliation between the M1-seeded 24
 * constraints and this design doc's per-constraint granularity.
 *
 * <p><b>M6a scope: HARD constraints + {@code unassignedPlayer} only.</b> SOFT constraints (§4's
 * remaining rows) are M6b scope and have no key here yet.
 */
public final class ConstraintKeys {

    private ConstraintKeys() {
    }

    // --- HARD: satisfied by construction, no Constraint Streams code (documented, tested at the
    // model level rather than via ConstraintVerifier) ---
    public static final String GROUP_REQUIRES_TRAINING_BLOCK = "groupRequiresTrainingBlock"; // §10.2
    public static final String LOCKED_ASSIGNMENT_HARD = "lockedAssignmentHard"; // §10.23

    // --- HARD: implemented in GroupPlanConstraintProvider ---
    public static final String TRAINING_BLOCK_CAPACITY = "trainingBlockCapacity"; // §10.1
    public static final String GROUP_MAX_SIZE_HARD = "groupMaxSizeHard"; // §10.3
    public static final String TIME_AVAILABILITY_HARD = "timeAvailabilityHard"; // §10.9
    public static final String SAME_GROUP_HARD = "sameGroupHard"; // §10.11
    public static final String DIFFERENT_GROUP_HARD = "differentGroupHard"; // §10.13
    public static final String COACH_NO_OVERLAP = "coachNoOverlap"; // §10.15
    public static final String PLAYER_NO_OVERLAP = "playerNoOverlap"; // §10.16
    public static final String COACH_CANNOT_TRAIN_AND_COACH_SAME_TIME = "coachCannotTrainAndCoachSameTime"; // §10.17
    public static final String COACH_AVAILABILITY_HARD = "coachAvailabilityHard"; // §10.18
    public static final String COACH_REQUIREMENT_HARD = "coachRequirementHard"; // §10.19
    public static final String COACH_MAX_GROUPS = "coachMaxGroups"; // spec §13.1, no § number
    public static final String COACH_WISH_REQUIRED = "coachWishRequired"; // §10.21b
    public static final String COACH_WISH_FORBIDDEN = "coachWishForbidden"; // §10.21c
    public static final String SAVED_PLAN_PERSON_BLOCKED = "savedPlanPersonBlocked"; // §10.24a
    public static final String SAVED_PLAN_COACH_BLOCKED = "savedPlanCoachBlocked"; // §10.24b
    public static final String SAVED_PLAN_COURT_BLOCKED = "savedPlanCourtBlocked"; // §10.24c

    // --- MEDIUM: the reserved waitlist penalty (never disableable/reclassifiable, ADR-006) ---
    public static final String UNASSIGNED_PLAYER = "unassignedPlayer";

    /** Every constraint key {@link GroupPlanConstraintProvider} actually implements as Constraint
     * Streams code (excludes the two "satisfied by construction" keys above, which never appear in
     * {@code ConstraintWeightOverrides} because the provider never calls {@code .asConstraint} for
     * them). Used defensively by {@code SolverInputAssembler} to filter DB constraint weights down
     * to keys the solver can actually resolve, so an unimplemented/reserved key (e.g. a M6b-only
     * SOFT constraint, or the M6b-reserved empty-group complement keys) can never reach {@code
     * ConstraintWeightOverrides} and blow up the solver at build/solve time with an unknown
     * constraint name. */
    public static final Set<String> IMPLEMENTED = Set.of(
            TRAINING_BLOCK_CAPACITY,
            GROUP_MAX_SIZE_HARD,
            TIME_AVAILABILITY_HARD,
            SAME_GROUP_HARD,
            DIFFERENT_GROUP_HARD,
            COACH_NO_OVERLAP,
            PLAYER_NO_OVERLAP,
            COACH_CANNOT_TRAIN_AND_COACH_SAME_TIME,
            COACH_AVAILABILITY_HARD,
            COACH_REQUIREMENT_HARD,
            COACH_MAX_GROUPS,
            COACH_WISH_REQUIRED,
            COACH_WISH_FORBIDDEN,
            SAVED_PLAN_PERSON_BLOCKED,
            SAVED_PLAN_COACH_BLOCKED,
            SAVED_PLAN_COURT_BLOCKED,
            UNASSIGNED_PLAYER);

    /**
     * Reserved names only (docs/design/05-solver-verification.md minor finding: empty-group
     * complement constraints need explicit, pre-agreed keys so a future weight-override fan-out
     * lands on the right pair). Not implemented until M6b (SOFT, groupSizeTarget/groupMinSizeSoft's
     * empty-group complements) - kept here purely so no other M6a code accidentally reuses these
     * strings for something else.
     */
    public static final String GROUP_SIZE_TARGET_EMPTY_RESERVED = "groupSizeTargetEmpty";
    public static final String GROUP_MIN_SIZE_EMPTY_RESERVED = "groupMinSizeEmpty";
}
